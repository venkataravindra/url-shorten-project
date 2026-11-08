package com.example.agentic.agent;

import com.example.agentic.codebase.CodebaseAnalyzer;
import com.example.agentic.domain.ActionType;
import com.example.agentic.exception.ValidationFailureException;
import com.example.agentic.governance.PolicyEngine;
import com.example.agentic.llm.LLMOperation;
import com.example.agentic.llm.LLMResponse;
import org.springframework.stereotype.Component;

import java.util.*;

/** Stage 3: architecture and design. For brownfield work it first analyses the real existing module. */
@Component
public class ArchitectureAgent extends AbstractAgent {
    private final CodebaseAnalyzer analyzer;
    private final PolicyEngine policy;

    public ArchitectureAgent(CodebaseAnalyzer analyzer, PolicyEngine policy) {
        this.analyzer = analyzer;
        this.policy = policy;
    }

    @Override
    public String id() { return "architecture"; }

    @Override
    public AgentResult execute(AgentContext ctx) {
        String scenario = ctx.workflow().string("requirements.scenario", "GREENFIELD");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("scenario", scenario);
        request.put("approvedAssumptions", ctx.workflow().list("assumption-approval.approvedAssumptions"));
        Map<String, Object> baseline = null;
        if (scenario.equals("BROWNFIELD")) {
            baseline = analyzer.analyzeUrlShortenerModule();
            request.put("baseline", baseline);
        }
        LLMResponse r = ctx.call(LLMOperation.RECOMMEND_ARCHITECTURE, request);
        Map<String, Object> p = r.payload();
        require(p, "ArchitectureAgent", "style", "components", "decisions");
        if (scenario.equals("BROWNFIELD") && !(p.get("changeImpact") instanceof Map<?, ?>)) {
            throw new ValidationFailureException("ArchitectureAgent: brownfield output must contain changeImpact");
        }
        List<String> approvalActions = new ArrayList<>();
        Object raw = p.get("approvalActions");
        if (raw instanceof List<?> l) {
            for (Object o : l) {
                try {
                    ActionType a = ActionType.valueOf(o.toString());
                    policy.assertNotForbidden(a);
                    approvalActions.add(a.name());
                } catch (IllegalArgumentException e) {
                    throw new ValidationFailureException("ArchitectureAgent: unknown approval action '" + o + "'");
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("style", p.get("style"));
        out.put("components", p.get("components"));
        out.put("dataModel", p.getOrDefault("dataModel", List.of()));
        out.put("decisions", p.get("decisions"));
        out.put("approvalActions", approvalActions);
        out.put("requiresApproval", !approvalActions.isEmpty());
        if (baseline != null) {
            out.put("baselineSummary", Map.of("source", baseline.get("source"), "entities", baseline.get("entities"),
                    "services", baseline.get("services"), "repositories", baseline.get("repositories"),
                    "schedulerPresent", baseline.get("schedulerPresent"), "testsDiscoverable", baseline.get("testsDiscoverable")));
            out.put("changeImpact", p.get("changeImpact"));
        }

        StringBuilder md = new StringBuilder("# Architecture\n\n").append(p.get("style")).append("\n\n## Components\n");
        maps(p.get("components")).forEach(c -> md.append("- **").append(str(c, "name")).append("**: ").append(str(c, "responsibility")).append('\n'));
        md.append("\n## Decisions\n");
        maps(p.get("decisions")).forEach(d -> md.append("- ").append(str(d, "id")).append(" ").append(str(d, "title"))
                .append(" - ").append(str(d, "rationale")).append(" (alternatives: ").append(str(d, "alternatives")).append(")\n"));
        List<ArtifactDraft> artifacts = new ArrayList<>(List.of(new ArtifactDraft("architecture.md", "MARKDOWN", md.toString())));
        if (baseline != null) {
            artifacts.add(new ArtifactDraft("change-impact-analysis.md", "MARKDOWN", impactMarkdown(p, baseline)));
        }
        List<DecisionDraft> decisions = new ArrayList<>();
        maps(p.get("decisions")).forEach(d -> decisions.add(new DecisionDraft(str(d, "title"), str(d, "rationale"))));
        return ctx.result(out, artifacts, decisions);
    }

    @SuppressWarnings("unchecked")
    private static String impactMarkdown(Map<String, Object> p, Map<String, Object> baseline) {
        Map<String, Object> impact = (Map<String, Object>) p.get("changeImpact");
        StringBuilder md = new StringBuilder("# Change impact analysis\n\nBaseline: ").append(baseline.get("source")).append("\n\n");
        for (String section : List.of("impactedApis", "impactedEntities", "impactedServices", "impactedRepositories")) {
            md.append("## ").append(section).append('\n');
            maps(impact.get(section)).forEach(i -> md.append("- ").append(i).append('\n'));
        }
        md.append("\n## Existing test coverage\n").append(impact.get("existingTestCoverage"))
                .append("\n\n## Regression risks\n");
        ((List<Object>) impact.getOrDefault("regressionRisks", List.of())).forEach(r -> md.append("- ").append(r).append('\n'));
        md.append("\nBackward compatible: ").append(impact.get("backwardCompatible")).append('\n');
        return md.toString();
    }
}