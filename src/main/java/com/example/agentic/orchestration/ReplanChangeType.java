package com.example.agentic.orchestration;

public enum ReplanChangeType {
    REQUIREMENT_CHANGE, ARCHITECTURE_CHANGE, DESIGN_DETAIL_CHANGE, TEST_STRATEGY_CHANGE, DOCUMENTATION_CHANGE;

    /** High-impact changes need a human approval before they are applied. */
    public boolean highImpact() {
        return this == REQUIREMENT_CHANGE || this == ARCHITECTURE_CHANGE;
    }
}
