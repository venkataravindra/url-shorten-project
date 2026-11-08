package com.example.agentic.orchestration;

import com.example.agentic.domain.WorkflowStatus;

import java.util.List;

/** What a replan would do. Persisted with the approval request so approval applies exactly what was reviewed. */
public record ReplanProposal(String workflowId, String changedTask, ReplanChangeType changeType, String reason,
                             String newRequirement, List<String> impactedTasks, List<String> preservedTasks,
                             List<WorkflowEdge> addedEdges, List<WorkflowEdge> revisedEdges, boolean highImpact,
                             WorkflowStatus previousStatus) {
}
