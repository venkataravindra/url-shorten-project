package com.example.agentic.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "agent_executions", indexes = @Index(name = "idx_agent_wf", columnList = "workflow_id"))
@Getter
@Setter
public class AgentExecution {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_id")
    private WorkflowExecution workflow;
    @Column(name = "workflow_id", insertable = false, updatable = false)
    private String workflowId;
    @Column(length = 64)
    private String taskKey;
    @Column(length = 40)
    private String agentId;
    private int attempt;
    private int taskRevision;
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AttemptStatus status;
    @Column(length = 60)
    private String provider;
    private boolean degraded;
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private FailureKind failureKind;
    @Column(columnDefinition = "TEXT")
    private String errorMessage;
    private Instant startedAt;
    private long durationMs;
}