package com.example.agentic.orchestration;

import com.example.agentic.agent.ArtifactDraft;
import com.example.agentic.agent.DecisionDraft;
import com.example.agentic.audit.AuditEventType;
import com.example.agentic.audit.AuditService;
import com.example.agentic.domain.*;
import com.example.agentic.exception.ConflictException;
import com.example.agentic.exception.InvalidWorkflowStateException;
import com.example.agentic.exception.PolicyViolationException;
import com.example.agentic.exception.SecurityConcernException;
import com.example.agentic.governance.ApprovalService;
import com.example.agentic.governance.SafeStopService;
import com.example.agentic.metrics.MetricsRecorder;
import com.example.agentic.service.ArtifactService;
import com.example.agentic.service.DecisionService;
import com.example.agentic.service.JsonSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinator of the DAG. Only this thread mutates workflow state; worker threads just run agents.
 * Loop: find ready tasks, evaluate conditions, open approval gates, dispatch agent tasks in parallel,
 * then block for the next completion. Terminal decision is taken when nothing is runnable or in flight.
 */
@Component
public class WorkflowEngine {
    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final WorkflowStateStore store;
    private final DependencyResolver resolver;
    private final ConditionEvaluator conditions;
    private final WorkflowScheduler scheduler;
    private final ResilientAgentExecutor executor;
    private final ApprovalService approvals;
    private final ArtifactService artifacts;
    private final DecisionService decisions;
    private final AuditService audit;
    private final SafeStopService safeStop;
    private final MetricsRecorder metrics;
    private final JsonSupport json;
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public WorkflowEngine(WorkflowStateStore store, DependencyResolver resolver, ConditionEvaluator conditions,
                          WorkflowScheduler scheduler, ResilientAgentExecutor executor, ApprovalService approvals,
                          ArtifactService artifacts, DecisionService decisions, AuditService audit,
                          SafeStopService safeStop, MetricsRecorder metrics, JsonSupport json) {
        this.store = store;
        this.resolver = resolver;
        this.conditions = conditions;
        this.scheduler = scheduler;
        this.executor = executor;
        this.approvals = approvals;
        this.artifacts = artifacts;
        this.decisions = decisions;
        this.audit = audit;
        this.safeStop = safeStop;
        this.metrics = metrics;
        this.json = json;
    }

    /** Runs until the workflow completes, waits for approval, fails or is stopped. Returns the resulting status. */
    public WorkflowStatus run(String workflowId) {
        if (!running.add(workflowId)) {
            throw new ConflictException("WORKFLOW_BUSY", "Workflow " + workflowId + " is already executing");
        }
        MDC.put("workflowId", workflowId);
        try {
            begin(workflowId);
            return loop(workflowId);
        } finally {
            running.remove(workflowId);
            MDC.remove("workflowId");
        }
    }

    public boolean isRunning(String workflowId) {
        return running.contains(workflowId);
    }

    private void begin(String workflowId) {
        WorkflowStatus current = store.status(workflowId);
        switch (current) {
            case CREATED -> {
                store.transition(workflowId, WorkflowStatus.RUNNING);
                audit.record(workflowId, AuditEventType.WORKFLOW_STARTED, "engine", Map.of());
            }
            case WAITING_FOR_APPROVAL -> store.transition(workflowId, WorkflowStatus.RUNNING);
            case RUNNING, RETRYING -> {
                int reset = store.resetInterruptedTasks(workflowId);
                log.warn("Resuming workflow found in {}; {} interrupted tasks reset", current, reset);
                store.flipStatus(workflowId, WorkflowStatus.RETRYING, WorkflowStatus.RUNNING);
            }
            default -> throw new InvalidWorkflowStateException("Workflow is " + current + " and cannot be executed");
        }
    }

