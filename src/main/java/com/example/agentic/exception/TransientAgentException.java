package com.example.agentic.exception;

/** A failure that may succeed if retried (timeouts, provider unavailable, transient DB errors). */
public class TransientAgentException extends RuntimeException {
    public TransientAgentException(String message) { super(message); }
    public TransientAgentException(String message, Throwable cause) { super(message, cause); }
}
