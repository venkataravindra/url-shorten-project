package com.example.agentic.orchestration;

import com.example.agentic.domain.ActionType;
import com.example.agentic.domain.NodeType;
import com.example.agentic.domain.TaskStatus;
import com.example.agentic.domain.WorkflowStage;

import java.util.Map;
import java.util.Set;

/** Immutable copy of a persisted task, safe to hand to worker threads. */
public record TaskSnapshot(String key, String title, NodeType nodeType, String agentId, WorkflowStage stage,
                           TaskStatus status, int revision, int attempts, String condition, ActionType approvalType,
                           Map<String, String> params, Set<String> dependsOn) {
}