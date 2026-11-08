package com.example.agentic.service;

import com.example.agentic.agent.DecisionDraft;
import com.example.agentic.domain.WorkflowDecision;
import com.example.agentic.repository.WorkflowDecisionRepository;
import com.example.agentic.repository.WorkflowExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Decision lineage: each decision records which upstream decisions it builds on. Superseded, never deleted. */
@Service
public class DecisionService {
    private final WorkflowDecisionRepository repository;
    private final WorkflowExecutionRepository workflows;
    private final Clock clock;

    public DecisionService(WorkflowDecisionRepository repository, WorkflowExecutionRepository workflows, Clock clock) {
        this.repository = repository;
        this.workflows = workflows;
        this.clock = clock;
    }

    @Transactional
    public WorkflowDecision record(String workflowId, String taskKey, int revision, DecisionDraft draft,
                                   String decidedBy, List<Long> parentIds) {
        WorkflowDecision d = new WorkflowDecision();
        d.setWorkflow(workflows.getReferenceById(workflowId));
        d.setTaskKey(taskKey);
        d.setTaskRevision(revision);
        d.setTitle(draft.title());
        d.setRationale(draft.rationale());
        d.setDecidedBy(decidedBy);
        d.setParentDecisionIds(parentIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
        d.setDecidedAt(Instant.now(clock));
        return repository.save(d);
    }

    @Transactional(readOnly = true)
    public List<Long> currentDecisionIds(String workflowId, Collection<String> taskKeys) {
        return repository.findByWorkflowIdOrderByIdAsc(workflowId).stream()
                .filter(d -> !d.isSuperseded() && taskKeys.contains(d.getTaskKey()))
                .map(WorkflowDecision::getId).toList();
    }

    @Transactional
    public int supersedeForTasks(String workflowId, Set<String> taskKeys, String reason) {
        List<WorkflowDecision> hit = repository.findByWorkflowIdOrderByIdAsc(workflowId).stream()
                .filter(d -> !d.isSuperseded() && taskKeys.contains(d.getTaskKey())).toList();
        hit.forEach(d -> {
            d.setSuperseded(true);
            d.setSupersededReason(reason);
        });
        return hit.size();
    }

    @Transactional(readOnly = true)
    public List<WorkflowDecision> list(String workflowId) {
        return repository.findByWorkflowIdOrderByIdAsc(workflowId);
    }
}