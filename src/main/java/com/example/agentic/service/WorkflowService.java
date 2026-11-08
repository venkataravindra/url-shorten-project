package com.example.agentic.service;

import com.example.agentic.agent.DecisionDraft;
import com.example.agentic.agent.RequirementNormalizer;
import com.example.agentic.audit.AuditEventType;
import com.example.agentic.audit.AuditService;
import com.example.agentic.domain.*;
import com.example.agentic.dto.*;
import com.example.agentic.exception.ConflictException;
import com.example.agentic.exception.InvalidRequestException;
import com.example.agentic.exception.InvalidWorkflowStateException;
import com.example.agentic.governance.ApprovalService;
import com.example.agentic.governance.SafeStopService;
import com.example.agentic.governance.SecurityValidator;
import com.example.agentic.orchestration.*;
import com.example.agentic.repository.WorkflowExecutionRepository;
import com.example.agentic.urlshortener.dto.PagedResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Application facade used by controllers: coordinates engine, governance and persistence. Holds no business rules itself. */
@Service
public class WorkflowService {
    private final SecurityValidator validator;
    private final WorkflowStateStore store;
    private final WorkflowDefinitionFactory factory;
    private final WorkflowEngine engine;
    private final ApprovalService approvals;
    private final ArtifactService artifacts;
    private final DecisionService decisions;
    private final SafeStopService safeStop;
    private final RollbackService rollback;
    private final WorkflowReplanner replanner;
    private final AuditService audit;
    private final JsonSupport json;
    private final WorkflowExecutionRepository repository;

    public WorkflowService(SecurityValidator validator, WorkflowStateStore store, WorkflowDefinitionFactory factory,
                           WorkflowEngine engine, ApprovalService approvals, ArtifactService artifacts,
                           DecisionService decisions, SafeStopService safeStop, RollbackService rollback,
                           WorkflowReplanner replanner, AuditService audit, JsonSupport json,
                           WorkflowExecutionRepository repository) {
        this.validator = validator;
        this.store = store;
        this.factory = factory;
        this.engine = engine;
        this.approvals = approvals;
        this.artifacts = artifacts;
        this.decisions = decisions;
        this.safeStop = safeStop;
        this.rollback = rollback;
        this.replanner = replanner;
        this.audit = audit;
        this.json = json;
        this.repository = repository;
    }

    // ------------------------------------------------------------------ create / read
    public WorkflowResponse create(CreateWorkflowRequest request, String actor) {
        validator.validateRequirement(request.requirement());
        String normalized = RequirementNormalizer.normalize(request.requirement());
        WorkflowExecution w = store.create(normalized, request.title(), actor, factory.standard());
        audit.record(w.getId(), AuditEventType.WORKFLOW_CREATED, actor, Map.of("title", String.valueOf(w.getTitle())));
        return toResponse(w);
    }

    public WorkflowResponse get(String id) {
        return toResponse(store.get(id));
    }

