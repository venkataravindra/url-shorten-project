package com.example.agentic.exception;

public class LLMTimeoutException extends TransientAgentException {
    public LLMTimeoutException(String message) { super(message); }
}
