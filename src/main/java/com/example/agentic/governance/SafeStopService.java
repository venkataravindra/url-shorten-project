package com.example.agentic.governance;

import com.example.agentic.audit.AuditEventType;
import com.example.agentic.audit.AuditService;
import com.example.agentic.domain.SafeStopCategory;
import com.example.agentic.domain.WorkflowExecution;
import com.example.agentic.domain.WorkflowStatus;
import com.example.agentic.exception.InvalidRequestException;
import com.example.agentic.exception.InvalidWorkflowStateException;
import com.example.agentic.orchestration.WorkflowStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Halts a workflow, persists why, and refuses to continue until a human acknowledges the stop. */
@Service
public class SafeStopService {
    private static final Logger log = LoggerFactory.getLogger(SafeStopService.class);
    private final WorkflowStateStore store;
    private final AuditService audit;

    public SafeStopService(WorkflowStateStore store, AuditService audit) {
        this.store = store;
        this.audit = audit;
    }

    public void safeStop(String workflowId, SafeStopCategory category, String reason, String actor) {
        WorkflowStatus current = store.status(workflowId);
        if (current.isTerminal() || current == WorkflowStatus.SAFE_STOPPED) {
            return;
        }
        String safeReason = SecretRedactor.redact(reason);
        store.setSafeStop(workflowId, category, safeReason);
        store.transition(workflowId, WorkflowStatus.SAFE_STOPPED);
        audit.record(workflowId, AuditEventType.SAFE_STOPPED, actor, Map.of("category", category.name(), "reason", safeReason));
        log.warn("Workflow {} SAFE-STOPPED category={} reason={}", workflowId, category, safeReason);
    }

    /** Moves SAFE_STOPPED back to CREATED (ready) after an explicit human acknowledgement. */
    public void resume(String workflowId, String operator, String acknowledgement) {
        WorkflowExecution w = store.get(workflowId);
        if (w.getStatus() != WorkflowStatus.SAFE_STOPPED) {
            throw new InvalidWorkflowStateException("Workflow is " + w.getStatus() + ", not SAFE_STOPPED");
        }
        if (acknowledgement == null || acknowledgement.isBlank()) {
            throw new InvalidRequestException("Resuming a safe-stopped workflow requires an acknowledgement of the stop reason");
        }
        store.resetInterruptedTasks(workflowId);
        store.clearSafeStop(workflowId);
        store.transition(workflowId, WorkflowStatus.CREATED);
        audit.record(workflowId, AuditEventType.SAFE_STOP_RESUMED, operator,
                Map.of("previousCategory", String.valueOf(w.getSafeStopCategory()), "acknowledgement", acknowledgement));
    }
}