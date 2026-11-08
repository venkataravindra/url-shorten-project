package com.example.agentic.repository;

import com.example.agentic.domain.WorkflowDependency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowDependencyRepository extends JpaRepository<WorkflowDependency, Long> {
    List<WorkflowDependency> findByWorkflowId(String workflowId);

    boolean existsByWorkflowIdAndTaskKeyAndDependsOnKey(String workflowId, String taskKey, String dependsOnKey);
}