package com.example.agentic.orchestration;

import com.example.agentic.domain.FailureKind;
import com.example.agentic.exception.ValidationFailureException;

public class TaskExecutionException extends RuntimeException {
    private final FailureKind kind;
    private final int attemptsUsed;
    private final boolean retriesExhausted;

    public TaskExecutionException(FailureKind kind, Throwable cause, int attemptsUsed, boolean retriesExhausted) {
        super(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), cause);
        this.kind = kind;
        this.attemptsUsed = attemptsUsed;
        this.retriesExhausted = retriesExhausted;
    }

    public FailureKind kind() { return kind; }
    public int attemptsUsed() { return attemptsUsed; }
    public boolean retriesExhausted() { return retriesExhausted; }

    public boolean critical() {
        return getCause() instanceof ValidationFailureException v && v.isCritical();
    }
}