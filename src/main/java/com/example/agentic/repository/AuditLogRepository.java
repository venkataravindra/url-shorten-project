package com.example.agentic.repository;

import com.example.agentic.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByWorkflowIdOrderByIdAsc(String workflowId);

    long countByEventType(String eventType);

    List<AuditLog> findByEventTypeInOrderByIdAsc(java.util.Collection<String> types);
}