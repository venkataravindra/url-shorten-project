package com.example.agentic.exception;

/** A security problem (secret in artifact, critical review finding, path escape). */
public class SecurityConcernException extends PolicyViolationException {
    public SecurityConcernException(String message) {
        super("SECURITY_CONCERN", message);
    }
}
