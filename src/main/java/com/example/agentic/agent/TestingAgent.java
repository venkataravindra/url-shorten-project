package com.example.agentic.agent;

import com.example.agentic.exception.ValidationFailureException;
import com.example.agentic.llm.LLMOperation;
import com.example.agentic.llm.LLMResponse;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Stage 5. Mode PLAN generates test cases and a coverage matrix against the acceptance criteria.
 * Mode VALIDATE statically validates the plans against the criteria (traceability, scenario-specific checks).
 * It does not execute tests: this repository's own tests are executed by Maven.
 */
@Component
public class TestingAgent extends AbstractAgent {
    @Override
    public String id() { return "testing"; }

    @Override
    public AgentResult execute(AgentContext ctx) {
        return "VALIDATE".equals(ctx.params().get("mode")) ? validate(ctx) : plan(ctx);
    }

    private AgentResult plan(AgentContext ctx) {
        String scenario = ctx.workflow().string("requirements.scenario", "GREENFIELD");
        List<Object> criteria = ctx.workflow().list("requirements.acceptanceCriteria");
        LLMResponse r = ctx.call(LLMOperation.GENERATE_TEST_CASES, Map.of("scenario", scenario, "acceptanceCriteria", criteria));
        require(r.payload(), "TestingAgent", "testCases", "strategy");
        List<Map<String, Object>> cases = maps(r.payload().get("testCases"));
        requireFields(cases, "TestingAgent", "test case", "id", "type", "name");

        Set<String> covered = new TreeSet<>();
        for (Map<String, Object> c : cases) {
            if (c.get("covers") instanceof List<?> l) {
                l.forEach(x -> covered.add(x.toString()));
            }
        }
        List<String> all = maps(criteria).stream().map(c -> str(c, "id")).toList();
        List<String> uncovered = all.stream().filter(id -> !covered.contains(id)).toList();
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("totalCriteria", all.size());
        coverage.put("coveredCriteria", all.size() - uncovered.size());
        coverage.put("uncovered", uncovered);
        coverage.put("percent", all.isEmpty() ? 0 : Math.round(100.0 * (all.size() - uncovered.size()) / all.size()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategy", r.payload().get("strategy"));
        out.put("testCases", cases);
        out.put("testCaseCount", cases.size());
        out.put("coverage", coverage);

        StringBuilder md = new StringBuilder("# Test plan\n\n").append(r.payload().get("strategy")).append("\n\n| Id | Type | Name | Covers |\n|---|---|---|---|\n");
        cases.forEach(c -> md.append("| ").append(str(c, "id")).append(" | ").append(str(c, "type")).append(" | ")
                .append(str(c, "name")).append(" | ").append(c.getOrDefault("covers", List.of())).append(" |\n"));
        md.append("\nCriteria coverage: ").append(coverage.get("percent")).append("%\n");
        return ctx.result(out, List.of(new ArtifactDraft("test-plan.md", "MARKDOWN", md.toString())),
                List.of(new DecisionDraft("Test plan with " + cases.size() + " cases, " + coverage.get("percent") + "% criteria coverage",
                        "Every acceptance criterion is traced to at least one test case")));
    }

    private AgentResult validate(AgentContext ctx) {
        String scenario = ctx.workflow().string("requirements.scenario", "GREENFIELD");
        List<Map<String, Object>> checks = new ArrayList<>();

        @SuppressWarnings("unchecked")
        List<Object> uncovered = (List<Object>) ((Map<String, Object>) ctx.workflow().output("test-planning")
                .getOrDefault("coverage", Map.of())).getOrDefault("uncovered", List.of("unknown"));
        checks.add(check("Every acceptance criterion is covered by a test case", uncovered.isEmpty(),
                uncovered.isEmpty() ? "all criteria traced" : "uncovered: " + uncovered));

        List<Map<String, Object>> files = maps(ctx.workflow().get("implementation.plannedFiles"));
        checks.add(check("Implementation plan lists files to change", !files.isEmpty(), files.size() + " planned files"));

        List<String> types = maps(ctx.workflow().get("test-planning.testCases")).stream().map(c -> str(c, "type")).toList();
        if (scenario.equals("BROWNFIELD")) {
            checks.add(check("Regression tests exist for the brownfield change", types.contains("REGRESSION"), "types=" + types));
        }
        if (scenario.equals("AMBIGUOUS")) {
            checks.add(check("Load and reliability tests exist for the agreed targets",
                    types.contains("LOAD") && (types.contains("RELIABILITY") || types.contains("CHAOS")), "types=" + types));
            boolean approved = ctx.workflow().flag("assumption-approval.approved");
            boolean provisional = maps(ctx.workflow().get("requirements.acceptanceCriteria")).stream()
                    .anyMatch(c -> "PROVISIONAL".equals(str(c, "status")));
            checks.add(check("Provisional criteria were validated only after assumptions were approved", !provisional || approved,
                    "assumptionsApproved=" + approved));
        }
        List<String> failed = checks.stream().filter(c -> !(Boolean) c.get("passed")).map(c -> str(c, "name")).toList();
        if (!failed.isEmpty()) {
            throw new ValidationFailureException("Validation failed: " + failed, true);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("passed", true);
        out.put("checks", checks);
        out.put("coverage", ctx.workflow().get("test-planning.coverage"));
        out.put("executionNote", "Static plan-level validation only. Tests of this repository are run by Maven (mvn test); "
                + "generated code is never executed by the platform.");
        StringBuilder md = new StringBuilder("# Validation report\n\n");
        checks.forEach(c -> md.append("- [x] ").append(str(c, "name")).append(" - ").append(str(c, "detail")).append('\n'));
        md.append("\n").append(out.get("executionNote")).append('\n');
        return ctx.result(out, List.of(new ArtifactDraft("validation-report.md", "MARKDOWN", md.toString())),
                List.of(new DecisionDraft("Validation passed (" + checks.size() + " checks)", "Traceability and scenario checks satisfied")));
    }

    private static Map<String, Object> check(String name, boolean passed, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("passed", passed);
        m.put("detail", detail);
        return m;
    }
}