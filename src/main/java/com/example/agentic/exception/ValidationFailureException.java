package com.example.agentic.exception;

/** Agent output or workflow structure failed validation. Never retried. */
public class ValidationFailureException extends RuntimeException {
    private final boolean critical;

    public ValidationFailureException(String message) {
        this(message, false);
    }

    public ValidationFailureException(String message, boolean critical) {
        super(message);
        this.critical = critical;
    }

    /** Critical validation failures trigger a safe-stop instead of a plain FAILED state. */
    public boolean isCritical() { return critical; }
}
