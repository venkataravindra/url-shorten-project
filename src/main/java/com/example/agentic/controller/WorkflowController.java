package com.example.agentic.controller;

import com.example.agentic.dto.*;
import com.example.agentic.service.WorkflowService;
import com.example.agentic.urlshortener.dto.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/workflows")
@Tag(name = "Agentic workflows")
public class WorkflowController {
    private final WorkflowService service;

    public WorkflowController(WorkflowService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create a workflow from a software requirement (does not start it)")
    public ResponseEntity<WorkflowResponse> create(@Valid @RequestBody CreateWorkflowRequest request, Authentication auth) {
        WorkflowResponse created = service.create(request, auth.getName());
        return ResponseEntity.created(URI.create("/api/v1/workflows/" + created.id())).body(created);
    }

    @GetMapping
    public PagedResponse<WorkflowResponse> list(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @GetMapping("/{id}")
    public WorkflowResponse get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping("/{id}/tasks")
    @Operation(summary = "Tasks with dependency information; add includeOutput=true for stage outputs")
    public List<TaskResponse> tasks(@PathVariable String id, @RequestParam(defaultValue = "false") boolean includeOutput) {
        return service.tasks(id, includeOutput);
    }

    @PostMapping("/{id}/execute")
    @Operation(summary = "Start or resume execution; runs until completion, an approval gate, failure or safe-stop")
    public WorkflowResponse execute(@PathVariable String id, @Valid @RequestBody(required = false) ExecuteRequest body,
                                    Authentication auth) {
        boolean approver = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_APPROVER"));
        return service.execute(id, body == null ? null : body.acknowledgement(), auth.getName(), approver);
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a pending action (APPROVER role); resumes the workflow")
    public WorkflowResponse approve(@PathVariable String id, @Valid @RequestBody(required = false) ApprovalDecisionRequest body,
                                    Authentication auth) {
        return service.approve(id, body == null ? new ApprovalDecisionRequest(null, null) : body, auth.getName());
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject a pending action (APPROVER role); rolls the workflow back")
    public WorkflowResponse reject(@PathVariable String id, @Valid @RequestBody(required = false) ApprovalDecisionRequest body,
                                   Authentication auth) {
        return service.reject(id, body == null ? new ApprovalDecisionRequest(null, null) : body, auth.getName());
    }

    @PostMapping("/{id}/stop")
    @Operation(summary = "Safely stop execution")
    public WorkflowResponse stop(@PathVariable String id, @Valid @RequestBody StopRequest body, Authentication auth) {
        return service.stop(id, body.reason(), auth.getName());
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel the workflow permanently")
    public WorkflowResponse cancel(@PathVariable String id, @Valid @RequestBody(required = false) StopRequest body,
                                   Authentication auth) {
        return service.cancel(id, body == null ? null : body.reason(), auth.getName());
    }

    @PostMapping("/{id}/replan")
    @Operation(summary = "Controlled replanning after an upstream change; high-impact changes need approval")
    public ReplanResponse replan(@PathVariable String id, @Valid @RequestBody ReplanRequest body, Authentication auth) {
        return service.replan(id, body, auth.getName());
    }

    @GetMapping("/{id}/artifacts")
    public List<ArtifactResponse> artifacts(@PathVariable String id, @RequestParam(defaultValue = "false") boolean includeContent) {
        return service.artifacts(id, includeContent);
    }

    @GetMapping("/{id}/decisions")
    @Operation(summary = "Decision lineage")
    public List<DecisionResponse> decisions(@PathVariable String id) {
        return service.decisions(id);
    }

    @GetMapping("/{id}/approvals")
    public List<ApprovalResponse> approvals(@PathVariable String id) {
        return service.approvals(id);
    }

    @GetMapping("/{id}/summary")
    @Operation(summary = "Final engineering summary (available once the workflow completed)")
    public SummaryResponse summary(@PathVariable String id) {
        return service.summary(id);
    }
}