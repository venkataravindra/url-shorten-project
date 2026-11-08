package com.example.agentic.orchestration;

import com.example.agentic.domain.*;
import com.example.agentic.exception.InvalidWorkflowStateException;
import com.example.agentic.exception.ResourceNotFoundException;
import com.example.agentic.governance.SecretRedactor;
import com.example.agentic.repository.*;
import com.example.agentic.service.JsonSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Persistence of workflow state. Every state change is a small transaction, so progress survives a crash. */
@Service
public class WorkflowStateStore {
    private final WorkflowExecutionRepository workflows;
    private final WorkflowTaskRepository tasks;
    private final WorkflowDependencyRepository dependencies;
    private final AgentExecutionRepository attempts;
    private final JsonSupport json;
    private final Clock clock;

    public WorkflowStateStore(WorkflowExecutionRepository workflows, WorkflowTaskRepository tasks,
                              WorkflowDependencyRepository dependencies, AgentExecutionRepository attempts,
                              JsonSupport json, Clock clock) {
        this.workflows = workflows;
        this.tasks = tasks;
        this.dependencies = dependencies;
        this.attempts = attempts;
        this.json = json;
        this.clock = clock;
    }

    private Instant now() { return Instant.now(clock); }

    @Transactional
    public WorkflowExecution create(String requirement, String title, String createdBy, WorkflowDefinition def) {
        WorkflowExecution w = new WorkflowExecution();
        w.setId(UUID.randomUUID().toString());
        w.setTitle(title == null || title.isBlank() ? def.name() : title);
        w.setRequirement(requirement);
        w.setCreatedBy(createdBy);
        w.setCreatedAt(now());
        w.setUpdatedAt(now());
        workflows.save(w);
        for (WorkflowNode n : def.nodes()) {
            WorkflowTask t = new WorkflowTask();
            t.setWorkflow(w);
            t.setNodeKey(n.key());
            t.setTitle(n.title());
            t.setAgentId(n.agentId());
            t.setNodeType(n.type());
            t.setStage(n.stage());
            t.setConditionExpr(n.condition());
            t.setApprovalType(n.approvalType());
            t.setParamsJson(json.toJson(n.params()));
            t.setUpdatedAt(now());
            tasks.save(t);
        }
        for (WorkflowEdge e : def.edges()) {
            addDependency(w, e.to(), e.from(), 1);
        }
        return w;
    }

    @Transactional
    public void addDependency(WorkflowExecution w, String taskKey, String dependsOn, int revision) {
        WorkflowDependency d = new WorkflowDependency();
        d.setWorkflow(w);
        d.setTaskKey(taskKey);
        d.setDependsOnKey(dependsOn);
        d.setAddedInRevision(revision);
        dependencies.save(d);
    }

