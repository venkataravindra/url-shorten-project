package com.example.agentic.workflow;

import com.example.agentic.IntegrationTestBase;
import com.example.agentic.domain.AgentExecution;
import com.example.agentic.repository.AgentExecutionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The three mandatory demonstration scenarios, executed end to end through the REST API and the real engine. */
class WorkflowScenarioIntegrationTest extends IntegrationTestBase {
    @Autowired AgentExecutionRepository executions;

    private static String st(JsonNode n) { return n.get("status").asText(); }

    private List<String> statuses(JsonNode artifactsArray) {
        return StreamSupport.stream(artifactsArray.spliterator(), false).map(a -> a.get("status").asText()).collect(Collectors.toList());
    }

    @Test
    void scenario1Greenfield() throws Exception {
        String id = createWorkflow(GREENFIELD);
        assertThat(st(workflow(id))).isEqualTo("CREATED");

        JsonNode waiting = execute(id);
        assertThat(st(waiting)).isEqualTo("WAITING_FOR_APPROVAL");
        assertThat(waiting.get("scenario").asText()).isEqualTo("GREENFIELD");
        assertThat(waiting.get("pendingApprovals")).hasSize(1);
        assertThat(waiting.get("pendingApprovals").get(0).get("type").asText()).isEqualTo("RELEASE_EXECUTION");

        Map<String, JsonNode> tasks = tasks(id);
        assertThat(st(tasks.get("assumption-approval"))).isEqualTo("SKIPPED");
        assertThat(st(tasks.get("architecture-approval"))).isEqualTo("SKIPPED");
        assertThat(st(tasks.get("final-approval"))).isEqualTo("WAITING_APPROVAL");
        assertThat(st(tasks.get("release-readiness"))).isEqualTo("PENDING");
        for (String done : List.of("requirements", "planning", "architecture", "implementation", "test-planning",
                "documentation", "integration-testing", "security-review")) {
            assertThat(st(tasks.get(done))).as(done).isEqualTo("COMPLETED");
        }
        assertThat(tasks.get("implementation").get("dependsOn").toString()).contains("architecture-approval");
        assertThat(tasks.get("security-review").get("dependsOn").toString()).contains("documentation", "integration-testing");
        assertThat(tasks.get("requirements").get("output").get("blockingAmbiguity").asBoolean()).isFalse();
        assertThat(tasks.get("requirements").get("output").get("ambiguities").size()).isGreaterThan(0);

        // parallel branches: implementation and test planning overlapped in time
        Map<String, AgentExecution> byTask = executions.findByWorkflowIdOrderByIdAsc(id).stream()
                .collect(Collectors.toMap(AgentExecution::getTaskKey, e -> e, (a, b) -> a));
        AgentExecution impl = byTask.get("implementation");
        AgentExecution test = byTask.get("test-planning");
        Instant implEnd = impl.getStartedAt().plusMillis(impl.getDurationMs());
        Instant testEnd = test.getStartedAt().plusMillis(test.getDurationMs());
        assertThat(impl.getStartedAt()).isBefore(testEnd);
        assertThat(test.getStartedAt()).isBefore(implEnd);

        JsonNode done = approve(id);
        assertThat(st(done)).isEqualTo("COMPLETED");

        JsonNode summary = getJson("/api/v1/workflows/" + id + "/summary");
        assertThat(summary.get("summary").get("sections")).hasSize(5);
        assertThat(summary.get("releaseReadiness").get("readiness").asText()).isEqualTo("READY_WITH_CONDITIONS");
        assertThat(summary.get("releaseReadiness").get("conditions").toString()).contains("R-1");

        JsonNode artifacts = getJson("/api/v1/workflows/" + id + "/artifacts");
        assertThat(artifacts.size()).isGreaterThanOrEqualTo(10);
        assertThat(statuses(artifacts)).containsOnly("ACCEPTED");
        assertThat(artifacts.get(0).has("content")).isFalse();
        assertThat(getJson("/api/v1/workflows/" + id + "/artifacts?includeContent=true").get(0).get("content").asText()).isNotBlank();

        JsonNode decisions = getJson("/api/v1/workflows/" + id + "/decisions");
        assertThat(decisions.size()).isGreaterThan(8);
        assertThat(StreamSupport.stream(decisions.spliterator(), false).anyMatch(d -> d.get("parentDecisionIds").size() > 0)).isTrue();

        Set<String> auditTypes = StreamSupport.stream(getJson("/api/v1/audit/workflows/" + id).spliterator(), false)
                .map(e -> e.get("eventType").asText()).collect(Collectors.toSet());
        assertThat(auditTypes).contains("WORKFLOW_CREATED", "WORKFLOW_STARTED", "TASK_COMPLETED", "TASK_SKIPPED",
                "APPROVAL_REQUESTED", "APPROVAL_GRANTED", "ARTIFACT_STAGED", "ARTIFACTS_ACCEPTED", "WORKFLOW_COMPLETED");

        JsonNode metrics = getJson("/api/v1/metrics/workflows/" + id);
        assertThat(metrics.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(metrics.get("agentExecutions").asLong()).as("10 agent tasks, one attempt each").isEqualTo(10);
        assertThat(metrics.get("endToEndLatencyMs").isNull()).isFalse();
        assertThat(metrics.get("approvalsDecided").asLong()).isEqualTo(1);
        JsonNode overall = getJson("/api/v1/metrics/summary");
        assertThat(overall.get("totalWorkflows").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(overall.get("workflowSuccessRate").asDouble()).isGreaterThan(0);
        assertThat(overall.get("definitions")).isNotEmpty();
    }

    @Test
    void scenario2Brownfield() throws Exception {
        String id = createWorkflow(BROWNFIELD);
        JsonNode waiting = execute(id);
        assertThat(st(waiting)).isEqualTo("WAITING_FOR_APPROVAL");
        assertThat(waiting.get("scenario").asText()).isEqualTo("BROWNFIELD");

        JsonNode arch = tasks(id).get("architecture").get("output");
        JsonNode impact = arch.get("changeImpact");
        assertThat(arch.get("baselineSummary").get("source").asText()).contains("Reflection scan");
        assertThat(arch.get("baselineSummary").get("schedulerPresent").asBoolean()).isFalse();
        assertThat(impact.get("impactedApis").toString()).contains("GET /api/v1/urls/{shortCode}");
        assertThat(impact.get("impactedEntities").toString()).contains("ShortUrl");
        assertThat(impact.get("impactedServices").toString()).contains("UrlShortenerService");
        assertThat(impact.get("impactedRepositories").toString()).contains("ShortUrlRepository");
        assertThat(impact.get("backwardCompatible").asBoolean()).isTrue();
        assertThat(impact.get("regressionRisks")).isNotEmpty();
        assertThat(impact.get("existingTestCoverage").toString()).contains("discoveredTests");
        assertThat(tasks(id).get("test-planning").get("output").get("testCases").toString()).contains("REGRESSION");
        assertThat(getJson("/api/v1/workflows/" + id + "/artifacts").toString()).contains("change-impact-analysis.md");

        assertThat(st(approve(id))).isEqualTo("COMPLETED");
        assertThat(getJson("/api/v1/workflows/" + id + "/summary").get("summary").get("headline").asText()).contains("BROWNFIELD");
    }

    @Test
    void scenario3AmbiguousRequirementNeedsHumanApprovalOfAssumptionsAndDesign() throws Exception {
        String id = createWorkflow(AMBIGUOUS);
        JsonNode first = execute(id);
        assertThat(st(first)).isEqualTo("WAITING_FOR_APPROVAL");
        assertThat(first.get("scenario").asText()).isEqualTo("AMBIGUOUS");
        assertThat(first.get("pendingApprovals").get(0).get("type").asText()).isEqualTo("MATERIAL_ASSUMPTIONS");

        Map<String, JsonNode> t1 = tasks(id);
        JsonNode req = t1.get("requirements").get("output");
        assertThat(req.get("blockingAmbiguity").asBoolean()).isTrue();
        assertThat(req.get("clarificationQuestions").size()).isGreaterThanOrEqualTo(3);
        assertThat(req.get("missingInformation").toString()).contains("Current baseline latency");
        assertThat(req.get("provisionalAssumptions").toString()).contains("p95 <= 50 ms");
        assertThat(st(t1.get("assumption-approval"))).isEqualTo("WAITING_APPROVAL");
        assertThat(st(t1.get("planning"))).as("no plan before assumptions are approved").isEqualTo("PENDING");

        // executing again must not get past the gate
        assertThat(st(execute(id))).isEqualTo("WAITING_FOR_APPROVAL");
        assertThat(st(tasks(id).get("planning"))).isEqualTo("PENDING");

        // an engineer is not allowed to decide approvals
        mvc.perform(post("/api/v1/workflows/" + id + "/approve").with(engineer())).andExpect(status().isForbidden());

        JsonNode second = approve(id);
        assertThat(st(second)).isEqualTo("WAITING_FOR_APPROVAL");
        assertThat(second.get("pendingApprovals").get(0).get("type").asText()).isEqualTo("HIGH_IMPACT_ARCHITECTURE_CHANGE");
        Map<String, JsonNode> t2 = tasks(id);
        assertThat(t2.get("planning").get("output").get("assumptionsApplied")).isNotEmpty();
        assertThat(st(t2.get("test-planning"))).as("independent branch ran while design approval pends").isEqualTo("COMPLETED");
        assertThat(st(t2.get("implementation"))).isEqualTo("PENDING");

        JsonNode third = approve(id);
        assertThat(st(third)).isEqualTo("WAITING_FOR_APPROVAL");
        assertThat(third.get("pendingApprovals").get(0).get("type").asText()).isEqualTo("RELEASE_EXECUTION");
        assertThat(st(approve(id))).isEqualTo("COMPLETED");

        Map<String, JsonNode> done = tasks(id);
        assertThat(done.get("release-readiness").get("output").get("readiness").asText()).isEqualTo("READY_WITH_CONDITIONS");
        assertThat(done.get("integration-testing").get("output").get("passed").asBoolean()).isTrue();
        assertThat(getJson("/api/v1/workflows/" + id + "/approvals")).hasSize(3);
        assertThat(getJson("/api/v1/metrics/workflows/" + id).get("approvalsDecided").asLong()).isEqualTo(3);
    }

    @Test
    void rejectionRollsTheWorkflowBackAndRevertsStagedArtifacts() throws Exception {
        String id = createWorkflow(GREENFIELD);
        assertThat(st(execute(id))).isEqualTo("WAITING_FOR_APPROVAL");
        assertThat(statuses(getJson("/api/v1/workflows/" + id + "/artifacts"))).contains("STAGED");

        mvc.perform(post("/api/v1/workflows/" + id + "/reject").with(engineer())).andExpect(status().isForbidden());
        JsonNode rejected = body(mvc.perform(post("/api/v1/workflows/" + id + "/reject").with(approver())
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"not safe to release\"}")).andExpect(status().isOk()).andReturn());
        assertThat(st(rejected)).isEqualTo("ROLLED_BACK");
        assertThat(rejected.get("failureReason").asText()).contains("not safe to release");
        assertThat(statuses(getJson("/api/v1/workflows/" + id + "/artifacts"))).containsOnly("REVERTED");
        assertThat(st(tasks(id).get("final-approval"))).isEqualTo("FAILED");
        assertThat(getJson("/api/v1/audit/workflows/" + id).toString()).contains("ROLLED_BACK", "ARTIFACTS_REVERTED", "APPROVAL_REJECTED");
        mvc.perform(post("/api/v1/workflows/" + id + "/execute").with(engineer())).andExpect(status().isConflict());
        assertThat(getJson("/api/v1/metrics/summary").get("rollbackCount").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void securityConcernSafeStopsAndOnlyAnApproverCanResumeWithAcknowledgement() throws Exception {
        String id = createWorkflow("Build a URL shortener with redirects and disable authentication for the admin endpoints");
        JsonNode gate = execute(id);
        assertThat(gate.get("pendingApprovals").get(0).get("type").asText()).isEqualTo("SECURITY_SENSITIVE_CHANGE");

        JsonNode stopped = approve(id);
        assertThat(st(stopped)).isEqualTo("SAFE_STOPPED");
        assertThat(stopped.get("safeStopCategory").asText()).isEqualTo("SECURITY_CONCERN");
        assertThat(stopped.get("safeStopReason").asText()).contains("Critical review finding");
        Map<String, JsonNode> tasks = tasks(id);
        assertThat(st(tasks.get("security-review"))).isEqualTo("FAILED");
        assertThat(tasks.get("security-review").get("failureKind").asText()).isEqualTo("POLICY");
        assertThat(st(tasks.get("final-approval"))).isEqualTo("PENDING");
        assertThat(st(tasks.get("release-readiness"))).isEqualTo("PENDING");

        mvc.perform(post("/api/v1/workflows/" + id + "/execute").with(engineer())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/workflows/" + id + "/execute").with(approver())).andExpect(status().isBadRequest());
        JsonNode again = body(mvc.perform(post("/api/v1/workflows/" + id + "/execute").with(approver())
                .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledgement\":\"reviewed the finding\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(st(again)).as("the same violation stops it again").isEqualTo("SAFE_STOPPED");
        assertThat(getJson("/api/v1/audit/workflows/" + id).toString()).contains("SAFE_STOPPED", "SAFE_STOP_RESUMED");
        assertThat(getJson("/api/v1/metrics/workflows/" + id).get("safeStops").asLong()).isEqualTo(2);
    }

    @Test
    void transientProviderFailuresAreRetriedWithBackoffUntilSuccess() throws Exception {
        faultInject("DECOMPOSE_TASKS", "TIMEOUT", 2, false);
        String id = createWorkflow(GREENFIELD);
        assertThat(st(execute(id))).isEqualTo("WAITING_FOR_APPROVAL");
        assertThat(tasks(id).get("planning").get("attempts").asInt()).isEqualTo(3);
        assertThat(getJson("/api/v1/metrics/workflows/" + id).get("retryAttempts").asLong()).isEqualTo(2);
        long retries = StreamSupport.stream(getJson("/api/v1/audit/workflows/" + id).spliterator(), false)
                .filter(e -> e.get("eventType").asText().equals("TASK_RETRY_SCHEDULED")).count();
        assertThat(retries).isEqualTo(2);
        assertThat(getJson("/api/v1/metrics/summary").get("retryCount").asLong()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void fallbackProviderTakesOverWhenRetriesAreExhausted() throws Exception {
        faultInject("PLAN_CODE_GENERATION", "PROVIDER_UNAVAILABLE", 10, false);
        String id = createWorkflow(GREENFIELD);
        assertThat(st(execute(id))).isEqualTo("WAITING_FOR_APPROVAL");
        JsonNode exec = tasks(id).get("implementation").get("output").get("_execution");
        assertThat(exec.get("fallbackUsed").asBoolean()).isTrue();
        assertThat(exec.get("degraded").asBoolean()).isTrue();
        assertThat(exec.get("provider").asText()).isEqualTo("mock-llm-fallback");
        assertThat(getJson("/api/v1/audit/workflows/" + id).toString()).contains("FALLBACK_USED");
    }

    @Test
    void validationFailuresAreNotRetriedAndFailTheWorkflow() throws Exception {
        faultInject("ANALYZE_REQUIREMENT", "MALFORMED_OUTPUT", 1, false);
        String id = createWorkflow(GREENFIELD);
        JsonNode failed = execute(id);
        assertThat(st(failed)).isEqualTo("FAILED");
        JsonNode task = tasks(id).get("requirements");
        assertThat(st(task)).isEqualTo("FAILED");
        assertThat(task.get("failureKind").asText()).isEqualTo("VALIDATION");
        assertThat(task.get("attempts").asInt()).isEqualTo(1);
        assertThat(failed.get("failureReason").asText()).contains("missing required field");
        mvc.perform(post("/api/v1/workflows/" + id + "/execute").with(engineer())).andExpect(status().isConflict());
        assertThat(getJson("/api/v1/metrics/summary").get("workflowFailureRate").asDouble()).isGreaterThan(0);
    }

    @Test
    void credentialLeakingOutputIsBlockedAndSafeStopsTheWorkflow() throws Exception {
        faultInject("GENERATE_DOCUMENTATION", "SECRET_LEAK", 1, true);
        String id = createWorkflow(GREENFIELD);
        JsonNode stopped = execute(id);
        assertThat(st(stopped)).isEqualTo("SAFE_STOPPED");
        assertThat(stopped.get("safeStopCategory").asText()).isEqualTo("SECURITY_CONCERN");
        assertThat(stopped.get("safeStopReason").asText()).doesNotContain("AKIAABCDEFGHIJKLMNOP");
        assertThat(getJson("/api/v1/workflows/" + id + "/artifacts?includeContent=true").toString()).doesNotContain("AKIAABCDEFGHIJKLMNOP");
        assertThat(getJson("/api/v1/audit/workflows/" + id).toString()).doesNotContain("AKIAABCDEFGHIJKLMNOP");
    }

    @Test
    void lowImpactReplanPreservesUnaffectedWorkAndReopensTheApprovalGate() throws Exception {
        String id = createWorkflow(GREENFIELD);
        execute(id);
        Map<String, JsonNode> before = tasks(id);
        String planningOutput = before.get("planning").get("output").toString().replaceAll("\"_execution\":\\{[^}]*\\}", "");

        replanBad(id, "{\"changedTask\":\"nope\",\"changeType\":\"DOCUMENTATION_CHANGE\",\"reason\":\"x\"}", 400);
        replanBad(id, "{\"changedTask\":\"documentation\",\"changeType\":\"DOCUMENTATION_CHANGE\",\"reason\":\"\"}", 400);
        replanBad(id, "{\"changedTask\":\"documentation\",\"changeType\":\"DOCUMENTATION_CHANGE\",\"reason\":\"cycle\","
                + "\"addDependencies\":[{\"from\":\"final-approval\",\"to\":\"requirements\"}]}", 422);

        JsonNode r = replan(id, "{\"changedTask\":\"documentation\",\"changeType\":\"DOCUMENTATION_CHANGE\",\"reason\":\"docs were outdated\"}");
        assertThat(r.get("applied").asBoolean()).isTrue();
        assertThat(r.get("approvalRequired").asBoolean()).isFalse();
        assertThat(r.get("impactedTasks").toString()).contains("documentation", "security-review", "final-approval", "release-readiness");
        assertThat(r.get("preservedTasks").toString()).contains("requirements", "planning", "architecture", "implementation", "test-planning");
        assertThat(r.get("workflowStatus").asText()).isEqualTo("CREATED");
        assertThat(st(tasks(id).get("documentation"))).isEqualTo("STALE");

        assertThat(st(execute(id))).isEqualTo("WAITING_FOR_APPROVAL");
        Map<String, JsonNode> after = tasks(id);
        assertThat(after.get("documentation").get("revision").asInt()).isEqualTo(2);
        assertThat(after.get("requirements").get("revision").asInt()).isEqualTo(1);
        assertThat(after.get("planning").get("output").toString().replaceAll("\"_execution\":\\{[^}]*\\}", "")).isEqualTo(planningOutput);
        List<String> approvalStatuses = StreamSupport.stream(getJson("/api/v1/workflows/" + id + "/approvals").spliterator(), false)
                .map(a -> a.get("status").asText()).toList();
        assertThat(approvalStatuses).containsExactly("SUPERSEDED", "PENDING");
        assertThat(statuses(getJson("/api/v1/workflows/" + id + "/artifacts"))).contains("SUPERSEDED", "STAGED");
        assertThat(st(approve(id))).isEqualTo("COMPLETED");
    }

    @Test
    void highImpactReplanNeedsApprovalThenReExecutesOnlyAffectedTasks() throws Exception {
        String id = createWorkflow(GREENFIELD);
        execute(id);
        JsonNode r = replan(id, "{\"changedTask\":\"architecture\",\"changeType\":\"ARCHITECTURE_CHANGE\",\"reason\":\"switch to event-driven click recording\"}");
        assertThat(r.get("applied").asBoolean()).isFalse();
        assertThat(r.get("approvalRequired").asBoolean()).isTrue();
        long replanApproval = r.get("approvalId").asLong();
        assertThat(r.get("preservedTasks").toString()).contains("requirements", "planning").doesNotContain("architecture\"");
        assertThat(st(workflow(id))).isEqualTo("WAITING_FOR_APPROVAL");
        assertThat(st(tasks(id).get("architecture"))).as("nothing changes before approval").isEqualTo("COMPLETED");

        mvc.perform(post("/api/v1/workflows/" + id + "/execute").with(engineer())).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/workflows/" + id + "/approve").with(engineer())).andExpect(status().isForbidden());
        JsonNode resumed = body(mvc.perform(post("/api/v1/workflows/" + id + "/approve").with(approver())
                .contentType(MediaType.APPLICATION_JSON).content("{\"approvalId\":" + replanApproval + ",\"comment\":\"ok\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(st(resumed)).isEqualTo("WAITING_FOR_APPROVAL");
        Map<String, JsonNode> after = tasks(id);
        assertThat(after.get("architecture").get("revision").asInt()).isEqualTo(2);
        assertThat(after.get("implementation").get("revision").asInt()).isEqualTo(2);
        assertThat(after.get("requirements").get("revision").asInt()).isEqualTo(1);
        assertThat(after.get("planning").get("revision").asInt()).isEqualTo(1);
        assertThat(st(after.get("architecture"))).isEqualTo("COMPLETED");

        JsonNode decisions = getJson("/api/v1/workflows/" + id + "/decisions");
        assertThat(StreamSupport.stream(decisions.spliterator(), false).anyMatch(d -> d.get("superseded").asBoolean()
                && d.get("supersededReason").asText().contains("switch to event-driven"))).isTrue();
        assertThat(getJson("/api/v1/audit/workflows/" + id).toString()).contains("REPLAN_PROPOSED", "REPLAN_APPLIED");

        // a second high-impact proposal that gets rejected leaves the plan untouched
        JsonNode second = replan(id, "{\"changedTask\":\"architecture\",\"changeType\":\"ARCHITECTURE_CHANGE\",\"reason\":\"another idea\"}");
        JsonNode rejected = body(mvc.perform(post("/api/v1/workflows/" + id + "/reject").with(approver())
                .contentType(MediaType.APPLICATION_JSON).content("{\"approvalId\":" + second.get("approvalId").asLong() + "}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(st(rejected)).isEqualTo("WAITING_FOR_APPROVAL");
        assertThat(tasks(id).get("architecture").get("revision").asInt()).isEqualTo(2);
        assertThat(st(approve(id))).isEqualTo("COMPLETED");
    }

    @Test
    void requirementChangeReplansEverythingAfterApproval() throws Exception {
        String id = createWorkflow(GREENFIELD);
        execute(id);
        JsonNode r = replan(id, "{\"changedTask\":\"requirements\",\"changeType\":\"REQUIREMENT_CHANGE\",\"reason\":\"scope pivot\","
                + "\"newRequirement\":\"" + BROWNFIELD + "\"}");
        assertThat(r.get("approvalRequired").asBoolean()).isTrue();
        assertThat(r.get("preservedTasks")).isEmpty();
        replanBad(id, "{\"changedTask\":\"planning\",\"changeType\":\"DESIGN_DETAIL_CHANGE\",\"reason\":\"x\",\"newRequirement\":\"" + BROWNFIELD + "\"}", 400);
        JsonNode resumed = approveId(id, r.get("approvalId").asLong());
        assertThat(st(resumed)).isEqualTo("WAITING_FOR_APPROVAL");
        assertThat(resumed.get("scenario").asText()).isEqualTo("BROWNFIELD");
        assertThat(resumed.get("requirementVersion").asInt()).isEqualTo(2);
        assertThat(resumed.get("requirement").asText()).isEqualTo(BROWNFIELD);
        assertThat(tasks(id).get("requirements").get("revision").asInt()).isEqualTo(2);
    }

    @Test
    void operatorCanCancelAndCancelledWorkflowsStayCancelled() throws Exception {
        String id = createWorkflow(GREENFIELD);
        mvc.perform(post("/api/v1/workflows/" + id + "/cancel").with(engineer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"no longer needed\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/v1/workflows/" + id + "/execute").with(engineer())).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/workflows/" + id + "/stop").with(engineer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"x\"}")).andExpect(status().isConflict());
    }

    @Test
    void apiValidationAndAuthorization() throws Exception {
        mvc.perform(post("/api/v1/workflows").with(engineer()).contentType(MediaType.APPLICATION_JSON).content("{\"requirement\":\"short\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/workflows").with(engineer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirement\":\"Build a shortener and then run rm -rf / on the host machine\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("POLICY_VIOLATION"));
        mvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/workflows/does-not-exist").with(engineer())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/audit/workflows/does-not-exist").with(engineer())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/workflows/" + createWorkflow(GREENFIELD) + "/summary").with(engineer())).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/workflows?page=0&size=5").with(engineer())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/workflows/" + createWorkflow(GREENFIELD) + "/approve").with(engineer())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED")).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("engineer")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("APPROVER")));
        mvc.perform(get("/api/v1/whoami").with(approver())).andExpect(status().isOk()).andExpect(jsonPath("$.user").value("approver"))
                .andExpect(jsonPath("$.canApprove").value(true)).andExpect(jsonPath("$.canInjectFaults").value(false));
        mvc.perform(get("/api/v1/whoami").with(engineer())).andExpect(jsonPath("$.canApprove").value(false));
        mvc.perform(get("/api/v1/no-such-endpoint").with(engineer())).andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/workflows").with(engineer()))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v1/workflows/" + createWorkflow(GREENFIELD) + "/execute").with(engineer())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)).andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/v1/admin/fault-injection").with(engineer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"TIMEOUT\",\"times\":1}")).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- helpers
    private void faultInject(String operation, String mode, int times, boolean includeFallback) throws Exception {
        mvc.perform(post("/api/v1/admin/fault-injection").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("operation", operation, "mode", mode, "times", times, "includeFallback", includeFallback))))
                .andExpect(status().isOk());
    }

    private JsonNode replan(String id, String json) throws Exception {
        return body(mvc.perform(post("/api/v1/workflows/" + id + "/replan").with(engineer()).contentType(MediaType.APPLICATION_JSON)
                .content(json)).andExpect(status().isOk()).andReturn());
    }

    private void replanBad(String id, String json, int expected) throws Exception {
        mvc.perform(post("/api/v1/workflows/" + id + "/replan").with(engineer()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().is(expected));
    }

    private JsonNode approveId(String id, long approvalId) throws Exception {
        return body(mvc.perform(post("/api/v1/workflows/" + id + "/approve").with(approver()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"approvalId\":" + approvalId + "}")).andExpect(status().isOk()).andReturn());
    }
}