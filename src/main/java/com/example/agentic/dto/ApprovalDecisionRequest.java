package com.example.agentic.dto;

import jakarta.validation.constraints.Size;

public record ApprovalDecisionRequest(Long approvalId, @Size(max = 1000) String comment) {
}
