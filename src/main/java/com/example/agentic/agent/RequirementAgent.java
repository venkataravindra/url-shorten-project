package com.example.agentic.agent;

import com.example.agentic.llm.LLMOperation;
import com.example.agentic.llm.LLMResponse;
import com.example.agentic.exception.ValidationFailureException;
import org.springframework.stereotype.Component;

import java.util.*;

/** Stage 1: understands the requirement, surfaces ambiguities and refuses to silently resolve blocking ones. */
@Component
public class RequirementAgent extends AbstractAgent {
    @Override
    public String id() { return "requirement"; }

    @Override
    public AgentResult execute(AgentContext ctx) {
        String normalized = RequirementNormalizer.normalize(ctx.requirement());
        LLMResponse analysis = ctx.call(LLMOperation.ANALYZE_REQUIREMENT, Map.of());
        Map<String, Object> a = analysis.payload();
        require(a, "RequirementAgent", "scenario", "summary", "functionalRequirements", "acceptanceCriteria");
        String scenario = str(a, "scenario");
        if (!List.of("GREENFIELD", "BROWNFIELD", "AMBIGUOUS").contains(scenario)) {
            throw new ValidationFailureException("RequirementAgent: unknown scenario '" + scenario + "'");
        }
        List<Map<String, Object>> criteria = maps(a.get("acceptanceCriteria"));
        requireFields(criteria, "RequirementAgent", "acceptance criterion", "id", "text", "requirementId");

        LLMResponse amb = ctx.call(LLMOperation.IDENTIFY_AMBIGUITIES, Map.of("scenario", scenario));
        require(amb.payload(), "RequirementAgent", "ambiguities");
        List<Map<String, Object>> ambiguities = maps(amb.payload().get("ambiguities"));
        requireFields(ambiguities, "RequirementAgent", "ambiguity", "id", "severity", "question", "provisionalAssumption");

        boolean blocking = ambiguities.stream().anyMatch(x -> "BLOCKING".equals(str(x, "severity")));
        List<Object> questions = new ArrayList<>();
        List<Object> provisional = new ArrayList<>();
        for (Map<String, Object> x : ambiguities) {
            questions.add(Map.of("id", str(x, "id"), "severity", str(x, "severity"), "question", str(x, "question")));
            provisional.add(str(x, "id") + ": " + str(x, "provisionalAssumption"));
        }
        Object extra = a.get("assumptions");
        if (extra instanceof List<?> l) {
            provisional.addAll(l);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", scenario);
        out.put("summary", str(a, "summary"));
        out.put("normalizedRequirement", normalized);
        out.put("functionalRequirements", a.get("functionalRequirements"));
        out.put("nonFunctionalRequirements", a.getOrDefault("nonFunctionalRequirements", List.of()));
        out.put("acceptanceCriteria", criteria);
        out.put("ambiguities", ambiguities);
        out.put("clarificationQuestions", questions);
        out.put("provisionalAssumptions", provisional);
        out.put("missingInformation", a.getOrDefault("missingInformation", List.of()));
        out.put("blockingAmbiguity", blocking);

        StringBuilder md = new StringBuilder("# Requirement analysis\n\n**Scenario:** ").append(scenario)
                .append("\n\n").append(str(a, "summary")).append("\n\n## Functional requirements\n");
        maps(a.get("functionalRequirements")).forEach(r -> md.append("- ").append(str(r, "id")).append(": ").append(str(r, "text")).append('\n'));
        md.append("\n## Acceptance criteria\n");
        criteria.forEach(c -> md.append("- ").append(str(c, "id")).append(" [").append(str(c, "status")).append("]: ").append(str(c, "text")).append('\n'));
        md.append("\n## Ambiguities and clarification questions\n");
        ambiguities.forEach(x -> md.append("- ").append(str(x, "id")).append(" (").append(str(x, "severity")).append("): ")
                .append(str(x, "question")).append("  \n  Provisional assumption: ").append(str(x, "provisionalAssumption")).append('\n'));
        md.append("\n> ").append(analysis.notice()).append('\n');

        List<DecisionDraft> decisions = new ArrayList<>();
        decisions.add(new DecisionDraft("Classified request as " + scenario,
                "Rule-based analysis of the requirement text (simulated understanding)"));
        if (blocking) {
            decisions.add(new DecisionDraft("Hold planning until material assumptions are approved",
                    "Blocking ambiguities exist; provisional assumptions must not become implementation decisions silently"));
        }
        return ctx.result(out, List.of(new ArtifactDraft("requirements-analysis.md", "MARKDOWN", md.toString())), decisions);
    }
}