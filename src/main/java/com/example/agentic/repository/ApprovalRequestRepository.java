package com.example.agentic.repository;

import com.example.agentic.domain.ApprovalRequest;
import com.example.agentic.domain.ApprovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, Long> {
    List<ApprovalRequest> findByWorkflowIdOrderByIdAsc(String workflowId);

    List<ApprovalRequest> findByWorkflowIdAndStatus(String workflowId, ApprovalStatus status);

    List<ApprovalRequest> findByStatusNot(ApprovalStatus status);
}