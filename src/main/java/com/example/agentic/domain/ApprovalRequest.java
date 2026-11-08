package com.example.agentic.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "approval_requests", indexes = {
        @Index(name = "idx_appr_wf", columnList = "workflow_id"),
        @Index(name = "idx_appr_status", columnList = "status")})
@Getter
@Setter
public class ApprovalRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_id")
    private WorkflowExecution workflow;
    @Column(name = "workflow_id", insertable = false, updatable = false)
    private String workflowId;
    /** Gate task this request belongs to; null for replan approvals. */
    @Column(length = 64)
    private String taskKey;
    @Enumerated(EnumType.STRING)
    @Column(length = 40, nullable = false)
    private ActionType primaryType;
    /** Comma separated ActionType names covered by this approval. */
    @Column(length = 600)
    private String actionTypes;
    @Column(columnDefinition = "TEXT")
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(length = 15, nullable = false)
    private ApprovalStatus status = ApprovalStatus.PENDING;
    @Column(length = 100)
    private String requestedBy;
    private Instant requestedAt;
    @Column(length = 100)
    private String decidedBy;
    private Instant decidedAt;
    @Column(length = 1000)
    private String decisionComment;
    /** Replan proposal or other structured context. */
    @Column(columnDefinition = "TEXT")
    private String payloadJson;
}