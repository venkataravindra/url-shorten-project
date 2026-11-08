package com.example.agentic.repository;

import com.example.agentic.domain.WorkflowDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowDecisionRepository extends JpaRepository<WorkflowDecision, Long> {
    List<WorkflowDecision> findByWorkflowIdOrderByIdAsc(String workflowId);
}