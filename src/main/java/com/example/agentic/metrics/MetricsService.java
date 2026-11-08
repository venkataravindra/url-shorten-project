package com.example.agentic.metrics;

import com.example.agentic.audit.AuditEvent;
import com.example.agentic.audit.AuditService;
import com.example.agentic.domain.*;
import com.example.agentic.orchestration.WorkflowStateStore;
import com.example.agentic.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Every figure is derived from persisted rows (workflows, tasks, agent executions, approvals, audit log). */
@Service
@Transactional(readOnly = true)
public class MetricsService {
    private final WorkflowExecutionRepository workflows;
    private final WorkflowTaskRepository tasks;
    private final AgentExecutionRepository attempts;
    private final ApprovalRequestRepository approvals;
    private final AuditLogRepository auditLogs;
    private final WorkflowMetricRepository metricRows;
    private final AuditService audit;
    private final WorkflowStateStore store;

    public MetricsService(WorkflowExecutionRepository workflows, WorkflowTaskRepository tasks, AgentExecutionRepository attempts,
                          ApprovalRequestRepository approvals, AuditLogRepository auditLogs,
                          WorkflowMetricRepository metricRows, AuditService audit, WorkflowStateStore store) {
        this.workflows = workflows;
        this.tasks = tasks;
        this.attempts = attempts;
        this.approvals = approvals;
        this.auditLogs = auditLogs;
        this.metricRows = metricRows;
        this.audit = audit;
        this.store = store;
    }

    public MetricsSummary summary() {
        Map<String, Long> byStatus = new TreeMap<>();
        workflows.countGroupedByStatus().forEach(r -> byStatus.put(r[0].toString(), (Long) r[1]));
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long completed = byStatus.getOrDefault("COMPLETED", 0L);
        long unsuccessful = byStatus.getOrDefault("FAILED", 0L) + byStatus.getOrDefault("ROLLED_BACK", 0L)
                + byStatus.getOrDefault("SAFE_STOPPED", 0L);
        long settled = completed + unsuccessful;

        Map<String, Long> taskStatus = new HashMap<>();
        tasks.countGroupedByStatus().forEach(r -> taskStatus.put(r[0].toString(), (Long) r[1]));
        long taskDone = taskStatus.getOrDefault("COMPLETED", 0L);
        long taskFailed = taskStatus.getOrDefault("FAILED", 0L);

        long rollbacks = auditLogs.countByEventType("ROLLED_BACK");
        long safeStops = auditLogs.countByEventType("SAFE_STOPPED");
        List<ApprovalRequest> decided = decidedApprovals();
        List<MetricsSummary.AgentStat> agents = attempts.durationByAgent().stream()
                .map(r -> new MetricsSummary.AgentStat(String.valueOf(r[0]), (Long) r[1], ((Number) r[2]).doubleValue())).toList();
        double[] mttr = mttr();
        return new MetricsSummary(total, byStatus, ratio(completed, settled), ratio(unsuccessful, settled),
                attempts.countRetryAttempts(), rollbacks, ratio(rollbacks, total), safeStops, ratio(safeStops, total),
                metricRows.average(MetricsRecorder.E2E_LATENCY_MS), averageWait(decided), decided.size(),
                ratio(taskDone, taskDone + taskFailed), agents, mttr[1] == 0 ? null : mttr[0] / mttr[1], (int) mttr[1], DEFINITIONS);
    }

