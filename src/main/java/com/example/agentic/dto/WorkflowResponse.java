package com.example.agentic.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record WorkflowResponse(String id, String title, String requirement, int requirementVersion, String scenario,
                               String status, String safeStopCategory, String safeStopReason, String failureReason,
                               String createdBy, Instant createdAt, Instant startedAt, Instant completedAt,
                               Map<String, Long> taskCounts, List<ApprovalResponse> pendingApprovals) {
}
