package com.example.agentic.domain;

import java.util.EnumSet;
import java.util.Set;

public enum WorkflowStatus {
    CREATED, RUNNING, WAITING_FOR_APPROVAL, COMPLETED, FAILED, RETRYING, ROLLED_BACK, CANCELLED, SAFE_STOPPED;

    /** Statuses from which no further execution is possible without a replan. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == ROLLED_BACK || this == CANCELLED;
    }

    public Set<WorkflowStatus> allowedTargets() {
        return switch (this) {
            case CREATED -> EnumSet.of(RUNNING, WAITING_FOR_APPROVAL, SAFE_STOPPED, CANCELLED);
            case RUNNING -> EnumSet.of(WAITING_FOR_APPROVAL, COMPLETED, FAILED, RETRYING, SAFE_STOPPED, ROLLED_BACK, CANCELLED);
            case RETRYING -> EnumSet.of(RUNNING, WAITING_FOR_APPROVAL, COMPLETED, FAILED, SAFE_STOPPED, CANCELLED);
            case WAITING_FOR_APPROVAL -> EnumSet.of(RUNNING, CREATED, COMPLETED, FAILED, SAFE_STOPPED, ROLLED_BACK, CANCELLED);
            case SAFE_STOPPED -> EnumSet.of(RUNNING, CREATED, WAITING_FOR_APPROVAL, ROLLED_BACK, CANCELLED);
            case COMPLETED -> EnumSet.of(CREATED, WAITING_FOR_APPROVAL);
            case FAILED -> EnumSet.of(CREATED, WAITING_FOR_APPROVAL, CANCELLED);
            case ROLLED_BACK, CANCELLED -> EnumSet.noneOf(WorkflowStatus.class);
        };
    }

    public boolean canTransitionTo(WorkflowStatus target) {
        return this == target || allowedTargets().contains(target);
    }
}