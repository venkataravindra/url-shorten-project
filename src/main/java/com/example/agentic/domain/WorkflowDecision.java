package com.example.agentic.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "workflow_decisions", indexes = @Index(name = "idx_dec_wf", columnList = "workflow_id"))
@Getter
@Setter
public class WorkflowDecision {
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
    private int taskRevision;
    @Column(length = 300)
    private String title;
    @Column(columnDefinition = "TEXT")
    private String rationale;
    /** Comma separated ids of upstream decisions this one builds on (decision lineage). */
    @Column(length = 500)
    private String parentDecisionIds;
    private boolean superseded;
    @Column(length = 500)
    private String supersededReason;
    @Column(length = 100)
    private String decidedBy;
    private Instant decidedAt;
}