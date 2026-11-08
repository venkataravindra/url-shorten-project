package com.example.agentic.repository;

import com.example.agentic.domain.TaskStatus;
import com.example.agentic.domain.WorkflowTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface WorkflowTaskRepository extends JpaRepository<WorkflowTask, Long> {
    List<WorkflowTask> findByWorkflowIdOrderByIdAsc(String workflowId);

    Optional<WorkflowTask> findByWorkflowIdAndNodeKey(String workflowId, String nodeKey);

    long countByStatus(TaskStatus status);

    @Query("select t.status, count(t) from WorkflowTask t group by t.status")
    List<Object[]> countGroupedByStatus();
}