    private WorkflowStatus loop(String workflowId) {
        CompletionService<TaskOutcome> completion = scheduler.newCompletionService();
        int inFlight = 0;
        boolean halted = false;
        while (true) {
            if (!halted) {
                WorkflowStatus s = store.status(workflowId);
                halted = s == WorkflowStatus.SAFE_STOPPED || s == WorkflowStatus.CANCELLED || s == WorkflowStatus.ROLLED_BACK;
            }
            boolean progressed = false;
            if (!halted) {
                WorkflowSnapshot snap = store.snapshot(workflowId);
                WorkflowContext context = snap.context();
                for (TaskSnapshot task : resolver.readyTasks(snap)) {
                    if (!conditions.evaluate(task.condition(), context)) {
                        store.skipTask(workflowId, task.key(), "condition not met: " + task.condition());
                        audit.record(workflowId, AuditEventType.TASK_SKIPPED, "engine",
                                Map.of("task", task.key(), "condition", String.valueOf(task.condition())));
                        progressed = true;
                    } else if (task.nodeType() == NodeType.APPROVAL) {
                        openApprovalGate(snap, task);
                        progressed = true;
                    } else {
                        store.startTask(workflowId, task.key());
                        completion.submit(() -> runTask(workflowId, task, context));
                        inFlight++;
                    }
                }
            }
            if (progressed) {
                continue; // newly satisfied dependencies may unlock more tasks immediately
            }
            if (inFlight == 0) {
                return halted ? store.status(workflowId) : finish(workflowId);
            }
            TaskOutcome outcome;
            try {
                outcome = completion.take().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Workflow engine interrupted", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("Unexpected worker failure", e.getCause());
            }
            inFlight--;
            if (handle(workflowId, outcome, halted)) {
                halted = true;
            }
        }
    }

    private TaskOutcome runTask(String workflowId, TaskSnapshot task, WorkflowContext context) {
        MDC.put("workflowId", workflowId);
        MDC.put("taskKey", task.key());
        try {
            return new TaskOutcome(task, executor.execute(workflowId, task, context), null);
        } catch (TaskExecutionException e) {
            return new TaskOutcome(task, null, e);
        } catch (RuntimeException e) {
            return new TaskOutcome(task, null, new TaskExecutionException(FailureKind.PERMANENT, e, 1, false));
        } finally {
            MDC.remove("taskKey");
        }
    }

    /** Returns true when the workflow must stop scheduling new work. */
    private boolean handle(String workflowId, TaskOutcome outcome, boolean alreadyHalted) {
        TaskSnapshot task = outcome.task();
        if (outcome.success() != null) {
            try {
                persistSuccess(workflowId, task, outcome.success());
                return false;
            } catch (PolicyViolationException e) {
                return fail(workflowId, task, new TaskExecutionException(FailureKind.POLICY, e, outcome.success().attempts(), false), alreadyHalted);
            }
        }
        return fail(workflowId, task, outcome.failure(), alreadyHalted);
    }

    private void persistSuccess(String workflowId, TaskSnapshot task, ExecutionOutcome eo) {
        var result = eo.result();
        for (ArtifactDraft draft : result.artifacts()) {
            artifacts.stage(workflowId, task.key(), draft);
        }
        List<Long> parents = decisions.currentDecisionIds(workflowId, task.dependsOn());
        for (DecisionDraft d : result.decisions()) {
            decisions.record(workflowId, task.key(), task.revision(), d, "agent:" + task.agentId(), parents);
        }
        Map<String, Object> output = new LinkedHashMap<>(result.output());
        output.put("_execution", Map.of("provider", result.provider(), "degraded", result.degraded(),
                "attempts", eo.attempts(), "fallbackUsed", eo.fallbackUsed()));
        if (task.key().equals("requirements")) {
            store.setScenario(workflowId, ScenarioType.valueOf(String.valueOf(output.get("scenario"))));
        }
        store.completeTask(workflowId, task.key(), output, eo.attempts());
        audit.record(workflowId, AuditEventType.TASK_COMPLETED, "agent:" + task.agentId(),
                Map.of("task", task.key(), "attempts", eo.attempts(), "provider", result.provider(), "degraded", result.degraded()));
    }

