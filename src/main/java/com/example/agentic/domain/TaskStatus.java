package com.example.agentic.domain;

public enum TaskStatus {
    PENDING, RUNNING, RETRYING, WAITING_APPROVAL, COMPLETED, FAILED, SKIPPED,
    /** Marked by replanning: must be re-executed once its upstream tasks are done again. */
    STALE;

    public boolean isSatisfied() { return this == COMPLETED || this == SKIPPED; }
    public boolean isRunnable() { return this == PENDING || this == STALE; }
}