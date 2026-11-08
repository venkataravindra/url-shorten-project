package com.example.agentic.metrics;

import com.example.agentic.domain.WorkflowMetric;
import com.example.agentic.repository.WorkflowExecutionRepository;
import com.example.agentic.repository.WorkflowMetricRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** Persists point-in-time measurements (for example end-to-end latency) at the moment they occur. */
@Component
public class MetricsRecorder {
    public static final String E2E_LATENCY_MS = "WORKFLOW_E2E_LATENCY_MS";

    private final WorkflowMetricRepository repository;
    private final WorkflowExecutionRepository workflows;
    private final Clock clock;

    public MetricsRecorder(WorkflowMetricRepository repository, WorkflowExecutionRepository workflows, Clock clock) {
        this.repository = repository;
        this.workflows = workflows;
        this.clock = clock;
    }

    @Transactional
    public void record(String workflowId, String name, double value) {
        WorkflowMetric m = new WorkflowMetric();
        if (workflowId != null) {
            m.setWorkflow(workflows.getReferenceById(workflowId));
        }
        m.setName(name);
        m.setMetricValue(value);
        m.setRecordedAt(Instant.now(clock));
        repository.save(m);
    }
}