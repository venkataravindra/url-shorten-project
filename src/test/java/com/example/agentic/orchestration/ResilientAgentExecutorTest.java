package com.example.agentic.orchestration;

import com.example.agentic.agent.Agent;
import com.example.agentic.agent.AgentContext;
import com.example.agentic.agent.AgentResult;
import com.example.agentic.audit.AuditService;
import com.example.agentic.config.AgenticProperties;
import com.example.agentic.domain.*;
import com.example.agentic.exception.*;
import com.example.agentic.llm.LLMProvider;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;

import java.util.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResilientAgentExecutorTest {
    private final WorkflowStateStore store = mock(WorkflowStateStore.class);
    private final AuditService audit = mock(AuditService.class);
    private final List<Long> sleeps = new ArrayList<>();
    private final LLMProvider primary = mock(LLMProvider.class);
    private final LLMProvider fallback = mock(LLMProvider.class);
    private final List<LLMProvider> providersUsed = new ArrayList<>();
    private final TaskSnapshot task = new TaskSnapshot("t", "title", NodeType.AGENT, "stub", WorkflowStage.IMPLEMENTATION,
            TaskStatus.RUNNING, 1, 0, null, null, Map.of(), Set.of());
    private final WorkflowContext context = new WorkflowContext("wf", "req", Map.of());

    private ResilientAgentExecutor executor(boolean fallbackEnabled, Function<Integer, RuntimeException> failureForAttempt) {
        when(primary.name()).thenReturn("primary");
        when(fallback.name()).thenReturn("fallback");
        Agent agent = new Agent() {
            int calls;
            public String id() { return "stub"; }
            public AgentResult execute(AgentContext ctx) {
                providersUsed.add(ctx.attempt() > 0 ? null : null);
                calls++;
                RuntimeException e = failureForAttempt.apply(calls);
                if (e != null) throw e;
                return new AgentResult(Map.of("ok", true), List.of(), List.of(), false, "p");
            }
        };
        var props = new AgenticProperties(new AgenticProperties.Orchestration(2),
                new AgenticProperties.Retry(3, 100, 2.0, 500, fallbackEnabled),
                new AgenticProperties.Llm("mock", new AgenticProperties.Mock(0, 0, 1, true),
                        new AgenticProperties.OpenAi(false, "m", "u", "")),
                new AgenticProperties.Workspace("x"), new AgenticProperties.Recovery(false), new AgenticProperties.Security(List.of()));
        return new ResilientAgentExecutor(new AgentRegistry(List.of(agent)), primary, fallback, props, new FailureClassifier(),
                store, audit, sleeps::add);
    }

    @Test
    void succeedsFirstTimeWithoutRetry() {
        ExecutionOutcome o = executor(true, n -> null).execute("wf", task, context);
        assertThat(o.attempts()).isEqualTo(1);
        assertThat(sleeps).isEmpty();
        verify(store).recordAttempt(eq("wf"), eq("t"), eq("stub"), eq(1), eq(1), eq(AttemptStatus.SUCCESS), any(), eq(false), any(), any(), any(), anyLong());
    }

    @Test
    void retriesTransientFailuresWithExponentialBackoff() {
        ExecutionOutcome o = executor(true, n -> n <= 2 ? new LLMTimeoutException("slow") : null).execute("wf", task, context);
        assertThat(o.attempts()).isEqualTo(3);
        assertThat(o.fallbackUsed()).isFalse();
        assertThat(sleeps).containsExactly(100L, 200L);
        verify(store, times(2)).markTaskRetrying(eq("wf"), eq("t"), anyInt(), anyString());
        verify(store, times(2)).recordAttempt(any(), any(), any(), anyInt(), anyInt(), eq(AttemptStatus.FAILED_TRANSIENT), any(), anyBoolean(), eq(FailureKind.TRANSIENT), any(), any(), anyLong());
    }

    @Test
    void backoffIsCappedAtTheConfiguredMaximum() {
        ResilientAgentExecutor e = executor(true, n -> null);
        assertThat(e.backoffMillis(1)).isEqualTo(100);
        assertThat(e.backoffMillis(2)).isEqualTo(200);
        assertThat(e.backoffMillis(3)).isEqualTo(400);
        assertThat(e.backoffMillis(4)).isEqualTo(500);
        assertThat(e.backoffMillis(10)).isEqualTo(500);
    }

    @Test
    void databaseTransientFailuresAreRetried() {
        ExecutionOutcome o = executor(true, n -> n == 1 ? new TransientDataAccessResourceException("conn reset") : null).execute("wf", task, context);
        assertThat(o.attempts()).isEqualTo(2);
    }

    @Test
    void validationFailuresAreNeverRetried() {
        var ex = executor(true, n -> new ValidationFailureException("bad output"));
        assertThatThrownBy(() -> ex.execute("wf", task, context)).isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> {
                    TaskExecutionException t = (TaskExecutionException) e;
                    assertThat(t.kind()).isEqualTo(FailureKind.VALIDATION);
                    assertThat(t.attemptsUsed()).isEqualTo(1);
                    assertThat(t.retriesExhausted()).isFalse();
                });
        assertThat(sleeps).isEmpty();
    }

    @Test
    void policyViolationsAreNeverRetried() {
        var ex = executor(true, n -> new SecurityConcernException("leak"));
        assertThatThrownBy(() -> ex.execute("wf", task, context)).isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).kind()).isEqualTo(FailureKind.POLICY));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void unknownErrorsArePermanentAndNotRetried() {
        var ex = executor(true, n -> new IllegalStateException("boom"));
        assertThatThrownBy(() -> ex.execute("wf", task, context))
                .satisfies(e -> assertThat(((TaskExecutionException) e).kind()).isEqualTo(FailureKind.PERMANENT));
    }

    @Test
    void exhaustedRetriesWithoutFallbackAreReportedAsExhausted() {
        var ex = executor(false, n -> new LLMTimeoutException("always"));
        assertThatThrownBy(() -> ex.execute("wf", task, context)).isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> {
                    TaskExecutionException t = (TaskExecutionException) e;
                    assertThat(t.kind()).isEqualTo(FailureKind.TRANSIENT);
                    assertThat(t.retriesExhausted()).isTrue();
                    assertThat(t.attemptsUsed()).isEqualTo(4);
                });
        assertThat(sleeps).containsExactly(100L, 200L, 400L);
    }

    @Test
    void fallbackProviderIsUsedAfterRetriesAreExhausted() {
        ExecutionOutcome o = executor(true, n -> n <= 4 ? new LLMTimeoutException("down") : null).execute("wf", task, context);
        assertThat(o.fallbackUsed()).isTrue();
        assertThat(o.attempts()).isEqualTo(5);
        verify(audit).record(eq("wf"), eq(com.example.agentic.audit.AuditEventType.FALLBACK_USED), any(), anyMap());
        verify(store).recordAttempt(any(), any(), any(), eq(5), anyInt(), eq(AttemptStatus.FALLBACK_SUCCESS), any(), eq(true), any(), any(), any(), anyLong());
    }

    @Test
    void failingFallbackStillEndsAsExhaustedTransientFailure() {
        var ex = executor(true, n -> new LLMTimeoutException("down"));
        assertThatThrownBy(() -> ex.execute("wf", task, context))
                .satisfies(e -> assertThat(((TaskExecutionException) e).retriesExhausted()).isTrue());
    }
}