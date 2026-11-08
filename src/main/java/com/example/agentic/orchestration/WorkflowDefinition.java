package com.example.agentic.orchestration;

import java.util.*;

/** Immutable, validated description of a workflow graph. */
public record WorkflowDefinition(String name, List<WorkflowNode> nodes, List<WorkflowEdge> edges) {

    public WorkflowDefinition {
        Set<String> keys = new HashSet<>();
        for (WorkflowNode n : nodes) {
            if (!keys.add(n.key())) {
                throw new com.example.agentic.exception.ValidationFailureException("Duplicate node key " + n.key());
            }
        }
        new DependencyResolver().topologicalOrder(keys, edges); // validates references and acyclicity
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    public Set<String> dependenciesOf(String key) {
        Set<String> deps = new LinkedHashSet<>();
        edges.forEach(e -> { if (e.to().equals(key)) deps.add(e.from()); });
        return deps;
    }
}