package com.example.agentic.orchestration;

import com.example.agentic.domain.ActionType;
import com.example.agentic.domain.NodeType;
import com.example.agentic.domain.WorkflowStage;

import java.util.Map;

/** A vertex of the workflow DAG: either an agent invocation or a human approval gate. */
public record WorkflowNode(String key, String title, NodeType type, String agentId, WorkflowStage stage,
                           String condition, ActionType approvalType, Map<String, String> params) {

    public static WorkflowNode agent(String key, String title, String agentId, WorkflowStage stage, String condition,
                                     Map<String, String> params) {
        return new WorkflowNode(key, title, NodeType.AGENT, agentId, stage, condition, null, params);
    }

    public static WorkflowNode approval(String key, String title, WorkflowStage stage, String condition, ActionType type) {
        return new WorkflowNode(key, title, NodeType.APPROVAL, null, stage, condition, type, Map.of());
    }
}