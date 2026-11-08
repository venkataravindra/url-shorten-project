package com.example.agentic.dto;

import java.time.Instant;
import java.util.List;

public record ApprovalResponse(Long id, String taskKey, String type, List<String> actionTypes, String description,
                               String status, String requestedBy, Instant requestedAt, String decidedBy,
                               Instant decidedAt, String comment) {
}
