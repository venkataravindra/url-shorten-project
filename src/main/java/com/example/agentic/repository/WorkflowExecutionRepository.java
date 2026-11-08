package com.example.agentic.repository;

import com.example.agentic.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, String> {

    /** Compare-and-set transition: succeeds only if the status is still what the caller observed. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update WorkflowExecution w set w.status = :to, w.updatedAt = :now where w.id = :id and w.status = :from")
    int compareAndSetStatus(@Param("id") String id, @Param("from") WorkflowStatus from,
                            @Param("to") WorkflowStatus to, @Param("now") Instant now);

    List<WorkflowExecution> findByStatusIn(Collection<WorkflowStatus> statuses);

    long countByStatus(WorkflowStatus status);

    @Query("select w.status, count(w) from WorkflowExecution w group by w.status")
    List<Object[]> countGroupedByStatus();
}