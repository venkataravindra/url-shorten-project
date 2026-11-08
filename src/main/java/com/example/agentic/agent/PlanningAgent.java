package com.example.agentic.agent;

import com.example.agentic.exception.ValidationFailureException;
import com.example.agentic.llm.LLMOperation;
import com.example.agentic.llm.LLMResponse;
import com.example.agentic.orchestration.DependencyResolver;
import com.example.agentic.orchestration.WorkflowEdge;
import org.springframework.stereotype.Component;

import java.util.*;

/** Stage 2: decomposes the work into engineering tasks and builds + validates the task dependency graph. */
@Component
public class PlanningAgent extends AbstractAgent {
    private final DependencyResolver resolver;

    public PlanningAgent(DependencyResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public String id() { return "planning"; }

    @Override
    public AgentResult execute(AgentContext ctx) {
        String scenario = ctx.workflow().string("requirements.scenario", "GREENFIELD");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("scenario", scenario);
        request.put("approvedAssumptions", ctx.workflow().list("assumption-approval.approvedAssumptions"));
        LLMResponse r = ctx.call(LLMOperation.DECOMPOSE_TASKS, request);
        require(r.payload(), "PlanningAgent", "tasks");
        List<Map<String, Object>> tasks = maps(r.payload().get("tasks"));
        requireFields(tasks, "PlanningAgent", "task", "id", "title", "estimateHours");

        Map<String, Integer> hours = new LinkedHashMap<>();
        for (Map<String, Object> t : tasks) {
            int h = ((Number) t.get("estimateHours")).intValue();
            if (h <= 0 || hours.put(str(t, "id"), h) != null) {
                throw new ValidationFailureException("PlanningAgent: invalid or duplicate task " + str(t, "id"));
            }
        }
        List<WorkflowEdge> edges = new ArrayList<>();
        for (Map<String, Object> t : tasks) {
            Object deps = t.get("dependsOn");
            if (deps instanceof List<?> l) {
                l.forEach(d -> edges.add(new WorkflowEdge(d.toString(), str(t, "id"))));
            }
        }
        List<String> order = resolver.topologicalOrder(hours.keySet(), edges);
        List<List<String>> levels = resolver.parallelLevels(hours.keySet(), edges);

        // critical path = longest chain by estimated hours
        Map<String, Integer> longest = new HashMap<>();
        Map<String, String> via = new HashMap<>();
        for (String id : order) {
            int best = 0;
            String from = null;
            for (WorkflowEdge e : edges) {
                if (e.to().equals(id) && longest.get(e.from()) > best) {
                    best = longest.get(e.from());
                    from = e.from();
                }
            }
            longest.put(id, best + hours.get(id));
            via.put(id, from);
        }
        String end = Collections.max(longest.entrySet(), Map.Entry.comparingByValue()).getKey();
        LinkedList<String> critical = new LinkedList<>();
        for (String cur = end; cur != null; cur = via.get(cur)) {
            critical.addFirst(cur);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tasks", tasks);
        out.put("taskGraph", edges.stream().map(e -> Map.of("from", e.from(), "to", e.to())).toList());
        out.put("topologicalOrder", order);
        out.put("parallelGroups", levels);
        out.put("criticalPath", critical);
        out.put("criticalPathHours", longest.get(end));
        out.put("taskCount", tasks.size());
        out.put("assumptionsApplied", ctx.workflow().list("assumption-approval.approvedAssumptions"));

        StringBuilder md = new StringBuilder("# Task plan\n\n| Task | Title | Hours | Depends on |\n|---|---|---|---|\n");
        tasks.forEach(t -> md.append("| ").append(str(t, "id")).append(" | ").append(str(t, "title")).append(" | ")
                .append(str(t, "estimateHours")).append(" | ").append(t.getOrDefault("dependsOn", List.of())).append(" |\n"));
        md.append("\nParallel groups: ").append(levels).append("\n\nCritical path: ").append(critical)
                .append(" (").append(longest.get(end)).append("h)\n");
        return ctx.result(out, List.of(new ArtifactDraft("task-plan.md", "MARKDOWN", md.toString())),
                List.of(new DecisionDraft("Decomposed into " + tasks.size() + " tasks; critical path " + longest.get(end) + "h",
                        "Dependency graph validated acyclic; " + levels.size() + " parallel levels")));
    }
}