package com.example.agentic.governance;

import com.example.agentic.audit.AuditEventType;
import com.example.agentic.audit.AuditService;
import com.example.agentic.domain.ActionType;
import com.example.agentic.domain.ApprovalRequest;
import com.example.agentic.domain.ApprovalStatus;
import com.example.agentic.exception.ConflictException;
import com.example.agentic.exception.InvalidRequestException;
import com.example.agentic.exception.ResourceNotFoundException;
import com.example.agentic.repository.ApprovalRequestRepository;
import com.example.agentic.repository.WorkflowExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Human-in-the-loop checkpoints. Approvals are rows; the workflow cannot pass a gate without an APPROVED row. */
@Service
public class ApprovalService {
    private final ApprovalRequestRepository repository;
    private final WorkflowExecutionRepository workflows;
    private final PolicyEngine policy;
    private final AuditService audit;
    private final Clock clock;

    public ApprovalService(ApprovalRequestRepository repository, WorkflowExecutionRepository workflows,
                           PolicyEngine policy, AuditService audit, Clock clock) {
        this.repository = repository;
        this.workflows = workflows;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
    }

    /** Idempotent per gate task: re-opening returns the existing pending request. */
    @Transactional
    public ApprovalRequest open(String workflowId, String taskKey, List<ActionType> actions, String description,
                                String requestedBy, String payloadJson) {
        if (actions.isEmpty()) {
            throw new InvalidRequestException("An approval needs at least one action type");
        }
        actions.forEach(policy::assertNotForbidden);
        if (taskKey != null) {
            Optional<ApprovalRequest> existing = repository.findByWorkflowIdAndStatus(workflowId, ApprovalStatus.PENDING)
                    .stream().filter(a -> taskKey.equals(a.getTaskKey())).findFirst();
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        ApprovalRequest r = new ApprovalRequest();
        r.setWorkflow(workflows.getReferenceById(workflowId));
        r.setTaskKey(taskKey);
        r.setPrimaryType(actions.get(0));
        r.setActionTypes(actions.stream().map(Enum::name).collect(Collectors.joining(",")));
        r.setDescription(description);
        r.setRequestedBy(requestedBy);
        r.setRequestedAt(Instant.now(clock));
        r.setPayloadJson(payloadJson);
        ApprovalRequest saved = repository.save(r);
        audit.record(workflowId, AuditEventType.APPROVAL_REQUESTED, requestedBy,
                Map.of("approvalId", saved.getId(), "task", String.valueOf(taskKey), "actions", saved.getActionTypes()));
        return saved;
    }

    @Transactional
    public ApprovalRequest decide(String workflowId, Long approvalId, boolean approve, String decider, String comment) {
        List<ApprovalRequest> pending = repository.findByWorkflowIdAndStatus(workflowId, ApprovalStatus.PENDING);
        ApprovalRequest request;
        if (approvalId == null) {
            if (pending.isEmpty()) {
                throw new ConflictException("NO_PENDING_APPROVAL", "Workflow has no pending approval");
            }
            if (pending.size() > 1) {
                throw new InvalidRequestException("Several approvals are pending; specify approvalId: "
                        + pending.stream().map(a -> String.valueOf(a.getId())).collect(Collectors.joining(",")));
            }
            request = pending.get(0);
        } else {
            request = repository.findById(approvalId)
                    .filter(a -> a.getWorkflowId().equals(workflowId))
                    .orElseThrow(() -> new ResourceNotFoundException("Approval " + approvalId + " not found in workflow " + workflowId));
            if (request.getStatus() != ApprovalStatus.PENDING) {
                throw new ConflictException("APPROVAL_ALREADY_DECIDED", "Approval " + approvalId + " is already " + request.getStatus());
            }
        }
        request.setStatus(approve ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED);
        request.setDecidedBy(decider);
        request.setDecidedAt(Instant.now(clock));
        request.setDecisionComment(comment);
        audit.record(workflowId, approve ? AuditEventType.APPROVAL_GRANTED : AuditEventType.APPROVAL_REJECTED, decider,
                Map.of("approvalId", request.getId(), "actions", request.getActionTypes(),
                        "comment", comment == null ? "" : comment));
        return request;
    }

    /** Supersedes pending and already-granted approvals of the given gate tasks (their basis changed). */
    @Transactional
    public int supersedeForTasks(String workflowId, Set<String> taskKeys, String reason) {
        List<ApprovalRequest> hit = repository.findByWorkflowIdOrderByIdAsc(workflowId).stream()
                .filter(a -> a.getTaskKey() != null && taskKeys.contains(a.getTaskKey())
                        && (a.getStatus() == ApprovalStatus.PENDING || a.getStatus() == ApprovalStatus.APPROVED)).toList();
        hit.forEach(a -> {
            a.setStatus(ApprovalStatus.SUPERSEDED);
            audit.record(workflowId, AuditEventType.APPROVAL_SUPERSEDED, "system", Map.of("approvalId", a.getId(), "reason", reason));
        });
        return hit.size();
    }

    @Transactional
    public int supersedeAllPending(String workflowId, String reason) {
        List<ApprovalRequest> pending = repository.findByWorkflowIdAndStatus(workflowId, ApprovalStatus.PENDING);
        pending.forEach(a -> {
            a.setStatus(ApprovalStatus.SUPERSEDED);
            audit.record(workflowId, AuditEventType.APPROVAL_SUPERSEDED, "system", Map.of("approvalId", a.getId(), "reason", reason));
        });
        return pending.size();
    }

    @Transactional(readOnly = true)
    public boolean isApproved(String workflowId, ActionType action) {
        return repository.findByWorkflowIdAndStatus(workflowId, ApprovalStatus.APPROVED).stream()
                .anyMatch(a -> Arrays.asList(a.getActionTypes().split(",")).contains(action.name()));
    }

    @Transactional(readOnly = true)
    public List<ApprovalRequest> pending(String workflowId) {
        return repository.findByWorkflowIdAndStatus(workflowId, ApprovalStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public List<ApprovalRequest> list(String workflowId) {
        return repository.findByWorkflowIdOrderByIdAsc(workflowId);
    }
}