package com.example.agentic.orchestration;

import com.example.agentic.domain.ActionType;
import com.example.agentic.domain.WorkflowStage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The standard engineering DAG:
 * <pre>
 * requirements -> assumption-approval? -> planning -> architecture -> architecture-approval?
 *   architecture ---------------------------------------> test-planning ----+
 *   architecture-approval -> implementation -------------------------------+-> integration-testing -+
 *   implementation + test-planning -> documentation ------------------------------------------------+-> security-review
 *   security-review -> final-approval -> release-readiness -> engineering-summary
 * </pre>
 * Nodes marked ? are conditional and are SKIPPED when their condition is false.
 */
@Component
public class WorkflowDefinitionFactory {

    public WorkflowDefinition standard() {
        List<WorkflowNode> nodes = List.of(
                WorkflowNode.agent("requirements", "Requirement understanding", "requirement",
                        WorkflowStage.REQUIREMENT_UNDERSTANDING, null, Map.of()),
                WorkflowNode.approval("assumption-approval", "Approve material assumptions",
                        WorkflowStage.REQUIREMENT_UNDERSTANDING, "requirements.blockingAmbiguity == true",
                        ActionType.MATERIAL_ASSUMPTIONS),
                WorkflowNode.agent("planning", "Task decomposition", "planning",
                        WorkflowStage.TASK_DECOMPOSITION, null, Map.of()),
                WorkflowNode.agent("architecture", "Architecture and design", "architecture",
                        WorkflowStage.ARCHITECTURE_AND_DESIGN, null, Map.of()),
                WorkflowNode.approval("architecture-approval", "Approve high-impact design",
                        WorkflowStage.ARCHITECTURE_AND_DESIGN, "architecture.requiresApproval == true",
                        ActionType.HIGH_IMPACT_ARCHITECTURE_CHANGE),
                WorkflowNode.agent("implementation", "Implementation plan", "coding",
                        WorkflowStage.IMPLEMENTATION, null, Map.of()),
                WorkflowNode.agent("test-planning", "Test planning", "testing",
                        WorkflowStage.TESTING_AND_VALIDATION, null, Map.of("mode", "PLAN")),
                WorkflowNode.agent("documentation", "Documentation", "documentation",
                        WorkflowStage.DOCUMENTATION, null, Map.of("mode", "DOCS")),
                WorkflowNode.agent("integration-testing", "Integration testing and validation", "testing",
                        WorkflowStage.TESTING_AND_VALIDATION, null, Map.of("mode", "VALIDATE")),
                WorkflowNode.agent("security-review", "Security and risk review", "review",
                        WorkflowStage.SECURITY_AND_RISK_REVIEW, null, Map.of()),
                WorkflowNode.approval("final-approval", "Human final approval",
                        WorkflowStage.HUMAN_FINAL_APPROVAL, null, ActionType.RELEASE_EXECUTION),
                WorkflowNode.agent("release-readiness", "Release readiness", "release",
                        WorkflowStage.RELEASE_READINESS, null, Map.of()),
                WorkflowNode.agent("engineering-summary", "Engineering summary", "documentation",
                        WorkflowStage.RELEASE_READINESS, null, Map.of("mode", "SUMMARY")));
        List<WorkflowEdge> edges = List.of(
                new WorkflowEdge("requirements", "assumption-approval"),
                new WorkflowEdge("requirements", "planning"),
                new WorkflowEdge("assumption-approval", "planning"),
                new WorkflowEdge("planning", "architecture"),
                new WorkflowEdge("architecture", "architecture-approval"),
                new WorkflowEdge("architecture-approval", "implementation"),
                new WorkflowEdge("architecture", "test-planning"),
                new WorkflowEdge("implementation", "documentation"),
                new WorkflowEdge("test-planning", "documentation"),
                new WorkflowEdge("implementation", "integration-testing"),
                new WorkflowEdge("test-planning", "integration-testing"),
                new WorkflowEdge("integration-testing", "security-review"),
                new WorkflowEdge("documentation", "security-review"),
                new WorkflowEdge("security-review", "final-approval"),
                new WorkflowEdge("final-approval", "release-readiness"),
                new WorkflowEdge("release-readiness", "engineering-summary"));
        return new WorkflowDefinition("standard-engineering-workflow", nodes, edges);
    }
}