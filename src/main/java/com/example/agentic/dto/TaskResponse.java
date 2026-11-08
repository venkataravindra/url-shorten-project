package com.example.agentic.dto;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record TaskResponse(String taskKey, String title, String agentId, String nodeType, String stage, String status,
                           int revision, int attempts, Set<String> dependsOn, String condition, String failureKind,
                           String failureReason, Instant startedAt, Instant completedAt, Map<String, Object> output) {
}
