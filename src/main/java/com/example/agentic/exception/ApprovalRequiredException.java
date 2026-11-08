package com.example.agentic.exception;

public class ApprovalRequiredException extends PolicyViolationException {
    public ApprovalRequiredException(String message) {
        super("APPROVAL_REQUIRED", message);
    }
}
