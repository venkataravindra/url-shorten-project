package com.example.agentic.agent;

import com.example.agentic.domain.ActionType;
import com.example.agentic.exception.ValidationFailureException;
import com.example.agentic.governance.ChangeControlService;
import com.example.agentic.governance.PolicyEngine;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Stage 8: release READINESS only. It checks that the evidence is in place and that the human final approval
 * is valid. It never deploys; production deployment stays a separate, manually approved action.
 */
@Component
public class ReleaseAgent extends AbstractAgent {
    private static final List<String> REQUIRED = List.of("requirements", "planning", "architecture", "implementation",
            "test-planning", "integration-testing", "documentation", "security-review", "final-approval");
    private final ChangeControlService changeControl;
    private final PolicyEngine policy;

    public ReleaseAgent(ChangeControlService changeControl, PolicyEngine policy) {
        this.changeControl = changeControl;
        this.policy = policy;
    }

    @Override
    public String id() { return "release"; }

    @Override
    public AgentResult execute(AgentContext ctx) {
        changeControl.authorize(ctx.workflowId(), ActionType.RELEASE_EXECUTION);
        List<Map<String, Object>> checklist = new ArrayList<>();
        for (String key : REQUIRED) {
            checklist.add(item("Stage '" + key + "' completed", ctx.workflow().has(key)));
        }
        checklist.add(item("Validation passed", ctx.workflow().flag("integration-testing.passed")));
        checklist.add(item("Review verdict is not REJECT", !"REJECT".equals(ctx.workflow().string("security-review.verdict", "REJECT"))));
        checklist.add(item("Human final approval granted", ctx.workflow().flag("final-approval.approved")));
        checklist.add(item("Rollback plan present", ctx.workflow().get("implementation.rollbackPlan") != null));

        List<String> failed = checklist.stream().filter(c -> !(Boolean) c.get("passed")).map(c -> c.get("check").toString()).toList();
        if (!failed.isEmpty()) {
            throw new ValidationFailureException("Release readiness failed: " + failed, true);
        }
        List<String> conditions = new ArrayList<>();
        maps(ctx.workflow().get("security-review.risks")).stream()
                .filter(r -> "HIGH".equals(str(r, "severity")) || "MEDIUM".equals(str(r, "severity")))
                .forEach(r -> conditions.add("Mitigate " + str(r, "id") + ": " + str(r, "mitigation")));
        if (ctx.workflow().flag("assumption-approval.approved")) {
            conditions.add("Confirm provisional targets against measured baselines");
        }
        String readiness = conditions.isEmpty() ? "READY" : "READY_WITH_CONDITIONS";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("readiness", readiness);
        out.put("checklist", checklist);
        out.put("conditions", conditions);
        out.put("productionDeployment", Map.of("executed", false,
                "policy", policy.evaluate(ActionType.PRODUCTION_DEPLOYMENT).outcome().name(),
                "note", "The platform never deploys. Production deployment needs its own manual approval and pipeline."));
        out.put("rollbackPlan", ctx.workflow().get("implementation.rollbackPlan"));
        StringBuilder md = new StringBuilder("# Release readiness: ").append(readiness).append("\n\n");
        checklist.forEach(c -> md.append("- [x] ").append(c.get("check")).append('\n'));
        md.append("\n## Conditions\n");
        conditions.forEach(c -> md.append("- ").append(c).append('\n'));
        md.append("\nProduction deployment was NOT performed.\n");
        return ctx.result(out, List.of(new ArtifactDraft("release-readiness.md", "MARKDOWN", md.toString())),
                List.of(new DecisionDraft("Release readiness: " + readiness, "All evidence present; deployment remains a manual, separately approved step")));
    }

    private static Map<String, Object> item(String check, boolean passed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("check", check);
        m.put("passed", passed);
        return m;
    }
}