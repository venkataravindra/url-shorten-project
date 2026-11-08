package com.example.agentic.workflow;

import com.example.agentic.IntegrationTestBase;
import com.example.agentic.orchestration.WorkflowRecoveryService;
import com.example.agentic.orchestration.WorkflowStateStore;
import com.example.agentic.domain.WorkflowStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs with the fallback provider disabled so that exhausted retries must end in a safe-stop. */
@TestPropertySource(properties = {"agentic.retry.fallback-enabled=false", "spring.datasource.url=jdbc:h2:mem:resilience;DB_CLOSE_DELAY=-1"})
class ResilienceIntegrationTest extends IntegrationTestBase {
    @Autowired WorkflowStateStore store;
    @Autowired WorkflowRecoveryService recovery;

    private static String st(JsonNode n) { return n.get("status").asText(); }

    @Test
    void maxRetriesSafeStopThenHumanResumeAndMttr() throws Exception {
        mvc.perform(post("/api/v1/admin/fault-injection").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("operation", "DECOMPOSE_TASKS", "mode", "TIMEOUT", "times", 20, "includeFallback", true))))
                .andExpect(status().isOk());
        String id = createWorkflow(GREENFIELD);
        JsonNode stopped = execute(id);
        assertThat(st(stopped)).isEqualTo("SAFE_STOPPED");
        assertThat(stopped.get("safeStopCategory").asText()).isEqualTo("MAX_RETRIES_EXCEEDED");
        assertThat(stopped.get("safeStopReason").asText()).contains("after 4 attempts");
        JsonNode planning = tasks(id).get("planning");
        assertThat(st(planning)).isEqualTo("FAILED");
        assertThat(planning.get("failureKind").asText()).isEqualTo("TRANSIENT");
        assertThat(planning.get("attempts").asInt()).isEqualTo(4);
        assertThat(getJson("/api/v1/metrics/workflows/" + id).get("retryAttempts").asLong()).isEqualTo(3);
        assertThat(getJson("/api/v1/metrics/summary").get("safeStopCount").asLong()).isGreaterThanOrEqualTo(1);

        // the provider recovers; a human acknowledges and resumes
        mvc.perform(delete("/api/v1/admin/fault-injection").with(admin())).andExpect(status().isOk());
        JsonNode resumed = body(mvc.perform(post("/api/v1/workflows/" + id + "/execute").with(approver())
                .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledgement\":\"provider outage ended\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(st(resumed)).isEqualTo("WAITING_FOR_APPROVAL");
        assertThat(st(approve(id))).isEqualTo("COMPLETED");

        JsonNode summary = getJson("/api/v1/metrics/summary");
        assertThat(summary.get("mttrSamples").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(summary.get("mttrMs").asDouble()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void operatorStopRequiresAnApproverToResume() throws Exception {
        String id = createWorkflow(GREENFIELD);
        JsonNode stopped = body(mvc.perform(post("/api/v1/workflows/" + id + "/stop").with(engineer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"maintenance window\"}")).andExpect(status().isOk()).andReturn());
        assertThat(st(stopped)).isEqualTo("SAFE_STOPPED");
        assertThat(stopped.get("safeStopCategory").asText()).isEqualTo("OPERATOR_REQUEST");
        mvc.perform(post("/api/v1/workflows/" + id + "/execute").with(engineer())).andExpect(status().isForbidden());
        JsonNode resumed = body(mvc.perform(post("/api/v1/workflows/" + id + "/execute").with(approver())
                .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledgement\":\"window over\"}")).andExpect(status().isOk()).andReturn());
        assertThat(st(resumed)).isEqualTo("WAITING_FOR_APPROVAL");
    }

    @Test
    void interruptedWorkflowsAreSafeStoppedByRecoveryAndCanBeResumed() throws Exception {
        String id = createWorkflow(GREENFIELD);
        store.transition(id, WorkflowStatus.RUNNING);   // simulate a crash mid-flight
        store.startTask(id, "requirements");
        assertThat(recovery.recoverInterruptedWorkflows()).isGreaterThanOrEqualTo(1);

        JsonNode w = workflow(id);
        assertThat(st(w)).isEqualTo("SAFE_STOPPED");
        assertThat(w.get("safeStopCategory").asText()).isEqualTo("RESTART_INTERRUPTED");
        assertThat(st(tasks(id).get("requirements"))).isEqualTo("PENDING");
        assertThat(getJson("/api/v1/audit/workflows/" + id).toString()).contains("WORKFLOW_RECOVERED");
        JsonNode resumed = body(mvc.perform(post("/api/v1/workflows/" + id + "/execute").with(approver())
                .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledgement\":\"restart acknowledged\"}")).andExpect(status().isOk()).andReturn());
        assertThat(st(resumed)).isEqualTo("WAITING_FOR_APPROVAL");
    }
}