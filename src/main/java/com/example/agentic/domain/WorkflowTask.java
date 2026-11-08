package com.example.agentic.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "workflow_tasks",
        uniqueConstraints = @UniqueConstraint(name = "uq_task_wf_key", columnNames = {"workflow_id", "nodeKey"}),
        indexes = @Index(name = "idx_task_wf", columnList = "workflow_id"))
@Getter
@Setter
public class WorkflowTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_id")
    private WorkflowExecution workflow;
    @Column(name = "workflow_id", insertable = false, updatable = false)
    private String workflowId;
    @Column(length = 64, nullable = false)
    private String nodeKey;
    @Column(length = 200)
    private String title;
    @Column(length = 40)
    private String agentId;
    @Enumerated(EnumType.STRING)
    @Column(length = 10, nullable = false)
    private NodeType nodeType;
    @Enumerated(EnumType.STRING)
    @Column(length = 40, nullable = false)
    private WorkflowStage stage;
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private TaskStatus status = TaskStatus.PENDING;
    @Column(length = 300)
    private String conditionExpr;
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private ActionType approvalType;
    @Column(length = 500)
    private String paramsJson;
    private int attempts;
    private int revision = 1;
    @Column(columnDefinition = "TEXT")
    private String failureReason;
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private FailureKind failureKind;
    @Column(columnDefinition = "TEXT")
    private String outputJson;
    private Instant startedAt;
    private Instant completedAt;
    private Instant updatedAt;
}