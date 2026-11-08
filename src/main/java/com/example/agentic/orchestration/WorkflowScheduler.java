package com.example.agentic.orchestration;

import com.example.agentic.config.AgenticProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded worker pool. Independent DAG branches run concurrently, up to agentic.orchestration.parallelism. */
@Component
public class WorkflowScheduler implements DisposableBean {
    private final ExecutorService pool;

    public WorkflowScheduler(AgenticProperties props) {
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(Math.max(1, props.orchestration().parallelism()), r -> {
            Thread t = new Thread(r, "wf-agent-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public <T> CompletionService<T> newCompletionService() {
        return new ExecutorCompletionService<>(pool);
    }

    public ExecutorService executor() { return pool; }

    @Override
    public void destroy() {
        pool.shutdownNow();
    }
}