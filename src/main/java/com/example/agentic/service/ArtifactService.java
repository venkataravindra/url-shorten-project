package com.example.agentic.service;

import com.example.agentic.agent.ArtifactDraft;
import com.example.agentic.audit.AuditEventType;
import com.example.agentic.audit.AuditService;
import com.example.agentic.domain.ArtifactStatus;
import com.example.agentic.domain.GeneratedArtifact;
import com.example.agentic.governance.SecurityValidator;
import com.example.agentic.governance.WorkspaceService;
import com.example.agentic.repository.GeneratedArtifactRepository;
import com.example.agentic.repository.WorkflowExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Versioned artifact store. New output is STAGED; the final approval ACCEPTS it; rollback REVERTS staged
 * versions and restores the previous version, so history is never silently overwritten.
 */
@Service
public class ArtifactService {
    private final GeneratedArtifactRepository repository;
    private final WorkflowExecutionRepository workflows;
    private final SecurityValidator validator;
    private final WorkspaceService workspace;
    private final AuditService audit;
    private final Clock clock;

    public ArtifactService(GeneratedArtifactRepository repository, WorkflowExecutionRepository workflows,
                           SecurityValidator validator, WorkspaceService workspace, AuditService audit, Clock clock) {
        this.repository = repository;
        this.workflows = workflows;
        this.validator = validator;
        this.workspace = workspace;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public GeneratedArtifact stage(String workflowId, String taskKey, ArtifactDraft draft) {
        validator.validateArtifactName(draft.name());
        validator.assertNoSecrets("artifact " + draft.name(), draft.content());
        int version = repository.maxVersion(workflowId, draft.name()) + 1;
        repository.findByWorkflowIdOrderByIdAsc(workflowId).stream()
                .filter(a -> a.getName().equals(draft.name())
                        && (a.getStatus() == ArtifactStatus.STAGED || a.getStatus() == ArtifactStatus.ACCEPTED))
                .forEach(a -> supersede(a));
        GeneratedArtifact a = new GeneratedArtifact();
        a.setWorkflow(workflows.getReferenceById(workflowId));
        a.setTaskKey(taskKey);
        a.setName(draft.name());
        a.setArtifactType(draft.type());
        a.setVersion(version);
        a.setStatus(ArtifactStatus.STAGED);
        a.setContent(draft.content());
        a.setSha256(sha256(draft.content()));
        a.setStoredPath(workspace.write(workflowId, version, draft.name(), draft.content()));
        a.setCreatedAt(Instant.now(clock));
        GeneratedArtifact saved = repository.save(a);
        audit.record(workflowId, AuditEventType.ARTIFACT_STAGED, "system",
                Map.of("name", draft.name(), "version", version, "task", taskKey));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<GeneratedArtifact> list(String workflowId) {
        return repository.findByWorkflowIdOrderByIdAsc(workflowId);
    }

    /** Artifacts currently in force (staged or accepted). */
    @Transactional(readOnly = true)
    public List<GeneratedArtifact> listActive(String workflowId) {
        return list(workflowId).stream()
                .filter(a -> a.getStatus() == ArtifactStatus.STAGED || a.getStatus() == ArtifactStatus.ACCEPTED).toList();
    }

    @Transactional
    public int acceptStaged(String workflowId, String actor) {
        List<GeneratedArtifact> staged = list(workflowId).stream().filter(a -> a.getStatus() == ArtifactStatus.STAGED).toList();
        staged.forEach(a -> a.setStatus(ArtifactStatus.ACCEPTED));
        if (!staged.isEmpty()) {
            audit.record(workflowId, AuditEventType.ARTIFACTS_ACCEPTED, actor, Map.of("count", staged.size()));
        }
        return staged.size();
    }

    /** Reverts all staged versions and restores the version each one superseded. */
    @Transactional
    public int revertStaged(String workflowId, String actor) {
        List<GeneratedArtifact> all = list(workflowId);
        List<GeneratedArtifact> staged = all.stream().filter(a -> a.getStatus() == ArtifactStatus.STAGED).toList();
        for (GeneratedArtifact s : staged) {
            s.setStatus(ArtifactStatus.REVERTED);
            all.stream().filter(p -> p.getName().equals(s.getName()) && p.getVersion() == s.getVersion() - 1
                            && p.getStatus() == ArtifactStatus.SUPERSEDED && p.getStatusBeforeSuperseded() != null)
                    .findFirst().ifPresent(p -> {
                        p.setStatus(p.getStatusBeforeSuperseded());
                        p.setStatusBeforeSuperseded(null);
                    });
        }
        audit.record(workflowId, AuditEventType.ARTIFACTS_REVERTED, actor, Map.of("count", staged.size()));
        return staged.size();
    }

    /** Marks artifacts produced by the given tasks as superseded (used by replanning). */
    @Transactional
    public int supersedeForTasks(String workflowId, Set<String> taskKeys) {
        List<GeneratedArtifact> hit = list(workflowId).stream()
                .filter(a -> taskKeys.contains(a.getTaskKey())
                        && (a.getStatus() == ArtifactStatus.STAGED || a.getStatus() == ArtifactStatus.ACCEPTED)).toList();
        hit.forEach(this::supersede);
        return hit.size();
    }

    private void supersede(GeneratedArtifact a) {
        a.setStatusBeforeSuperseded(a.getStatus());
        a.setStatus(ArtifactStatus.SUPERSEDED);
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}