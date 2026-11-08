package com.example.agentic.workflow;

import com.example.agentic.AgenticApplication;
import com.example.agentic.domain.WorkflowStatus;
import com.example.agentic.dto.ApprovalDecisionRequest;
import com.example.agentic.dto.CreateWorkflowRequest;
import com.example.agentic.dto.WorkflowResponse;
import com.example.agentic.orchestration.WorkflowStateStore;
import com.example.agentic.service.WorkflowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;

import static com.example.agentic.IntegrationTestBase.AMBIGUOUS;
import static com.example.agentic.IntegrationTestBase.GREENFIELD;
import static org.assertj.core.api.Assertions.assertThat;

/** A real restart: two application contexts over the same file-based H2 database. */
class RestartRecoveryTest {
    @TempDir Path dir;

    private ConfigurableApplicationContext start() {
        String db = dir.resolve("db").toString().replace('\\', '/');
        String ws = dir.resolve("ws").toString().replace('\\', '/');
        return new SpringApplicationBuilder(AgenticApplication.class).profiles("test").run(
                "--server.port=0", "--spring.datasource.url=jdbc:h2:file:" + db, "--agentic.workspace.root=" + ws,
                "--agentic.llm.mock.delay-ms=0", "--spring.main.banner-mode=off");
    }

    @Test
    void workflowStateApprovalsAndInterruptedWorkSurviveARestart() {
        String waitingId;
        String interruptedId;
        try (ConfigurableApplicationContext first = start()) {
            WorkflowService service = first.getBean(WorkflowService.class);
            waitingId = service.create(new CreateWorkflowRequest(GREENFIELD, "waiting"), "it").id();
            assertThat(service.execute(waitingId, null, "it", false).status()).isEqualTo("WAITING_FOR_APPROVAL");
            interruptedId = service.create(new CreateWorkflowRequest(AMBIGUOUS, "interrupted"), "it").id();
            WorkflowStateStore store = first.getBean(WorkflowStateStore.class);
            store.transition(interruptedId, WorkflowStatus.RUNNING);
            store.startTask(interruptedId, "requirements");
        }
        try (ConfigurableApplicationContext second = start()) {
            WorkflowService service = second.getBean(WorkflowService.class);

            WorkflowResponse waiting = service.get(waitingId);
            assertThat(waiting.status()).isEqualTo("WAITING_FOR_APPROVAL");
            assertThat(waiting.pendingApprovals()).hasSize(1);
            assertThat(waiting.taskCounts().get("COMPLETED")).isGreaterThanOrEqualTo(8);

            WorkflowResponse interrupted = service.get(interruptedId);
            assertThat(interrupted.status()).isEqualTo("SAFE_STOPPED");
            assertThat(interrupted.safeStopCategory()).isEqualTo("RESTART_INTERRUPTED");

            assertThat(service.approve(waitingId, new ApprovalDecisionRequest(null, "after restart"), "approver").status())
                    .isEqualTo("COMPLETED");
            assertThat(service.execute(interruptedId, "restart acknowledged", "approver", true).status())
                    .isEqualTo("WAITING_FOR_APPROVAL");
        }
    }
}