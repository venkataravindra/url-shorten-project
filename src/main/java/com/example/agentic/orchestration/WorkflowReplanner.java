package com.example.agentic.orchestration;

import com.example.agentic.agent.RequirementNormalizer;
import com.example.agentic.audit.AuditEventType;
import com.example.agentic.audit.AuditService;
import com.example.agentic.domain.TaskStatus;
import com.example.agentic.domain.WorkflowExecution;
import com.example.agentic.domain.WorkflowStatus;
import com.example.agentic.domain.WorkflowTask;
import com.example.agentic.exception.InvalidRequestException;
import com.example.agentic.exception.InvalidWorkflowStateException;
import com.example.agentic.governance.ApprovalService;
import com.example.agentic.governance.SecurityValidator;
import com.example.agentic.repository.WorkflowTaskRepository;
import com.example.agentic.service.ArtifactService;
import com.example.agentic.service.DecisionService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * Dynamic replanning. propose() is read-only impact analysis; apply() marks affected tasks STALE (their old
 * outputs, decisions and artifacts are superseded, not deleted), preserves unaffected completed work and
 * returns the workflow to CREATED so execution can resume safely.
 */
@Component
public class WorkflowReplanner {
    private static final Set<WorkflowStatus> REPLANNABLE = EnumSet.of(WorkflowStatus.CREATED,
            WorkflowStatus.WAITING_FOR_APPROVAL, WorkflowStatus.SAFE_STOPPED, WorkflowStatus.COMPLETED, WorkflowStatus.FAILED);

    private final WorkflowStateStore store;
    private final DependencyResolver resolver;
    private final WorkflowTaskRepository taskRepository;
    private final ApprovalService approvals;
    private final DecisionService decisions;
    private final ArtifactService artifacts;
    private final SecurityValidator validator;
    private final AuditService audit;
    private final Clock clock;

    public WorkflowReplanner(WorkflowStateStore store, DependencyResolver resolver, WorkflowTaskRepository taskRepository,
                             ApprovalService approvals, DecisionService decisions, ArtifactService artifacts,
                             SecurityValidator validator, AuditService audit, Clock clock) {
        this.store = store;
        this.resolver = resolver;
        this.taskRepository = taskRepository;
        this.approvals = approvals;
        this.decisions = decisions;
        this.artifacts = artifacts;
        this.validator = validator;
        this.audit = audit;
        this.clock = clock;
    }

    public ReplanProposal propose(String workflowId, ReplanCommand cmd) {
        WorkflowExecution w = store.get(workflowId);
        if (!REPLANNABLE.contains(w.getStatus())) {
            throw new InvalidWorkflowStateException("Workflow in state " + w.getStatus() + " cannot be replanned");
        }
        if (cmd.reason() == null || cmd.reason().isBlank()) {
            throw new InvalidRequestException("A replan needs a reason");
        }
        WorkflowSnapshot snap = store.snapshot(workflowId);
        Set<String> keys = new LinkedHashSet<>();
        snap.tasks().forEach(t -> keys.add(t.key()));
        if (!keys.contains(cmd.changedTask())) {
            throw new InvalidRequestException("Unknown task '" + cmd.changedTask() + "'");
        }
        String newRequirement = null;
        if (cmd.newRequirement() != null && !cmd.newRequirement().isBlank()) {
            if (cmd.changeType() != ReplanChangeType.REQUIREMENT_CHANGE) {
                throw new InvalidRequestException("newRequirement is only valid for REQUIREMENT_CHANGE");
            }
            newRequirement = RequirementNormalizer.normalize(validator.validateRequirement(cmd.newRequirement()));
        }
        List<WorkflowEdge> existing = snap.edges();
        List<WorkflowEdge> added = new ArrayList<>();
        for (WorkflowEdge e : cmd.addDependencies()) {
            if (!keys.contains(e.from()) || !keys.contains(e.to())) {
                throw new InvalidRequestException("Dependency references unknown task: " + e.from() + " -> " + e.to());
            }
            if (!existing.contains(e) && !added.contains(e)) {
                added.add(e);
            }
        }
        List<WorkflowEdge> revised = new ArrayList<>(existing);
        revised.addAll(added);
        List<String> order = resolver.topologicalOrder(keys, revised); // throws on a cycle

        Set<String> impacted = new LinkedHashSet<>();
        List<String> seeds = new ArrayList<>(List.of(cmd.changedTask()));
        added.forEach(e -> seeds.add(e.to()));
        for (String seed : seeds) {
            impacted.add(seed);
            impacted.addAll(resolver.descendants(seed, revised));
        }
        List<String> impactedOrdered = order.stream().filter(impacted::contains).toList();
        List<String> preserved = snap.tasks().stream()
                .filter(t -> t.status() == TaskStatus.COMPLETED || t.status() == TaskStatus.SKIPPED)
                .map(TaskSnapshot::key).filter(k -> !impacted.contains(k)).toList();
        return new ReplanProposal(workflowId, cmd.changedTask(), cmd.changeType(), cmd.reason(), newRequirement,
                impactedOrdered, preserved, added, revised, cmd.changeType().highImpact(), w.getStatus());
    }

    @Transactional
    public void apply(ReplanProposal p, String actor) {
        String id = p.workflowId();
        WorkflowExecution w = store.get(id);
        store.resetInterruptedTasks(id);
        Set<String> impacted = new LinkedHashSet<>(p.impactedTasks());
        for (String key : impacted) {
            WorkflowTask t = taskRepository.findByWorkflowIdAndNodeKey(id, key).orElseThrow();
            if (t.getStatus() != TaskStatus.PENDING) {
                t.setStatus(TaskStatus.STALE);
                t.setRevision(t.getRevision() + 1);
                t.setAttempts(0);
                t.setCompletedAt(null);
                t.setFailureReason(null);
                t.setFailureKind(null);
                t.setUpdatedAt(Instant.now(clock));
            }
        }
        String why = "replan: " + p.reason();
        approvals.supersedeForTasks(id, impacted, why);
        int decisionsSuperseded = decisions.supersedeForTasks(id, impacted, why);
        int artifactsSuperseded = artifacts.supersedeForTasks(id, impacted);
        int revision = store.dependencies(id).stream().mapToInt(d -> d.getAddedInRevision()).max().orElse(1) + 1;
        for (WorkflowEdge e : p.addedEdges()) {
            store.addDependency(w, e.to(), e.from(), revision);
        }
        if (p.newRequirement() != null) {
            store.setRequirement(id, p.newRequirement());
        }
        if (w.getSafeStopCategory() != null) {
            store.clearSafeStop(id);
        }
        store.transition(id, WorkflowStatus.CREATED);
        audit.record(id, AuditEventType.REPLAN_APPLIED, actor, Map.of("changedTask", p.changedTask(),
                "changeType", p.changeType().name(), "reason", p.reason(), "impacted", p.impactedTasks(),
                "preserved", p.preservedTasks(), "decisionsSuperseded", decisionsSuperseded,
                "artifactsSuperseded", artifactsSuperseded, "addedEdges", p.addedEdges().size()));
    }
}