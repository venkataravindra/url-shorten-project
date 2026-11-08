package com.example.agentic.repository;

import com.example.agentic.domain.AgentExecution;
import com.example.agentic.domain.AttemptStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AgentExecutionRepository extends JpaRepository<AgentExecution, Long> {
    List<AgentExecution> findByWorkflowIdOrderByIdAsc(String workflowId);

    @Query("select count(a) from AgentExecution a where a.attempt > 1")
    long countRetryAttempts();

    @Query("select count(a) from AgentExecution a where a.workflowId = :wf and a.attempt > 1")
    long countRetryAttempts(@org.springframework.data.repository.query.Param("wf") String workflowId);

    long countByStatus(AttemptStatus status);

    @Query("select a.agentId, count(a), avg(a.durationMs) from AgentExecution a group by a.agentId")
    List<Object[]> durationByAgent();
}