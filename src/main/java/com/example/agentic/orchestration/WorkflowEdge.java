package com.example.agentic.orchestration;

/** "to" cannot start until "from" is COMPLETED or SKIPPED. */
public record WorkflowEdge(String from, String to) {
}
