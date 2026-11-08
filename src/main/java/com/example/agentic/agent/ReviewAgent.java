package com.example.agentic.agent;

import com.example.agentic.domain.GeneratedArtifact;
import com.example.agentic.exception.SecurityConcernException;
import com.example.agentic.governance.SecretRedactor;
import com.example.agentic.llm.LLMOperation;
import com.example.agentic.llm.LLMResponse;
import com.example.agentic.service.ArtifactService;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Stage 7: code review, risk identification and deterministic security scanning of every staged artifact.
 * A critical finding or a credential-like string raises SecurityConcernException, which safe-stops the workflow.
 */
@Component
public class ReviewAgent extends AbstractAgent {
    private final ArtifactService artifacts;

    public ReviewAgent(ArtifactService artifacts) {
        this.artifacts = artifacts;
    }

    @Override
    public String id() { return "review"; }

    @Override
    public AgentResult execute(AgentContext ctx) {
        String scenario = ctx.workflow().string("requirements.scenario", "GREENFIELD");
        LLMResponse review = ctx.call(LLMOperation.REVIEW_CODE, Map.of("scenario", scenario,
                "plannedFiles", ctx.workflow().list("implementation.plannedFiles")));
        require(review.payload(), "ReviewAgent", "findings", "verdict");
        LLMResponse risk = ctx.call(LLMOperation.IDENTIFY_RISKS, Map.of("scenario", scenario));
        require(risk.payload(), "ReviewAgent", "risks");
        List<Map<String, Object>> findings = maps(review.payload().get("findings"));
        List<Map<String, Object>> risks = maps(risk.payload().get("risks"));
        requireFields(findings, "ReviewAgent", "finding", "id", "severity", "message");
        requireFields(risks, "ReviewAgent", "risk", "id", "severity", "description");

        int scanned = 0;
        for (GeneratedArtifact a : artifacts.listActive(ctx.workflowId())) {
            scanned++;
            if (SecretRedactor.containsSecret(a.getContent())) {
                throw new SecurityConcernException("Credential-like content found in artifact " + a.getName());
            }
        }
        for (Map<String, Object> f : findings) {
            if ("CRITICAL".equals(str(f, "severity"))) {
                throw new SecurityConcernException("Critical review finding " + str(f, "id") + ": " + str(f, "message"));
            }
        }
        for (Map<String, Object> r : risks) {
            if ("CRITICAL".equals(str(r, "severity"))) {
                throw new SecurityConcernException("Critical risk " + str(r, "id") + ": " + str(r, "description"));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verdict", str(review.payload(), "verdict"));
        out.put("findings", findings);
        out.put("risks", risks);
        out.put("blockingFindings", 0);
        out.put("secretScan", Map.of("artifactsScanned", scanned, "hits", 0));

        StringBuilder md = new StringBuilder("# Security and risk review\n\nVerdict: ").append(out.get("verdict"))
                .append("\n\n## Findings\n");
        findings.forEach(f -> md.append("- ").append(str(f, "id")).append(" [").append(str(f, "severity")).append("] ").append(str(f, "message")).append('\n'));
        md.append("\n## Risks\n");
        risks.forEach(r -> md.append("- ").append(str(r, "id")).append(" [").append(str(r, "severity")).append("] ")
                .append(str(r, "description")).append(" -> ").append(str(r, "mitigation")).append('\n'));
        md.append("\nSecret scan: ").append(scanned).append(" artifacts scanned, no hits.\n");
        return ctx.result(out, List.of(new ArtifactDraft("security-risk-review.md", "MARKDOWN", md.toString())),
                List.of(new DecisionDraft("Review verdict " + out.get("verdict") + " with " + risks.size() + " risks recorded",
                        "No critical findings; secret scan clean")));
    }
}