    public WorkflowMetrics forWorkflow(String id) {
        WorkflowExecution w = store.get(id);
        List<WorkflowTask> ts = tasks.findByWorkflowIdOrderByIdAsc(id);
        List<AgentExecution> runs = attempts.findByWorkflowIdOrderByIdAsc(id);
        List<ApprovalRequest> decided = approvals.findByWorkflowIdOrderByIdAsc(id).stream()
                .filter(a -> a.getDecidedAt() != null && (a.getStatus() == ApprovalStatus.APPROVED || a.getStatus() == ApprovalStatus.REJECTED)).toList();
        List<AuditEvent> events = audit.forWorkflow(id);
        Map<String, Long> counts = new TreeMap<>();
        ts.forEach(t -> counts.merge(t.getStatus().name(), 1L, Long::sum));
        Map<String, Double> recorded = new LinkedHashMap<>();
        metricRows.findByWorkflowIdOrderByIdAsc(id).forEach(m -> recorded.put(m.getName(), m.getMetricValue()));
        Long e2e = w.getStartedAt() != null && w.getCompletedAt() != null
                ? Duration.between(w.getStartedAt(), w.getCompletedAt()).toMillis() : null;
        return new WorkflowMetrics(id, w.getStatus().name(), e2e, runs.stream().mapToLong(AgentExecution::getDurationMs).sum(),
                runs.size(), runs.stream().filter(r -> r.getAttempt() > 1).count(),
                decided.stream().mapToLong(a -> Duration.between(a.getRequestedAt(), a.getDecidedAt()).toMillis()).sum(), decided.size(),
                events.stream().filter(e -> e.eventType().equals("SAFE_STOPPED")).count(),
                events.stream().filter(e -> e.eventType().equals("ROLLED_BACK")).count(), counts,
                ts.stream().map(t -> new WorkflowMetrics.TaskMetric(t.getNodeKey(), t.getStatus().name(), t.getAttempts(),
                        t.getStartedAt() != null && t.getCompletedAt() != null
                                ? Duration.between(t.getStartedAt(), t.getCompletedAt()).toMillis() : null)).toList(),
                recorded);
    }

    private List<ApprovalRequest> decidedApprovals() {
        return approvals.findByStatusNot(ApprovalStatus.PENDING).stream()
                .filter(a -> a.getDecidedAt() != null && (a.getStatus() == ApprovalStatus.APPROVED || a.getStatus() == ApprovalStatus.REJECTED)).toList();
    }

    private static Double averageWait(List<ApprovalRequest> decided) {
        return decided.isEmpty() ? null : decided.stream()
                .mapToLong(a -> Duration.between(a.getRequestedAt(), a.getDecidedAt()).toMillis()).average().orElse(0);
    }

    /** Returns {sumMillis, samples}: time from first failure/safe-stop of a workflow to its next completion. */
    private double[] mttr() {
        Map<String, Instant> failureStart = new HashMap<>();
        double sum = 0;
        int n = 0;
        for (AuditLog a : auditLogs.findByEventTypeInOrderByIdAsc(List.of("SAFE_STOPPED", "WORKFLOW_FAILED", "WORKFLOW_COMPLETED"))) {
            if (a.getWorkflowId() == null) {
                continue;
            }
            if (a.getEventType().equals("WORKFLOW_COMPLETED")) {
                Instant start = failureStart.remove(a.getWorkflowId());
                if (start != null) {
                    sum += Duration.between(start, a.getOccurredAt()).toMillis();
                    n++;
                }
            } else {
                failureStart.putIfAbsent(a.getWorkflowId(), a.getOccurredAt());
            }
        }
        return new double[]{sum, n};
    }

    private static double ratio(long a, long b) {
        return b == 0 ? 0.0 : Math.round(10000.0 * a / b) / 10000.0;
    }

    private static final List<String> DEFINITIONS = List.of(
            "successRate = COMPLETED / (COMPLETED + FAILED + ROLLED_BACK + SAFE_STOPPED); CANCELLED and in-progress workflows are excluded",
            "failureRate = (FAILED + ROLLED_BACK + SAFE_STOPPED) / the same denominator",
            "retryCount = persisted agent executions with attempt > 1 (includes the fallback attempt)",
            "taskSuccessRate = COMPLETED tasks / (COMPLETED + FAILED tasks); skipped and pending tasks excluded",
            "approval wait = decidedAt - requestedAt for APPROVED/REJECTED approvals; includes human think time",
            "MTTR = mean time from a workflow's first SAFE_STOPPED/FAILED audit event to its next WORKFLOW_COMPLETED event; "
                    + "only workflows that recovered are sampled, and it includes the time a human took to intervene");
}