    public PagedResponse<WorkflowResponse> list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidRequestException("page must be >= 0 and size between 1 and 100");
        }
        return PagedResponse.of(repository.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))), this::toResponse);
    }

    public List<TaskResponse> tasks(String id, boolean includeOutput) {
        store.get(id);
        Map<String, Set<String>> deps = new HashMap<>();
        store.dependencies(id).forEach(d -> deps.computeIfAbsent(d.getTaskKey(), k -> new TreeSet<>()).add(d.getDependsOnKey()));
        return store.tasks(id).stream().map(t -> new TaskResponse(t.getNodeKey(), t.getTitle(), t.getAgentId(),
                t.getNodeType().name(), t.getStage().name(), t.getStatus().name(), t.getRevision(), t.getAttempts(),
                deps.getOrDefault(t.getNodeKey(), Set.of()), t.getConditionExpr(),
                t.getFailureKind() == null ? null : t.getFailureKind().name(), t.getFailureReason(), t.getStartedAt(),
                t.getCompletedAt(), includeOutput ? json.toMap(t.getOutputJson()) : null)).toList();
    }

    // ------------------------------------------------------------------ execution
    public WorkflowResponse execute(String id, String acknowledgement, String actor, boolean approver) {
        WorkflowExecution w = store.get(id);
        switch (w.getStatus()) {
            case CREATED, WAITING_FOR_APPROVAL -> { }
            case SAFE_STOPPED -> {
                if (!approver) {
                    throw new AccessDeniedException("Only an APPROVER may resume a safe-stopped workflow");
                }
                safeStop.resume(id, actor, acknowledgement);
            }
            case RUNNING, RETRYING -> throw new ConflictException("WORKFLOW_BUSY", "Workflow is already executing");
            default -> throw new InvalidWorkflowStateException("Workflow is " + w.getStatus() + " and cannot be executed");
        }
        if (replanPending(id)) {
            throw new InvalidWorkflowStateException("A high-impact replan is awaiting approval; decide it first");
        }
        engine.run(id);
        return get(id);
    }

    public WorkflowResponse approve(String id, ApprovalDecisionRequest request, String approver) {
        ApprovalRequest r = approvals.decide(id, request.approvalId(), true, approver, request.comment());
        if (r.getPrimaryType() == ActionType.HIGH_IMPACT_REPLAN) {
            replanner.apply(json.fromJson(r.getPayloadJson(), ReplanProposal.class), approver);
        } else {
            completeGate(id, r, approver);
        }
        resume(id);
        return get(id);
    }

    public WorkflowResponse reject(String id, ApprovalDecisionRequest request, String approver) {
        ApprovalRequest r = approvals.decide(id, request.approvalId(), false, approver, request.comment());
        String comment = request.comment() == null ? "" : request.comment();
        if (r.getPrimaryType() == ActionType.HIGH_IMPACT_REPLAN) {
            ReplanProposal p = json.fromJson(r.getPayloadJson(), ReplanProposal.class);
            store.transition(id, p.previousStatus());
            audit.record(id, AuditEventType.REPLAN_DISCARDED, approver, Map.of("changedTask", p.changedTask(), "comment", comment));
        } else {
            store.failTask(id, r.getTaskKey(), FailureKind.POLICY, "Rejected by " + approver + ": " + comment, 0);
            rollback.rollback(id, "Approval of '" + r.getTaskKey() + "' rejected by " + approver + ": " + comment, approver);
        }
        return get(id);
    }

    public WorkflowResponse stop(String id, String reason, String actor) {
        if (store.status(id).isTerminal()) {
            throw new InvalidWorkflowStateException("Workflow is already " + store.status(id));
        }
        safeStop.safeStop(id, SafeStopCategory.OPERATOR_REQUEST, reason, actor);
        return get(id);
    }

    public WorkflowResponse cancel(String id, String reason, String actor) {
        WorkflowStatus status = store.status(id);
        if (status.isTerminal() && status != WorkflowStatus.FAILED) {
            throw new InvalidWorkflowStateException("Workflow is already " + status);
        }
        store.transition(id, WorkflowStatus.CANCELLED);
        approvals.supersedeAllPending(id, "workflow cancelled");
        audit.record(id, AuditEventType.WORKFLOW_CANCELLED, actor, Map.of("reason", reason == null ? "" : reason));
        return get(id);
    }

    public ReplanResponse replan(String id, ReplanRequest request, String actor) {
        List<WorkflowEdge> edges = request.addDependencies() == null ? List.of()
                : request.addDependencies().stream().map(e -> new WorkflowEdge(e.from(), e.to())).toList();
        ReplanProposal p = replanner.propose(id, new ReplanCommand(request.changedTask(), request.changeType(),
                request.reason(), request.newRequirement(), edges, actor));
        audit.record(id, AuditEventType.REPLAN_PROPOSED, actor, Map.of("changedTask", p.changedTask(),
                "changeType", p.changeType().name(), "highImpact", p.highImpact(), "impacted", p.impactedTasks()));
        List<EdgeDto> revised = p.revisedEdges().stream().map(e -> new EdgeDto(e.from(), e.to())).toList();
        if (p.highImpact()) {
            ApprovalRequest a = approvals.open(id, null, List.of(ActionType.HIGH_IMPACT_REPLAN),
                    "Approve high-impact replan (" + p.changeType() + ") of '" + p.changedTask() + "': " + p.reason()
                            + ". Tasks to re-execute: " + p.impactedTasks(), actor, json.toJson(p));
            store.transition(id, WorkflowStatus.WAITING_FOR_APPROVAL);
            return new ReplanResponse(false, true, a.getId(), true, p.changedTask(), p.changeType().name(),
                    p.impactedTasks(), p.preservedTasks(), revised, store.status(id).name());
        }
        replanner.apply(p, actor);
        return new ReplanResponse(true, false, null, false, p.changedTask(), p.changeType().name(),
                p.impactedTasks(), p.preservedTasks(), revised, store.status(id).name());
    }

    // ------------------------------------------------------------------ read models
    public List<ArtifactResponse> artifacts(String id, boolean includeContent) {
        store.get(id);
        return artifacts.list(id).stream().map(a -> new ArtifactResponse(a.getId(), a.getName(), a.getArtifactType(),
                a.getTaskKey(), a.getVersion(), a.getStatus().name(), a.getStoredPath(), a.getSha256(), a.getCreatedAt(),
                includeContent ? a.getContent() : null)).toList();
    }

    public List<DecisionResponse> decisions(String id) {
        store.get(id);
        return decisions.list(id).stream().map(d -> new DecisionResponse(d.getId(), d.getTaskKey(), d.getTaskRevision(),
                d.getTitle(), d.getRationale(), parseIds(d.getParentDecisionIds()), d.isSuperseded(), d.getSupersededReason(),
                d.getDecidedBy(), d.getDecidedAt())).toList();
    }

    public List<ApprovalResponse> approvals(String id) {
        store.get(id);
        return approvals.list(id).stream().map(this::toResponse).toList();
    }

    public SummaryResponse summary(String id) {
        WorkflowExecution w = store.get(id);
        WorkflowSnapshot snap = store.snapshot(id);
        Map<String, Object> summary = snap.outputs().get("engineering-summary");
        if (summary == null) {
            throw new InvalidWorkflowStateException("Engineering summary is not available: workflow is " + w.getStatus());
        }
        return new SummaryResponse(id, w.getStatus().name(), w.getScenarioType().name(), summary,
                snap.outputs().getOrDefault("release-readiness", Map.of()));
    }

    // ------------------------------------------------------------------ helpers
    private void completeGate(String id, ApprovalRequest r, String approver) {
        WorkflowSnapshot snap = store.snapshot(id);
        TaskSnapshot task = snap.task(r.getTaskKey());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("approved", true);
        output.put("approvedBy", approver);
        output.put("approvedAt", Instant.now().toString());
        output.put("comment", r.getDecisionComment() == null ? "" : r.getDecisionComment());
        output.put("actionTypes", Arrays.asList(r.getActionTypes().split(",")));
        if (task.key().equals("assumption-approval")) {
            output.put("approvedAssumptions", json.toMap(r.getPayloadJson()).getOrDefault("assumptions", List.of()));
        }
        store.completeTask(id, task.key(), output, 0);
        decisions.record(id, task.key(), task.revision(),
                new DecisionDraft("Approved " + r.getActionTypes(), r.getDecisionComment() == null ? "No comment" : r.getDecisionComment()),
                "human:" + approver, decisions.currentDecisionIds(id, task.dependsOn()));
        if (task.key().equals("final-approval")) {
            artifacts.acceptStaged(id, approver);
        }
    }

    private void resume(String id) {
        WorkflowStatus s = store.status(id);
        if ((s == WorkflowStatus.WAITING_FOR_APPROVAL || s == WorkflowStatus.CREATED) && !replanPending(id)) {
            try {
                engine.run(id);
            } catch (ConflictException busy) {
                // another request is already driving this workflow; it will observe the decision
            }
        }
    }

    private boolean replanPending(String id) {
        return approvals.pending(id).stream().anyMatch(a -> a.getPrimaryType() == ActionType.HIGH_IMPACT_REPLAN);
    }

    private List<Long> parseIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(Long::valueOf).toList();
    }

    private ApprovalResponse toResponse(ApprovalRequest a) {
        return new ApprovalResponse(a.getId(), a.getTaskKey(), a.getPrimaryType().name(),
                Arrays.asList(a.getActionTypes().split(",")), a.getDescription(), a.getStatus().name(), a.getRequestedBy(),
                a.getRequestedAt(), a.getDecidedBy(), a.getDecidedAt(), a.getDecisionComment());
    }

    private WorkflowResponse toResponse(WorkflowExecution w) {
        Map<String, Long> counts = store.tasks(w.getId()).stream()
                .collect(Collectors.groupingBy(t -> t.getStatus().name(), TreeMap::new, Collectors.counting()));
        List<ApprovalResponse> pending = approvals.pending(w.getId()).stream().map(this::toResponse).toList();
        return new WorkflowResponse(w.getId(), w.getTitle(), w.getRequirement(), w.getRequirementVersion(),
                w.getScenarioType().name(), w.getStatus().name(),
                w.getSafeStopCategory() == null ? null : w.getSafeStopCategory().name(), w.getSafeStopReason(),
                w.getFailureReason(), w.getCreatedBy(), w.getCreatedAt(), w.getStartedAt(), w.getCompletedAt(), counts, pending);
    }
}