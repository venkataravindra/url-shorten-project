package com.example.agentic.orchestration;

import com.example.agentic.audit.AuditEventType;
import com.example.agentic.audit.AuditService;
import com.example.agentic.config.AgenticProperties;
import com.example.agentic.domain.SafeStopCategory;
import com.example.agentic.domain.WorkflowExecution;
import com.example.agentic.domain.WorkflowStatus;
import com.example.agentic.governance.SafeStopService;
import com.example.agentic.repository.WorkflowExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * After a restart, workflows persisted as RUNNING/RETRYING were interrupted mid-flight. Their in-flight tasks are
 * reset (agents are idempotent) and the workflow is safe-stopped so a human decides when to resume.
 * WAITING_FOR_APPROVAL workflows need no recovery: their state and pending approvals are already persisted.
 */
@Component
public class WorkflowRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(WorkflowRecoveryService.class);

    private final WorkflowExecutionRepository workflows;
    private final WorkflowStateStore store;
    private final SafeStopService safeStop;
    private final AuditService audit;
    private final WorkflowEngine engine;
    private final WorkflowScheduler scheduler;
    private final AgenticProperties props;

    public WorkflowRecoveryService(WorkflowExecutionRepository workflows, WorkflowStateStore store, SafeStopService safeStop,
                                   AuditService audit, WorkflowEngine engine, WorkflowScheduler scheduler, AgenticProperties props) {
        this.workflows = workflows;
        this.store = store;
        this.safeStop = safeStop;
        this.audit = audit;
        this.engine = engine;
        this.scheduler = scheduler;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        recoverInterruptedWorkflows();
    }

    public int recoverInterruptedWorkflows() {
        List<WorkflowExecution> interrupted = workflows.findByStatusIn(List.of(WorkflowStatus.RUNNING, WorkflowStatus.RETRYING));
        for (WorkflowExecution w : interrupted) {
            if (engine.isRunning(w.getId())) {
                continue;
            }
            int reset = store.resetInterruptedTasks(w.getId());
            safeStop.safeStop(w.getId(), SafeStopCategory.RESTART_INTERRUPTED,
                    "Application restarted while the workflow was " + w.getStatus() + "; " + reset + " tasks reset for re-execution", "recovery");
            audit.record(w.getId(), AuditEventType.WORKFLOW_RECOVERED, "recovery", Map.of("tasksReset", reset));
            log.warn("Recovered interrupted workflow {} ({} tasks reset)", w.getId(), reset);
            if (props.recovery().autoResume()) {
                String id = w.getId();
                scheduler.executor().submit(() -> {
                    safeStop.resume(id, "system", "auto-resume after restart");
                    engine.run(id);
                });
            }
        }
        return interrupted.size();
    }
}