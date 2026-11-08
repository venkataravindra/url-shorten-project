package com.example.agentic.governance;

import com.example.agentic.domain.ActionType;
import com.example.agentic.exception.PolicyViolationException;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/** Central rulebook mapping an action to ALLOW / REQUIRE_APPROVAL / DENY. */
@Component
public class PolicyEngine {
    private static final Set<ActionType> FORBIDDEN =
            EnumSet.of(ActionType.SHELL_COMMAND_EXECUTION, ActionType.GENERATED_CODE_EXECUTION);

    public PolicyDecision evaluate(ActionType action) {
        if (FORBIDDEN.contains(action)) {
            return new PolicyDecision(PolicyDecision.Outcome.DENY, action + " is never permitted on this platform");
        }
        return new PolicyDecision(PolicyDecision.Outcome.REQUIRE_APPROVAL, action + " requires explicit human approval");
    }

    public boolean requiresApproval(ActionType action) {
        return evaluate(action).outcome() == PolicyDecision.Outcome.REQUIRE_APPROVAL;
    }

    public void assertNotForbidden(ActionType action) {
        PolicyDecision d = evaluate(action);
        if (d.outcome() == PolicyDecision.Outcome.DENY) {
            throw new PolicyViolationException(d.reason());
        }
    }
}