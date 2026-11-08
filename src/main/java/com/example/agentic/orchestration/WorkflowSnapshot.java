package com.example.agentic.orchestration;

import com.example.agentic.domain.ScenarioType;
import com.example.agentic.domain.WorkflowStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record WorkflowSnapshot(String workflowId, String requirement, ScenarioType scenario, WorkflowStatus status,
                               List<TaskSnapshot> tasks, Map<String, Map<String, Object>> outputs) {

    public TaskSnapshot task(String key) {
        return tasks.stream().filter(t -> t.key().equals(key)).findFirst()
                .orElseThrow(() -> new com.example.agentic.exception.ResourceNotFoundException("Unknown task " + key));
    }

    public List<WorkflowEdge> edges() {
        List<WorkflowEdge> edges = new ArrayList<>();
        tasks.forEach(t -> t.dependsOn().forEach(d -> edges.add(new WorkflowEdge(d, t.key()))));
        return edges;
    }

    public WorkflowContext context() {
        return new WorkflowContext(workflowId, requirement, outputs);
    }
}