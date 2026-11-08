package com.example.agentic.orchestration;

import java.util.List;

public record ReplanCommand(String changedTask, ReplanChangeType changeType, String reason, String newRequirement,
                            List<WorkflowEdge> addDependencies, String requestedBy) {
    public ReplanCommand {
        addDependencies = addDependencies == null ? List.of() : addDependencies;
    }
}
