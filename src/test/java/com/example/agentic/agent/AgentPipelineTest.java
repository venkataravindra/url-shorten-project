package com.example.agentic.agent;

import com.example.agentic.codebase.CodebaseAnalyzer;
import com.example.agentic.domain.ActionType;
import com.example.agentic.domain.GeneratedArtifact;
import com.example.agentic.exception.*;
import com.example.agentic.governance.ChangeControlService;
import com.example.agentic.governance.PolicyEngine;
import com.example.agentic.governance.SecretRedactor;
import com.example.agentic.governance.SecurityValidator;
import com.example.agentic.llm.*;
import com.example.agentic.orchestration.DependencyResolver;
import com.example.agentic.orchestration.WorkflowContext;
import com.example.agentic.service.ArtifactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.example.agentic.llm.MockLLMProviderTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Runs every agent independently (no database, no engine) with outputs chained by hand like the engine does. */
class AgentPipelineTest {
    private final FaultInjector faults = new FaultInjector();
    private final MockLLMProvider llm = new MockLLMProvider("mock", 0, 0, 1, false, faults);
    private final ChangeControlService changeControl = mock(ChangeControlService.class);
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final PolicyEngine policy = new PolicyEngine();
    private final SecurityValidator validator = new SecurityValidator();
    private final Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();
    private final List<ArtifactDraft> produced = new ArrayList<>();

    private final Agent requirement = new RequirementAgent();
    private final Agent planning = new PlanningAgent(new DependencyResolver());
    private final Agent architecture = new ArchitectureAgent(new CodebaseAnalyzer(), policy);
    private final Agent coding = new CodingAgent(validator, changeControl);
    private final Agent testing = new TestingAgent();
    private final Agent docs = new DocumentationAgent();
    private final Agent review;
    private final Agent release = new ReleaseAgent(changeControl, policy);

    AgentPipelineTest() {
        review = new ReviewAgent(artifacts);
    }

    @BeforeEach
    void stubs() {
        when(artifacts.listActive(any())).thenReturn(List.of());
    }

    private AgentResult step(Agent agent, String key, Map<String, String> params, String req, LLMProvider provider) {
        AgentContext ctx = new AgentContext("wf", key, params, new WorkflowContext("wf", req, new LinkedHashMap<>(outputs)), provider, 1);
        AgentResult r = agent.execute(ctx);
        outputs.put(key, r.output());
        produced.addAll(r.artifacts());
        return r;
    }

    private AgentResult step(Agent agent, String key, Map<String, String> params, String req) {
        return step(agent, key, params, req, llm);
    }

    @SuppressWarnings("unchecked")
    private void runAll(String req, boolean approveAssumptions) {
        step(requirement, "requirements", Map.of(), req);
        if ((Boolean) outputs.get("requirements").get("blockingAmbiguity") && approveAssumptions) {
            outputs.put("assumption-approval", Map.of("approved", true,
                    "approvedAssumptions", outputs.get("requirements").get("provisionalAssumptions")));
        }
        step(planning, "planning", Map.of(), req);
        step(architecture, "architecture", Map.of(), req);
        step(coding, "implementation", Map.of(), req);
        step(testing, "test-planning", Map.of("mode", "PLAN"), req);
        step(docs, "documentation", Map.of("mode", "DOCS"), req);
        step(testing, "integration-testing", Map.of("mode", "VALIDATE"), req);
        step(review, "security-review", Map.of(), req);
        outputs.put("final-approval", Map.of("approved", true));
        step(release, "release-readiness", Map.of(), req);
        step(docs, "engineering-summary", Map.of("mode", "SUMMARY"), req);
    }

