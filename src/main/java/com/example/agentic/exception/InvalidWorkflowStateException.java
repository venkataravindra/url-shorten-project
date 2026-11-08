package com.example.agentic.exception;

public class InvalidWorkflowStateException extends ConflictException {
    public InvalidWorkflowStateException(String message) {
        super("INVALID_WORKFLOW_STATE", message);
    }
}
