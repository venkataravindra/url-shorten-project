package com.example.agentic.orchestration;

import com.example.agentic.domain.TaskStatus;
import com.example.agentic.exception.CyclicDependencyException;
import com.example.agentic.exception.ValidationFailureException;
import org.springframework.stereotype.Component;

import java.util.*;

/** Pure graph logic: ordering, cycle detection, parallel levels, impact analysis and readiness. */
@Component
public class DependencyResolver {

    /** Kahn's algorithm. Throws CyclicDependencyException naming the nodes that could not be ordered. */
    public List<String> topologicalOrder(Collection<String> nodes, Collection<WorkflowEdge> edges) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> out = new HashMap<>();
        nodes.forEach(n -> indegree.put(n, 0));
        for (WorkflowEdge e : edges) {
            if (!indegree.containsKey(e.from()) || !indegree.containsKey(e.to())) {
                throw new ValidationFailureException("Dependency references unknown task: " + e.from() + " -> " + e.to());
            }
            if (e.from().equals(e.to())) {
                throw new CyclicDependencyException("Task depends on itself: " + e.from());
            }
            out.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
            indegree.merge(e.to(), 1, Integer::sum);
        }
        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((k, v) -> { if (v == 0) queue.add(k); });
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String n = queue.poll();
            order.add(n);
            for (String next : out.getOrDefault(n, List.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }
        if (order.size() != indegree.size()) {
            List<String> stuck = new ArrayList<>(indegree.keySet());
            stuck.removeAll(order);
            throw new CyclicDependencyException("Dependency cycle detected among: " + stuck);
        }
        return order;
    }

    /** Groups of nodes that can run in parallel: level n depends only on levels < n. */
    public List<List<String>> parallelLevels(Collection<String> nodes, Collection<WorkflowEdge> edges) {
        List<String> order = topologicalOrder(nodes, edges);
        Map<String, Integer> level = new HashMap<>();
        for (String n : order) {
            int l = 0;
            for (WorkflowEdge e : edges) {
                if (e.to().equals(n)) {
                    l = Math.max(l, level.get(e.from()) + 1);
                }
            }
            level.put(n, l);
        }
        int max = level.values().stream().mapToInt(Integer::intValue).max().orElse(-1);
        List<List<String>> levels = new ArrayList<>();
        for (int i = 0; i <= max; i++) {
            int current = i;
            levels.add(order.stream().filter(n -> level.get(n) == current).toList());
        }
        return levels;
    }

    /** All transitive dependents of a node (excluding the node itself). */
    public Set<String> descendants(String key, Collection<WorkflowEdge> edges) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(List.of(key));
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            for (WorkflowEdge e : edges) {
                if (e.from().equals(cur) && seen.add(e.to())) {
                    stack.push(e.to());
                }
            }
        }
        return seen;
    }

    /** Tasks that are runnable and whose every dependency is satisfied (COMPLETED or SKIPPED). */
    public List<TaskSnapshot> readyTasks(WorkflowSnapshot snapshot) {
        Map<String, TaskSnapshot> byKey = new HashMap<>();
        snapshot.tasks().forEach(t -> byKey.put(t.key(), t));
        return snapshot.tasks().stream()
                .filter(t -> t.status().isRunnable())
                .filter(t -> t.dependsOn().stream().allMatch(d -> byKey.get(d) != null && byKey.get(d).status().isSatisfied()))
                .toList();
    }

    public boolean allSatisfied(WorkflowSnapshot snapshot) {
        return snapshot.tasks().stream().allMatch(t -> t.status() == TaskStatus.COMPLETED || t.status() == TaskStatus.SKIPPED);
    }
}