package com.example.agentic.controller;

import com.example.agentic.audit.AuditEvent;
import com.example.agentic.audit.AuditService;
import com.example.agentic.metrics.MetricsService;
import com.example.agentic.metrics.MetricsSummary;
import com.example.agentic.metrics.WorkflowMetrics;
import com.example.agentic.orchestration.WorkflowStateStore;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Observability")
public class MetricsController {
    private final MetricsService metrics;
    private final AuditService audit;
    private final WorkflowStateStore store;

    public MetricsController(MetricsService metrics, AuditService audit, WorkflowStateStore store) {
        this.metrics = metrics;
        this.audit = audit;
        this.store = store;
    }

    @GetMapping("/metrics/summary")
    public MetricsSummary summary() {
        return metrics.summary();
    }

    @GetMapping("/metrics/workflows/{workflowId}")
    public WorkflowMetrics workflow(@PathVariable String workflowId) {
        return metrics.forWorkflow(workflowId);
    }

    @GetMapping("/audit/workflows/{workflowId}")
    public List<AuditEvent> audit(@PathVariable String workflowId) {
        store.get(workflowId);
        return audit.forWorkflow(workflowId);
    }
}