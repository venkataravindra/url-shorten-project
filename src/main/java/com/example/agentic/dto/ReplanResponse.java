package com.example.agentic.dto;

import java.util.List;

public record ReplanResponse(boolean applied, boolean approvalRequired, Long approvalId, boolean highImpact,
                             String changedTask, String changeType, List<String> impactedTasks,
                             List<String> preservedTasks, List<EdgeDto> revisedDependencies, String workflowStatus) {
}