    private boolean fail(String workflowId, TaskSnapshot task, TaskExecutionException failure, boolean alreadyHalted) {
        String reason = failure.getMessage();
        store.failTask(workflowId, task.key(), failure.kind(), reason, failure.attemptsUsed());
        audit.record(workflowId, AuditEventType.TASK_FAILED, "engine",
                Map.of("task", task.key(), "kind", failure.kind().name(), "reason", String.valueOf(reason),
                        "attempts", failure.attemptsUsed()));
        if (alreadyHalted) {
            return true;
        }
        String msg = "Task '" + task.key() + "' failed: " + reason;
        switch (failure.kind()) {
            case POLICY -> safeStop.safeStop(workflowId,
                    failure.getCause() instanceof SecurityConcernException ? SafeStopCategory.SECURITY_CONCERN : SafeStopCategory.POLICY_VIOLATION,
                    msg, "engine");
            case TRANSIENT -> safeStop.safeStop(workflowId, SafeStopCategory.MAX_RETRIES_EXCEEDED,
                    msg + " (after " + failure.attemptsUsed() + " attempts)", "engine");
            case VALIDATION -> {
                if (failure.critical()) {
                    safeStop.safeStop(workflowId, SafeStopCategory.CRITICAL_VALIDATION, msg, "engine");
                } else {
                    failWorkflow(workflowId, msg);
                }
            }
            default -> failWorkflow(workflowId, msg);
        }
        return true;
    }

    private void failWorkflow(String workflowId, String reason) {
        store.setFailure(workflowId, reason);
        store.transition(workflowId, WorkflowStatus.FAILED);
        audit.record(workflowId, AuditEventType.WORKFLOW_FAILED, "engine", Map.of("reason", reason));
    }

    @SuppressWarnings("unchecked")
    private void openApprovalGate(WorkflowSnapshot snap, TaskSnapshot task) {
        WorkflowContext ctx = snap.context();
        List<ActionType> actions = new ArrayList<>();
        Map<String, Object> payload = new LinkedHashMap<>();
        String description;
        switch (task.key()) {
            case "assumption-approval" -> {
                List<Object> assumptions = new ArrayList<>(ctx.list("requirements.provisionalAssumptions"));
                payload.put("assumptions", assumptions);
                description = "Approve provisional assumptions and answer clarification questions before planning: " + assumptions;
                actions.add(ActionType.MATERIAL_ASSUMPTIONS);
            }
            case "architecture-approval" -> {
                for (Object a : ctx.list("architecture.approvalActions")) {
                    actions.add(ActionType.valueOf(a.toString()));
                }
                description = "Approve high-impact design actions " + actions + " proposed by the architecture stage";
            }
            default -> {
                description = "Final human approval of security review verdict '" + ctx.string("security-review.verdict", "n/a")
                        + "' before release readiness is assessed";
            }
        }
        if (actions.isEmpty()) {
            actions.add(task.approvalType());
        }
        approvals.open(snap.workflowId(), task.key(), actions, description, "engine", json.toJson(payload));
        store.waitTask(snap.workflowId(), task.key());
    }

    private WorkflowStatus finish(String workflowId) {
        WorkflowSnapshot snap = store.snapshot(workflowId);
        if (resolver.allSatisfied(snap)) {
            store.transition(workflowId, WorkflowStatus.COMPLETED);
            artifacts.acceptStaged(workflowId, "engine"); // promote artifacts produced after the final approval gate
            WorkflowExecution w = store.get(workflowId);
            if (w.getStartedAt() != null && w.getCompletedAt() != null) {
                metrics.record(workflowId, MetricsRecorder.E2E_LATENCY_MS,
                        Duration.between(w.getStartedAt(), w.getCompletedAt()).toMillis());
            }
            audit.record(workflowId, AuditEventType.WORKFLOW_COMPLETED, "engine", Map.of());
            return WorkflowStatus.COMPLETED;
        }
        if (snap.tasks().stream().anyMatch(t -> t.status() == TaskStatus.WAITING_APPROVAL)) {
            store.transition(workflowId, WorkflowStatus.WAITING_FOR_APPROVAL);
            return WorkflowStatus.WAITING_FOR_APPROVAL;
        }
        failWorkflow(workflowId, "No runnable tasks remain but the workflow is incomplete (unsatisfied dependencies)");
        return WorkflowStatus.FAILED;
    }
}