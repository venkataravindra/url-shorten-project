package com.example.agentic.governance;

import com.example.agentic.audit.AuditEventType;
import com.example.agentic.audit.AuditService;
import com.example.agentic.domain.ActionType;
import com.example.agentic.exception.ApprovalRequiredException;
import com.example.agentic.exception.PolicyViolationException;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Gatekeeper called before any sensitive action: allowed only with a matching, still-valid human approval. */
@Service
public class ChangeControlService {
    private final PolicyEngine policy;
    private final ApprovalService approvals;
    private final AuditService audit;

    public ChangeControlService(PolicyEngine policy, ApprovalService approvals, AuditService audit) {
        this.policy = policy;
        this.approvals = approvals;
        this.audit = audit;
    }

    public void authorize(String workflowId, ActionType action) {
        PolicyDecision decision = policy.evaluate(action);
        switch (decision.outcome()) {
            case DENY -> {
                audit.record(workflowId, AuditEventType.POLICY_DENIED, "policy", Map.of("action", action.name()));
                throw new PolicyViolationException(decision.reason());
            }
            case REQUIRE_APPROVAL -> {
                if (!approvals.isApproved(workflowId, action)) {
                    audit.record(workflowId, AuditEventType.POLICY_DENIED, "policy",
                            Map.of("action", action.name(), "reason", "no valid approval"));
                    throw new ApprovalRequiredException(action + " requires a granted approval that is still valid");
                }
            }
            case ALLOW -> { }
        }
        audit.record(workflowId, AuditEventType.CHANGE_AUTHORIZED, "policy", Map.of("action", action.name()));
    }
}