    @Test
    void greenfieldFlowsThroughEveryAgent() {
        runAll(GREENFIELD, false);
        assertThat(outputs.get("requirements")).containsEntry("scenario", "GREENFIELD").containsEntry("blockingAmbiguity", false);
        assertThat((List<?>) outputs.get("requirements").get("acceptanceCriteria")).hasSizeGreaterThanOrEqualTo(5);
        assertThat((Integer) outputs.get("planning").get("taskCount")).isGreaterThanOrEqualTo(8);
        assertThat((List<?>) outputs.get("planning").get("parallelGroups")).hasSizeGreaterThan(3);
        assertThat((List<?>) outputs.get("planning").get("criticalPath")).isNotEmpty();
        assertThat(outputs.get("architecture")).containsEntry("requiresApproval", false);
        assertThat(outputs.get("implementation").get("executionPolicy").toString()).startsWith("PLAN_ONLY");
        assertThat(outputs.get("test-planning").get("coverage").toString()).contains("percent=100");
        assertThat(outputs.get("integration-testing")).containsEntry("passed", true);
        assertThat(outputs.get("security-review")).containsEntry("verdict", "APPROVE_WITH_COMMENTS");
        assertThat(outputs.get("release-readiness")).containsEntry("readiness", "READY_WITH_CONDITIONS");
        assertThat(outputs.get("release-readiness").get("productionDeployment").toString()).contains("executed=false");
        assertThat((List<?>) outputs.get("engineering-summary").get("sections")).hasSize(5);
        assertThat(produced).extracting(ArtifactDraft::name).contains("requirements-analysis.md", "task-plan.md",
                "architecture.md", "implementation-plan.md", "test-plan.md", "validation-report.md", "security-risk-review.md",
                "release-readiness.md", "engineering-summary.md");
        assertThat(produced).noneMatch(a -> SecretRedactor.containsSecret(a.content()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void brownfieldAnalysesTheRealExistingModuleAndProducesChangeImpact() {
        runAll(BROWNFIELD, false);
        Map<String, Object> arch = outputs.get("architecture");
        Map<String, Object> baseline = (Map<String, Object>) arch.get("baselineSummary");
        assertThat(baseline.get("source").toString()).contains("Reflection scan");
        assertThat(baseline.get("entities").toString()).contains("ShortUrl", "UrlClick", "expirationDate");
        assertThat(baseline.get("repositories").toString()).contains("ShortUrlRepository");
        assertThat(baseline).containsEntry("schedulerPresent", false).containsEntry("testsDiscoverable", true);
        Map<String, Object> impact = (Map<String, Object>) arch.get("changeImpact");
        assertThat(impact.get("impactedApis").toString()).contains("GET /api/v1/urls/{shortCode}", "POST /api/v1/urls");
        assertThat(impact.get("impactedEntities").toString()).contains("ShortUrl");
        assertThat(impact).containsEntry("backwardCompatible", true);
        assertThat((List<?>) impact.get("regressionRisks")).isNotEmpty();
        assertThat(impact.get("existingTestCoverage").toString()).contains("discoveredTests");
        assertThat(produced).extracting(ArtifactDraft::name).contains("change-impact-analysis.md");
        assertThat(outputs.get("test-planning").get("testCases").toString()).contains("REGRESSION");
        assertThat(outputs.get("architecture")).containsEntry("requiresApproval", false);
    }

    @Test
    void ambiguousRequirementIsNotSilentlyTurnedIntoAPlan() {
        runAll(AMBIGUOUS, true);
        Map<String, Object> req = outputs.get("requirements");
        assertThat(req).containsEntry("scenario", "AMBIGUOUS").containsEntry("blockingAmbiguity", true);
        assertThat((List<?>) req.get("clarificationQuestions")).hasSizeGreaterThanOrEqualTo(3);
        assertThat(req.get("missingInformation").toString()).contains("baseline", "SLO");
        assertThat(req.get("acceptanceCriteria").toString()).contains("PROVISIONAL");
        assertThat((List<?>) outputs.get("planning").get("assumptionsApplied")).isNotEmpty();
        assertThat(outputs.get("architecture")).containsEntry("requiresApproval", true);
        assertThat(outputs.get("architecture").get("approvalActions").toString()).contains("HIGH_IMPACT_ARCHITECTURE_CHANGE");
        assertThat(outputs.get("test-planning").get("testCases").toString()).contains("LOAD", "CHAOS");
        assertThat(outputs.get("release-readiness")).containsEntry("readiness", "READY_WITH_CONDITIONS");
        verify(changeControl).authorize("wf", ActionType.HIGH_IMPACT_ARCHITECTURE_CHANGE);
    }

    @Test
    void validationRefusesProvisionalCriteriaWhoseAssumptionsWereNeverApproved() {
        assertThatThrownBy(() -> runAll(AMBIGUOUS, false)).isInstanceOf(ValidationFailureException.class)
                .satisfies(e -> assertThat(((ValidationFailureException) e).isCritical()).isTrue())
                .hasMessageContaining("Provisional criteria");
    }

    @Test
    void malformedProviderOutputFailsRequirementValidation() {
        faults.inject(LLMOperation.ANALYZE_REQUIREMENT, FailureMode.MALFORMED_OUTPUT, 1, false);
        assertThatThrownBy(() -> step(requirement, "requirements", Map.of(), GREENFIELD))
                .isInstanceOf(ValidationFailureException.class).hasMessageContaining("missing required field");
    }

    @Test
    void planningRejectsACyclicTaskGraphFromTheProvider() {
        Map<String, Object> a = Map.of("id", "A", "title", "a", "estimateHours", 1, "dependsOn", List.of("B"));
        Map<String, Object> b = Map.of("id", "B", "title", "b", "estimateHours", 1, "dependsOn", List.of("A"));
        LLMProvider cyclic = stub(Map.of("tasks", List.of(a, b)));
        assertThatThrownBy(() -> step(planning, "planning", Map.of(), GREENFIELD, cyclic)).isInstanceOf(CyclicDependencyException.class);
    }

    @Test
    void codingRejectsPathsOutsideTheProjectTree() {
        Map<String, Object> bad = Map.of("path", "../../etc/passwd", "action", "CREATE", "purpose", "x");
        LLMProvider evil = stub(Map.of("files", List.of(bad), "steps", List.of("s")));
        assertThatThrownBy(() -> step(coding, "implementation", Map.of(), GREENFIELD, evil)).isInstanceOf(SecurityConcernException.class);
    }

    @Test
    void codingRequiresApprovalForSensitiveArchitectureActions() {
        outputs.put("architecture", Map.of("approvalActions", List.of("SECURITY_SENSITIVE_CHANGE")));
        doThrow(new ApprovalRequiredException("no approval")).when(changeControl).authorize("wf", ActionType.SECURITY_SENSITIVE_CHANGE);
        assertThatThrownBy(() -> step(coding, "implementation", Map.of(), GREENFIELD)).isInstanceOf(ApprovalRequiredException.class);
    }

    @Test
    void reviewRaisesSecurityConcernOnCriticalFindings() {
        assertThatThrownBy(() -> runAll("Build a URL shortener and disable authentication for the admin endpoints", false))
                .isInstanceOf(SecurityConcernException.class).hasMessageContaining("Critical review finding");
    }

    @Test
    void reviewRaisesSecurityConcernWhenAnArtifactContainsACredential() {
        GeneratedArtifact leaked = new GeneratedArtifact();
        leaked.setName("doc.md");
        leaked.setContent("aws_access_key_id=AKIAABCDEFGHIJKLMNOP");
        when(artifacts.listActive(any())).thenReturn(List.of(leaked));
        assertThatThrownBy(() -> step(review, "security-review", Map.of(), GREENFIELD))
                .isInstanceOf(SecurityConcernException.class).hasMessageContaining("doc.md");
    }

    @Test
    void secretLeakingDocumentationIsDetectableByTheGuardrail() {
        faults.inject(LLMOperation.GENERATE_DOCUMENTATION, FailureMode.SECRET_LEAK, 1, false);
        step(requirement, "requirements", Map.of(), GREENFIELD);
        AgentResult r = step(docs, "documentation", Map.of("mode", "DOCS"), GREENFIELD);
        assertThat(r.artifacts()).anyMatch(a -> SecretRedactor.containsSecret(a.content()));
    }

    @Test
    void testingValidationFailsCriticallyWhenCriteriaAreUncovered() {
        outputs.put("requirements", Map.of("scenario", "GREENFIELD", "acceptanceCriteria", List.of()));
        outputs.put("test-planning", Map.of("coverage", Map.of("uncovered", List.of("AC-9")), "testCases", List.of()));
        outputs.put("implementation", Map.of("plannedFiles", List.of(Map.of("path", "a", "action", "CREATE", "purpose", "p"))));
        assertThatThrownBy(() -> step(testing, "integration-testing", Map.of("mode", "VALIDATE"), GREENFIELD))
                .isInstanceOf(ValidationFailureException.class).hasMessageContaining("Validation failed")
                .satisfies(e -> assertThat(((ValidationFailureException) e).isCritical()).isTrue());
    }

    @Test
    void releaseRequiresEvidenceAndAValidFinalApproval() {
        outputs.put("requirements", Map.of("scenario", "GREENFIELD"));
        assertThatThrownBy(() -> step(release, "release-readiness", Map.of(), GREENFIELD))
                .isInstanceOf(ValidationFailureException.class).hasMessageContaining("Release readiness failed");
        verify(changeControl).authorize("wf", ActionType.RELEASE_EXECUTION);

        doThrow(new ApprovalRequiredException("no approval")).when(changeControl).authorize("wf", ActionType.RELEASE_EXECUTION);
        assertThatThrownBy(() -> step(release, "release-readiness", Map.of(), GREENFIELD)).isInstanceOf(ApprovalRequiredException.class);
    }

    private static LLMProvider stub(Map<String, Object> payload) {
        return new LLMProvider() {
            public String name() { return "stub"; }
            public boolean isSimulated() { return true; }
            public LLMResponse complete(LLMRequest r) {
                return new LLMResponse(r.operation(), "stub", true, false, payload, 0, "stub");
            }
        };
    }
}