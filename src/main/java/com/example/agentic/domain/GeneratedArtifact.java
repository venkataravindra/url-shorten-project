package com.example.agentic.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "generated_artifacts",
        uniqueConstraints = @UniqueConstraint(name = "uq_artifact_version", columnNames = {"workflow_id", "name", "version"}),
        indexes = @Index(name = "idx_art_wf", columnList = "workflow_id"))
@Getter
@Setter
public class GeneratedArtifact {
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
    @Column(length = 120, nullable = false)
    private String name;
    @Column(length = 30)
    private String artifactType;
    private int version;
    @Enumerated(EnumType.STRING)
    @Column(length = 15, nullable = false)
    private ArtifactStatus status = ArtifactStatus.STAGED;
    @Enumerated(EnumType.STRING)
    @Column(length = 15)
    private ArtifactStatus statusBeforeSuperseded;
    @Column(length = 300)
    private String storedPath;
    @Column(length = 64)
    private String sha256;
    @Column(columnDefinition = "TEXT")
    private String content;
    private Instant createdAt;
}