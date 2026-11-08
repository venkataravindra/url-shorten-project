package com.example.agentic.orchestration;

import com.example.agentic.audit.AuditEventType;
import com.example.agentic.audit.AuditService;
import com.example.agentic.domain.WorkflowStatus;
import com.example.agentic.governance.ApprovalService;
import com.example.agentic.service.ArtifactService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Safe, reversible rollback: staged artifact versions are reverted (previous versions restored) and open
 * approvals are withdrawn. No file or system state outside the artifact store is touched.
 */
@Service
public class RollbackService {
    private final ArtifactService artifacts;
    private final ApprovalService approvals;
    private final WorkflowStateStore store;
    private final AuditService audit;

    public RollbackService(ArtifactService artifacts, ApprovalService approvals, WorkflowStateStore store, AuditService audit) {
        this.artifacts = artifacts;
        this.approvals = approvals;
        this.store = store;
        this.audit = audit;
    }

    @Transactional
    public void rollback(String workflowId, String reason, String actor) {
        int reverted = artifacts.revertStaged(workflowId, actor);
        int withdrawn = approvals.supersedeAllPending(workflowId, "workflow rolled back");
        store.setFailure(workflowId, reason);
        store.transition(workflowId, WorkflowStatus.ROLLED_BACK);
        audit.record(workflowId, AuditEventType.ROLLED_BACK, actor,
                Map.of("reason", reason, "artifactsReverted", reverted, "approvalsWithdrawn", withdrawn));
    }
}