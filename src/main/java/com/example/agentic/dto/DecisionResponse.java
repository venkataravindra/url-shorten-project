package com.example.agentic.dto;

import java.time.Instant;
import java.util.List;

public record DecisionResponse(Long id, String taskKey, int taskRevision, String title, String rationale,
                               List<Long> parentDecisionIds, boolean superseded, String supersededReason,
                               String decidedBy, Instant decidedAt) {
}
