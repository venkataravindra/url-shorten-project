package com.example.agentic.llm;

public enum FailureMode {
    /** Transient: provider timed out. */
    TIMEOUT,
    /** Transient: provider returned 503. */
    PROVIDER_UNAVAILABLE,
    /** Transient: a downstream database error surfaced during the call. */
    DATABASE_TRANSIENT,
    /** Permanent: response payload is empty/malformed and fails agent output validation. */
    MALFORMED_OUTPUT,
    /** Security: documentation output contains a credential-like string (must be caught by guardrails). */
    SECRET_LEAK
}