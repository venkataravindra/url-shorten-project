package com.example.agentic.metrics;

import java.util.List;
import java.util.Map;

public record WorkflowMetrics(String workflowId, String status, Long endToEndLatencyMs, long activeAgentTimeMs,
                              long agentExecutions, long retryAttempts, long approvalWaitMs, long approvalsDecided,
                              long safeStops, long rollbacks, Map<String, Long> taskCounts, List<TaskMetric> tasks,
                              Map<String, Double> recordedMetrics) {
    public record TaskMetric(String task, String status, int attempts, Long durationMs) {}
}
