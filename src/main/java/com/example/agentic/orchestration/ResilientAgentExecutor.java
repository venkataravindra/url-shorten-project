package com.example.agentic.orchestration;

import com.example.agentic.agent.Agent;
import com.example.agentic.agent.AgentContext;
import com.example.agentic.agent.AgentResult;
import com.example.agentic.audit.AuditEventType;
import com.example.agentic.audit.AuditService;
import com.example.agentic.config.AgenticProperties;
import com.example.agentic.domain.AttemptStatus;
import com.example.agentic.domain.FailureKind;
import com.example.agentic.llm.LLMProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Runs one agent task with: retry only for transient failures, exponential back-off, a degraded fallback
 * provider once retries are exhausted, and a persisted AgentExecution row per attempt.
 */
@Component
public class ResilientAgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(ResilientAgentExecutor.class);

    private final AgentRegistry registry;
    private final LLMProvider primary;
    private final LLMProvider fallback;
    private final AgenticProperties.Retry retry;
    private final FailureClassifier classifier;
    private final WorkflowStateStore store;
    private final AuditService audit;
    private final Sleeper sleeper;

    public ResilientAgentExecutor(AgentRegistry registry, LLMProvider primary,
                                  @Qualifier("fallbackLlmProvider") LLMProvider fallback, AgenticProperties props,
                                  FailureClassifier classifier, WorkflowStateStore store, AuditService audit, Sleeper sleeper) {
        this.registry = registry;
        this.primary = primary;
        this.fallback = fallback;
        this.retry = props.retry();
        this.classifier = classifier;
        this.store = store;
        this.audit = audit;
        this.sleeper = sleeper;
    }

    public long backoffMillis(int failedAttempt) {
        double delay = retry.initialBackoffMs() * Math.pow(retry.multiplier(), failedAttempt - 1);
        return (long) Math.min(delay, retry.maxBackoffMs());
    }

    public ExecutionOutcome execute(String workflowId, TaskSnapshot task, WorkflowContext context) {
        Agent agent = registry.get(task.agentId());
        int maxAttempts = retry.maxRetries() + 1;
        RuntimeException lastTransient = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                store.markTaskRunningAfterRetry(workflowId, task.key());
            }
            Instant started = Instant.now();
            long t0 = System.nanoTime();
            try {
                AgentResult result = agent.execute(new AgentContext(workflowId, task.key(), task.params(), context, primary, attempt));
                store.recordAttempt(workflowId, task.key(), task.agentId(), attempt, task.revision(), AttemptStatus.SUCCESS,
                        result.provider(), result.degraded(), null, null, started, millisSince(t0));
                return new ExecutionOutcome(result, attempt, false);
            } catch (RuntimeException e) {
                FailureKind kind = classifier.classify(e);
                store.recordAttempt(workflowId, task.key(), task.agentId(), attempt, task.revision(),
                        kind == FailureKind.TRANSIENT ? AttemptStatus.FAILED_TRANSIENT : AttemptStatus.FAILED_PERMANENT,
                        primary.name(), false, kind, e.getMessage(), started, millisSince(t0));
                if (kind != FailureKind.TRANSIENT) {
                    throw new TaskExecutionException(kind, e, attempt, false);
                }
                lastTransient = e;
                if (attempt < maxAttempts) {
                    long delay = backoffMillis(attempt);
                    store.markTaskRetrying(workflowId, task.key(), attempt, e.getMessage());
                    audit.record(workflowId, AuditEventType.TASK_RETRY_SCHEDULED, "engine",
                            Map.of("task", task.key(), "attempt", attempt, "backoffMs", delay, "reason", String.valueOf(e.getMessage())));
                    log.warn("Task {} attempt {} failed transiently, retrying in {} ms", task.key(), attempt, delay);
                    pause(delay, attempt);
                }
            }
        }
        return tryFallback(workflowId, task, context, agent, maxAttempts, lastTransient);
    }

    private ExecutionOutcome tryFallback(String workflowId, TaskSnapshot task, WorkflowContext context, Agent agent,
                                         int maxAttempts, RuntimeException lastTransient) {
        if (!retry.fallbackEnabled() || fallback == null) {
            throw new TaskExecutionException(FailureKind.TRANSIENT, lastTransient, maxAttempts, true);
        }
        int attempt = maxAttempts + 1;
        store.markTaskRunningAfterRetry(workflowId, task.key());
        Instant started = Instant.now();
        long t0 = System.nanoTime();
        try {
            AgentResult result = agent.execute(new AgentContext(workflowId, task.key(), task.params(), context, fallback, attempt));
            store.recordAttempt(workflowId, task.key(), task.agentId(), attempt, task.revision(), AttemptStatus.FALLBACK_SUCCESS,
                    result.provider(), true, null, null, started, millisSince(t0));
            audit.record(workflowId, AuditEventType.FALLBACK_USED, "engine",
                    Map.of("task", task.key(), "provider", result.provider(), "afterAttempts", maxAttempts));
            return new ExecutionOutcome(result, attempt, true);
        } catch (RuntimeException e) {
            FailureKind kind = classifier.classify(e);
            store.recordAttempt(workflowId, task.key(), task.agentId(), attempt, task.revision(),
                    kind == FailureKind.TRANSIENT ? AttemptStatus.FAILED_TRANSIENT : AttemptStatus.FAILED_PERMANENT,
                    fallback.name(), true, kind, e.getMessage(), started, millisSince(t0));
            if (kind != FailureKind.TRANSIENT) {
                throw new TaskExecutionException(kind, e, attempt, false);
            }
            throw new TaskExecutionException(FailureKind.TRANSIENT, lastTransient, attempt, true);
        }
    }

    private void pause(long delay, int attempt) {
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new TaskExecutionException(FailureKind.PERMANENT, ie, attempt, false);
        }
    }

    private static long millisSince(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}