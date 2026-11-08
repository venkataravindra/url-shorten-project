package com.example.agentic.audit;

import com.example.agentic.domain.AuditLog;
import com.example.agentic.governance.SecretRedactor;
import com.example.agentic.repository.AuditLogRepository;
import com.example.agentic.repository.WorkflowExecutionRepository;
import com.example.agentic.service.JsonSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Append-only audit trail. Details are redacted before being persisted or logged. */
@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;
    private final WorkflowExecutionRepository workflows;
    private final JsonSupport json;
    private final Clock clock;

    public AuditService(AuditLogRepository repository, WorkflowExecutionRepository workflows, JsonSupport json, Clock clock) {
        this.repository = repository;
        this.workflows = workflows;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public void record(String workflowId, AuditEventType type, String actor, Map<String, Object> detail) {
        AuditLog entry = new AuditLog();
        if (workflowId != null) {
            entry.setWorkflow(workflows.getReferenceById(workflowId));
        }
        entry.setEventType(type.name());
        entry.setActor(actor);
        String redacted = SecretRedactor.redact(json.toJson(detail == null ? Map.of() : detail));
        entry.setDetail(redacted);
        entry.setOccurredAt(Instant.now(clock));
        repository.save(entry);
        log.info("AUDIT {} actor={} detail={}", type, actor, redacted);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> forWorkflow(String workflowId) {
        return repository.findByWorkflowIdOrderByIdAsc(workflowId).stream()
                .map(a -> new AuditEvent(a.getId(), a.getWorkflowId(), a.getEventType(), a.getActor(),
                        json.toMap(a.getDetail()), a.getOccurredAt())).toList();
    }
}