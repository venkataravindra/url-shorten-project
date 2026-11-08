package com.example.agentic.audit;

import java.time.Instant;
import java.util.Map;

public record AuditEvent(Long id, String workflowId, String eventType, String actor, Map<String, Object> detail,
                         Instant occurredAt) {
}