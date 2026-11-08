package com.example.agentic.dto;

import java.util.Map;

public record SummaryResponse(String workflowId, String status, String scenario, Map<String, Object> summary,
                              Map<String, Object> releaseReadiness) {
}