    @Transactional(readOnly = true)
    public WorkflowExecution get(String id) {
        return workflows.findById(id).orElseThrow(() -> new ResourceNotFoundException("Workflow " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public WorkflowStatus status(String id) { return get(id).getStatus(); }

    @Transactional(readOnly = true)
    public List<WorkflowTask> tasks(String id) { return tasks.findByWorkflowIdOrderByIdAsc(id); }

    @Transactional(readOnly = true)
    public WorkflowSnapshot snapshot(String id) {
        WorkflowExecution w = get(id);
        Map<String, Set<String>> deps = new HashMap<>();
        dependencies.findByWorkflowId(id).forEach(d -> deps.computeIfAbsent(d.getTaskKey(), k -> new LinkedHashSet<>()).add(d.getDependsOnKey()));
        List<TaskSnapshot> snaps = new ArrayList<>();
        Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();
        for (WorkflowTask t : tasks.findByWorkflowIdOrderByIdAsc(id)) {
            snaps.add(new TaskSnapshot(t.getNodeKey(), t.getTitle(), t.getNodeType(), t.getAgentId(), t.getStage(),
                    t.getStatus(), t.getRevision(), t.getAttempts(), t.getConditionExpr(), t.getApprovalType(),
                    json.toStringMap(t.getParamsJson()), deps.getOrDefault(t.getNodeKey(), Set.of())));
            if (t.getStatus() == TaskStatus.COMPLETED && t.getOutputJson() != null) {
                outputs.put(t.getNodeKey(), json.toMap(t.getOutputJson()));
            }
        }
        return new WorkflowSnapshot(id, w.getRequirement(), w.getScenarioType(), w.getStatus(), snaps, outputs);
    }

    @Transactional(readOnly = true)
    public List<WorkflowDependency> dependencies(String id) { return dependencies.findByWorkflowId(id); }

    // ------------------------------------------------------------------ workflow level
    /** Validated compare-and-set transition; returns false if the workflow is already in the target state. */
    @Transactional
    public boolean transition(String id, WorkflowStatus to) {
        for (int i = 0; i < 3; i++) {
            WorkflowStatus from = get(id).getStatus();
            if (from == to) {
                return false;
            }
            if (!from.canTransitionTo(to)) {
                throw new InvalidWorkflowStateException("Illegal workflow transition " + from + " -> " + to);
            }
            if (workflows.compareAndSetStatus(id, from, to, now()) == 1) {
                WorkflowExecution w = get(id);
                if (to == WorkflowStatus.RUNNING && w.getStartedAt() == null) {
                    w.setStartedAt(now());
                }
                if (to == WorkflowStatus.COMPLETED || to == WorkflowStatus.FAILED || to == WorkflowStatus.ROLLED_BACK) {
                    w.setCompletedAt(now());
                } else if (to == WorkflowStatus.CREATED) {
                    w.setCompletedAt(null);
                }
                return true;
            }
        }
        throw new InvalidWorkflowStateException("Concurrent modification of workflow " + id);
    }

    /** Best-effort CAS used for RUNNING <-> RETRYING flips from worker threads. */
    @Transactional
    public void flipStatus(String id, WorkflowStatus from, WorkflowStatus to) {
        workflows.compareAndSetStatus(id, from, to, now());
    }

    @Transactional
    public void setScenario(String id, ScenarioType scenario) {
        get(id).setScenarioType(scenario);
    }

    @Transactional
    public void setSafeStop(String id, SafeStopCategory category, String reason) {
        WorkflowExecution w = get(id);
        w.setSafeStopCategory(category);
        w.setSafeStopReason(SecretRedactor.redact(reason));
    }

    @Transactional
    public void clearSafeStop(String id) {
        WorkflowExecution w = get(id);
        w.setSafeStopCategory(null);
        w.setSafeStopReason(null);
        w.setResumeCount(w.getResumeCount() + 1);
    }

    @Transactional
    public void setFailure(String id, String reason) {
        get(id).setFailureReason(SecretRedactor.redact(reason));
    }

    @Transactional
    public void setRequirement(String id, String requirement) {
        WorkflowExecution w = get(id);
        w.setRequirement(requirement);
        w.setRequirementVersion(w.getRequirementVersion() + 1);
    }

    // ------------------------------------------------------------------ task level
    private WorkflowTask task(String id, String key) {
        return tasks.findByWorkflowIdAndNodeKey(id, key)
                .orElseThrow(() -> new ResourceNotFoundException("Task " + key + " not found in workflow " + id));
    }

    @Transactional
    public void startTask(String id, String key) {
        WorkflowTask t = task(id, key);
        t.setStatus(TaskStatus.RUNNING);
        if (t.getStartedAt() == null) t.setStartedAt(now());
        t.setFailureReason(null);
        t.setFailureKind(null);
        t.setUpdatedAt(now());
    }

    @Transactional
    public void markTaskRetrying(String id, String key, int attempt, String reason) {
        WorkflowTask t = task(id, key);
        t.setStatus(TaskStatus.RETRYING);
        t.setAttempts(attempt);
        t.setFailureReason(SecretRedactor.redact(reason));
        t.setUpdatedAt(now());
        workflows.compareAndSetStatus(id, WorkflowStatus.RUNNING, WorkflowStatus.RETRYING, now());
    }

    @Transactional
    public void markTaskRunningAfterRetry(String id, String key) {
        WorkflowTask t = task(id, key);
        t.setStatus(TaskStatus.RUNNING);
        t.setUpdatedAt(now());
        workflows.compareAndSetStatus(id, WorkflowStatus.RETRYING, WorkflowStatus.RUNNING, now());
    }

    @Transactional
    public void completeTask(String id, String key, Map<String, Object> output, int attemptsUsed) {
        WorkflowTask t = task(id, key);
        t.setStatus(TaskStatus.COMPLETED);
        t.setOutputJson(json.toJson(output));
        t.setAttempts(attemptsUsed);
        t.setCompletedAt(now());
        t.setFailureReason(null);
        t.setFailureKind(null);
        t.setUpdatedAt(now());
    }

    @Transactional
    public void skipTask(String id, String key, String reason) {
        WorkflowTask t = task(id, key);
        t.setStatus(TaskStatus.SKIPPED);
        t.setOutputJson(json.toJson(Map.of("skipped", true, "reason", reason)));
        t.setCompletedAt(now());
        t.setUpdatedAt(now());
    }

    @Transactional
    public void failTask(String id, String key, FailureKind kind, String reason, int attemptsUsed) {
        WorkflowTask t = task(id, key);
        t.setStatus(TaskStatus.FAILED);
        t.setFailureKind(kind);
        t.setFailureReason(SecretRedactor.redact(reason));
        t.setAttempts(attemptsUsed);
        t.setUpdatedAt(now());
    }

    @Transactional
    public void waitTask(String id, String key) {
        WorkflowTask t = task(id, key);
        t.setStatus(TaskStatus.WAITING_APPROVAL);
        if (t.getStartedAt() == null) t.setStartedAt(now());
        t.setUpdatedAt(now());
    }

    /** Used on resume and on restart recovery: interrupted or failed work is re-run (agents are idempotent). */
    @Transactional
    public int resetInterruptedTasks(String id) {
        int n = 0;
        for (WorkflowTask t : tasks.findByWorkflowIdOrderByIdAsc(id)) {
            if (t.getStatus() == TaskStatus.RUNNING || t.getStatus() == TaskStatus.RETRYING || t.getStatus() == TaskStatus.FAILED) {
                t.setStatus(TaskStatus.PENDING);
                t.setAttempts(0);
                t.setFailureReason(null);
                t.setFailureKind(null);
                t.setUpdatedAt(now());
                n++;
            }
        }
        return n;
    }

    @Transactional
    public void recordAttempt(String id, String key, String agentId, int attempt, int revision, AttemptStatus status,
                              String provider, boolean degraded, FailureKind kind, String error, Instant startedAt,
                              long durationMs) {
        AgentExecution a = new AgentExecution();
        a.setWorkflow(workflows.getReferenceById(id));
        a.setTaskKey(key);
        a.setAgentId(agentId);
        a.setAttempt(attempt);
        a.setTaskRevision(revision);
        a.setStatus(status);
        a.setProvider(provider);
        a.setDegraded(degraded);
        a.setFailureKind(kind);
        a.setErrorMessage(SecretRedactor.redact(error));
        a.setStartedAt(startedAt);
        a.setDurationMs(durationMs);
        attempts.save(a);
    }
}