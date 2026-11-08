package com.example.agentic.orchestration;

import com.example.agentic.domain.*;
import com.example.agentic.exception.CyclicDependencyException;
import com.example.agentic.exception.ValidationFailureException;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DependencyResolverTest {
    private final DependencyResolver resolver = new DependencyResolver();
    private final WorkflowDefinition standard = new WorkflowDefinitionFactory().standard();
    private final Set<String> keys = new HashSet<>(standard.nodes().stream().map(WorkflowNode::key).toList());

    @Test
    void topologicalOrderRespectsEveryEdge() {
        List<String> order = resolver.topologicalOrder(keys, standard.edges());
        for (WorkflowEdge e : standard.edges()) {
            assertThat(order.indexOf(e.from())).isLessThan(order.indexOf(e.to()));
        }
    }

    @Test
    void architecturePrecedesImplementationAndTestPlanningRunsIndependently() {
        List<List<String>> levels = resolver.parallelLevels(keys, standard.edges());
        int impl = levelOf(levels, "implementation");
        int test = levelOf(levels, "test-planning");
        assertThat(levelOf(levels, "architecture")).isLessThan(impl);
        assertThat(levelOf(levels, "architecture")).isLessThan(test);
        assertThat(test).isLessThan(impl); // implementation also waits for the conditional approval gate
        assertThat(levelOf(levels, "documentation")).isEqualTo(levelOf(levels, "integration-testing"));
    }

    @Test
    void securityReviewIsAJoinOfDocumentationAndIntegrationTesting() {
        assertThat(standard.dependenciesOf("security-review")).containsExactlyInAnyOrder("integration-testing", "documentation");
        assertThat(standard.dependenciesOf("final-approval")).containsExactly("security-review");
        assertThat(standard.dependenciesOf("release-readiness")).containsExactly("final-approval");
    }

    @Test
    void detectsCycles() {
        List<WorkflowEdge> edges = List.of(new WorkflowEdge("a", "b"), new WorkflowEdge("b", "c"), new WorkflowEdge("c", "a"));
        assertThatThrownBy(() -> resolver.topologicalOrder(List.of("a", "b", "c"), edges))
                .isInstanceOf(CyclicDependencyException.class).hasMessageContaining("cycle");
        assertThatThrownBy(() -> resolver.topologicalOrder(List.of("a"), List.of(new WorkflowEdge("a", "a"))))
                .isInstanceOf(CyclicDependencyException.class);
    }

    @Test
    void rejectsUnknownNodesAndDuplicateKeys() {
        assertThatThrownBy(() -> resolver.topologicalOrder(List.of("a"), List.of(new WorkflowEdge("a", "zzz"))))
                .isInstanceOf(ValidationFailureException.class);
        WorkflowNode n = WorkflowNode.agent("x", "x", "requirement", WorkflowStage.REQUIREMENT_UNDERSTANDING, null, Map.of());
        assertThatThrownBy(() -> new WorkflowDefinition("dup", List.of(n, n), List.of())).isInstanceOf(ValidationFailureException.class);
    }

    @Test
    void descendantsAreTheTransitiveDependents() {
        Set<String> d = resolver.descendants("architecture", standard.edges());
        assertThat(d).contains("implementation", "test-planning", "documentation", "integration-testing", "security-review",
                "final-approval", "release-readiness", "engineering-summary");
        assertThat(d).doesNotContain("requirements", "planning", "architecture");
    }

    @Test
    void readyTasksNeedAllDependenciesCompletedOrSkipped() {
        Map<String, TaskStatus> status = new HashMap<>();
        keys.forEach(k -> status.put(k, TaskStatus.PENDING));
        assertThat(ready(status)).containsExactly("requirements");
        status.put("requirements", TaskStatus.COMPLETED);
        assertThat(ready(status)).containsExactly("assumption-approval");
        status.put("assumption-approval", TaskStatus.SKIPPED);
        assertThat(ready(status)).containsExactly("planning");
        status.put("planning", TaskStatus.COMPLETED);
        status.put("architecture", TaskStatus.COMPLETED);
        status.put("architecture-approval", TaskStatus.SKIPPED);
        assertThat(ready(status)).containsExactlyInAnyOrder("implementation", "test-planning");
        status.put("implementation", TaskStatus.RUNNING);
        status.put("test-planning", TaskStatus.COMPLETED);
        assertThat(ready(status)).isEmpty();
        status.put("planning", TaskStatus.STALE);
        assertThat(ready(status)).contains("planning");
    }

    private List<String> ready(Map<String, TaskStatus> status) {
        Map<String, Set<String>> deps = new HashMap<>();
        standard.edges().forEach(e -> deps.computeIfAbsent(e.to(), k -> new HashSet<>()).add(e.from()));
        List<TaskSnapshot> tasks = standard.nodes().stream().map(n -> new TaskSnapshot(n.key(), n.title(), n.type(), n.agentId(),
                n.stage(), status.get(n.key()), 1, 0, n.condition(), n.approvalType(), n.params(),
                deps.getOrDefault(n.key(), Set.of()))).toList();
        WorkflowSnapshot snap = new WorkflowSnapshot("w", "r", ScenarioType.GREENFIELD, WorkflowStatus.RUNNING, tasks, Map.of());
        return resolver.readyTasks(snap).stream().map(TaskSnapshot::key).toList();
    }

    private static int levelOf(List<List<String>> levels, String key) {
        for (int i = 0; i < levels.size(); i++) {
            if (levels.get(i).contains(key)) return i;
        }
        throw new AssertionError(key);
    }
}