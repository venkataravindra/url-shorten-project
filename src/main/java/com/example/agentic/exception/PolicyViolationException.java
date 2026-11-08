package com.example.agentic.exception;

import org.springframework.http.HttpStatus;

/** A governance rule was violated. Never retried; leads to a safe-stop when raised during execution. */
public class PolicyViolationException extends ApiException {
    public PolicyViolationException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "POLICY_VIOLATION", message);
    }

    protected PolicyViolationException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
