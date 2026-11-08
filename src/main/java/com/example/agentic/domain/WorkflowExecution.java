package com.example.agentic.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "workflow_executions", indexes = {
        @Index(name = "idx_wf_status", columnList = "status"),
        @Index(name = "idx_wf_created", columnList = "createdAt")})
@Getter
@Setter
public class WorkflowExecution {
    @Id
    @Column(length = 36)
    private String id;
    @Column(length = 200)
    private String title;
    @Column(columnDefinition = "TEXT", nullable = false)
    private String requirement;
    private int requirementVersion = 1;
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private ScenarioType scenarioType = ScenarioType.UNCLASSIFIED;
    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private WorkflowStatus status = WorkflowStatus.CREATED;
    @Column(columnDefinition = "TEXT")
    private String safeStopReason;
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private SafeStopCategory safeStopCategory;
    @Column(columnDefinition = "TEXT")
    private String failureReason;
    @Column(length = 100)
    private String createdBy;
    private int resumeCount;
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private Instant updatedAt;
}