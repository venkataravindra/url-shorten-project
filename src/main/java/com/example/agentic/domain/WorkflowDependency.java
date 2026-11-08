package com.example.agentic.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "workflow_dependencies",
        uniqueConstraints = @UniqueConstraint(name = "uq_dep", columnNames = {"workflow_id", "taskKey", "dependsOnKey"}),
        indexes = @Index(name = "idx_dep_wf", columnList = "workflow_id"))
@Getter
@Setter
public class WorkflowDependency {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_id")
    private WorkflowExecution workflow;
    @Column(name = "workflow_id", insertable = false, updatable = false)
    private String workflowId;
    @Column(length = 64, nullable = false)
    private String taskKey;
    @Column(length = 64, nullable = false)
    private String dependsOnKey;
    /** Graph revision in which the edge was introduced (1 = original plan). */
    private int addedInRevision = 1;
}