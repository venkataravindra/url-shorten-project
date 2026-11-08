package com.example.agentic.metrics;

import java.util.List;
import java.util.Map;

public record MetricsSummary(long totalWorkflows, Map<String, Long> workflowsByStatus, double workflowSuccessRate,
                             double workflowFailureRate, long retryCount, long rollbackCount, double rollbackFrequency,
                             long safeStopCount, double safeStopFrequency, Double avgEndToEndLatencyMs,
                             Double avgApprovalWaitMs, long approvalsDecided, double taskSuccessRate,
                             List<AgentStat> agentExecutions, Double mttrMs, int mttrSamples, List<String> definitions) {
    public record AgentStat(String agent, long executions, double avgDurationMs) {}
}
