package com.example.agentic.agent;

import com.example.agentic.llm.LLMOperation;
import com.example.agentic.llm.LLMResponse;
import org.springframework.stereotype.Component;

import java.util.*;

/** Stage 6. Mode DOCS writes change documentation; mode SUMMARY writes the final engineering summary. */
@Component
public class DocumentationAgent extends AbstractAgent {
    @Override
    public String id() { return "documentation"; }

    @Override
    public AgentResult execute(AgentContext ctx) {
        return "SUMMARY".equals(ctx.params().get("mode")) ? summary(ctx) : docs(ctx);
    }

    private AgentResult docs(AgentContext ctx) {
        String scenario = ctx.workflow().string("requirements.scenario", "GREENFIELD");
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("headline", ctx.workflow().string("requirements.summary", "Engineering change"));
        facts.put("requirements", maps(ctx.workflow().get("requirements.functionalRequirements")).stream()
                .map(r -> str(r, "id") + ": " + str(r, "text")).toList());
        facts.put("plannedFiles", maps(ctx.workflow().get("implementation.plannedFiles")).stream()
                .map(f -> str(f, "action") + " " + str(f, "path")).toList());
        LLMResponse r = ctx.call(LLMOperation.GENERATE_DOCUMENTATION, Map.of("scenario", scenario, "facts", facts));
        require(r.payload(), "DocumentationAgent", "documents");
        List<Map<String, Object>> docs = maps(r.payload().get("documents"));
        requireFields(docs, "DocumentationAgent", "document", "name", "content");
        List<ArtifactDraft> artifacts = docs.stream().map(d -> new ArtifactDraft(str(d, "name"), "MARKDOWN", str(d, "content"))).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documents", docs.stream().map(d -> Map.of("name", d.get("name"), "title", d.getOrDefault("title", d.get("name")))).toList());
        out.put("documentCount", docs.size());
        return ctx.result(out, artifacts, List.of(new DecisionDraft("Generated " + docs.size() + " documents",
                "Documentation derived from the approved requirements and implementation plan")));
    }

    private AgentResult summary(AgentContext ctx) {
        String scenario = ctx.workflow().string("requirements.scenario", "GREENFIELD");
        List<String> approvals = new ArrayList<>();
        for (String gate : List.of("assumption-approval", "architecture-approval", "final-approval")) {
            if (ctx.workflow().flag(gate + ".approved")) {
                approvals.add(gate);
            }
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("readiness", ctx.workflow().string("release-readiness.readiness", "UNKNOWN"));
        facts.put("taskCount", ctx.workflow().get("planning.taskCount"));
        facts.put("criticalPathHours", ctx.workflow().get("planning.criticalPathHours"));
        facts.put("testCaseCount", ctx.workflow().get("test-planning.testCaseCount"));
        facts.put("coverage", ctx.workflow().string("test-planning.coverage.percent", "n/a") + "%");
        facts.put("riskCount", ctx.workflow().list("security-review.risks").size());
        facts.put("verdict", ctx.workflow().string("security-review.verdict", "n/a"));
        facts.put("approvals", approvals.isEmpty() ? "none required" : String.join(", ", approvals));
        LLMResponse r = ctx.call(LLMOperation.SUMMARIZE, Map.of("scenario", scenario, "facts", facts));
        require(r.payload(), "DocumentationAgent", "headline", "sections");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", scenario);
        out.put("headline", r.payload().get("headline"));
        out.put("sections", r.payload().get("sections"));
        out.put("openItems", r.payload().getOrDefault("openItems", List.of()));
        out.put("facts", facts);
        out.put("simulated", r.payload().containsKey("simulationNotice"));
        StringBuilder md = new StringBuilder("# ").append(r.payload().get("headline")).append("\n\n");
        maps(r.payload().get("sections")).forEach(s -> md.append("## ").append(str(s, "title")).append('\n').append(str(s, "body")).append("\n\n"));
        return ctx.result(out, List.of(new ArtifactDraft("engineering-summary.md", "MARKDOWN", md.toString())),
                List.of(new DecisionDraft("Final engineering summary produced", "Aggregates results of all stages")));
    }
}