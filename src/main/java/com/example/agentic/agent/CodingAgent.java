package com.example.agentic.agent;

import com.example.agentic.domain.ActionType;
import com.example.agentic.governance.ChangeControlService;
import com.example.agentic.governance.SecurityValidator;
import com.example.agentic.llm.LLMOperation;
import com.example.agentic.llm.LLMResponse;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Stage 4: produces an implementation PLAN. It never writes into a project tree and never compiles or runs
 * code. Sensitive actions proposed by the architecture must hold a valid approval before planning proceeds.
 */
@Component
public class CodingAgent extends AbstractAgent {
    private final SecurityValidator validator;
    private final ChangeControlService changeControl;

    public CodingAgent(SecurityValidator validator, ChangeControlService changeControl) {
        this.validator = validator;
        this.changeControl = changeControl;
    }

    @Override
    public String id() { return "coding"; }

    @Override
    public AgentResult execute(AgentContext ctx) {
        for (Object action : ctx.workflow().list("architecture.approvalActions")) {
            changeControl.authorize(ctx.workflowId(), ActionType.valueOf(action.toString()));
        }
        String scenario = ctx.workflow().string("requirements.scenario", "GREENFIELD");
        LLMResponse r = ctx.call(LLMOperation.PLAN_CODE_GENERATION,
                Map.of("scenario", scenario, "architecture", ctx.workflow().output("architecture")));
        require(r.payload(), "CodingAgent", "files", "steps");
        List<Map<String, Object>> files = maps(r.payload().get("files"));
        requireFields(files, "CodingAgent", "planned file", "path", "action", "purpose");
        files.forEach(f -> validator.validateProjectPath(str(f, "path")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plannedFiles", files);
        out.put("steps", r.payload().get("steps"));
        out.put("executionPolicy", r.payload().getOrDefault("executionPolicy", "PLAN_ONLY"));
        out.put("rollbackPlan", r.payload().getOrDefault("rollbackPlan", "Revert the change set"));
        out.put("fileCount", files.size());

        StringBuilder md = new StringBuilder("# Implementation plan\n\n> Plan only: generated code is never compiled or executed by the platform.\n\n| Action | Path | Purpose |\n|---|---|---|\n");
        files.forEach(f -> md.append("| ").append(str(f, "action")).append(" | `").append(str(f, "path")).append("` | ").append(str(f, "purpose")).append(" |\n"));
        md.append("\n## Steps\n");
        ((List<?>) r.payload().get("steps")).forEach(s -> md.append("1. ").append(s).append('\n'));
        md.append("\n## Rollback\n").append(out.get("rollbackPlan")).append('\n');
        return ctx.result(out, List.of(new ArtifactDraft("implementation-plan.md", "MARKDOWN", md.toString())),
                List.of(new DecisionDraft("Implementation plan covers " + files.size() + " files",
                        "Plan-only output; execution policy: " + out.get("executionPolicy"))));
    }
}