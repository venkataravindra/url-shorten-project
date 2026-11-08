package com.example.agentic.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "workflow_metrics", indexes = @Index(name = "idx_metric_name", columnList = "name"))
@Getter
@Setter
public class WorkflowMetric {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id")
    private WorkflowExecution workflow;
    @Column(name = "workflow_id", insertable = false, updatable = false)
    private String workflowId;
    @Column(length = 60, nullable = false)
    private String name;
    private double metricValue;
    private Instant recordedAt;
}