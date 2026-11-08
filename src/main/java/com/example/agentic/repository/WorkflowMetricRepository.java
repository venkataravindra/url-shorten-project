package com.example.agentic.repository;

import com.example.agentic.domain.WorkflowMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WorkflowMetricRepository extends JpaRepository<WorkflowMetric, Long> {
    List<WorkflowMetric> findByWorkflowIdOrderByIdAsc(String workflowId);

    @Query("select avg(m.metricValue) from WorkflowMetric m where m.name = :name")
    Double average(@Param("name") String name);

    @Query("select count(m) from WorkflowMetric m where m.name = :name")
    long countByName(@Param("name") String name);
}