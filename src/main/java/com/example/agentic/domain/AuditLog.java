package com.example.agentic.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_wf", columnList = "workflow_id,occurredAt"),
        @Index(name = "idx_audit_type", columnList = "eventType")})
@Getter
@Setter
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id")
    private WorkflowExecution workflow;
    @Column(name = "workflow_id", insertable = false, updatable = false)
    private String workflowId;
    @Column(length = 50, nullable = false)
    private String eventType;
    @Column(length = 100)
    private String actor;
    @Column(columnDefinition = "TEXT")
    private String detail;
    private Instant occurredAt;
}