package com.example.agentic.exception;

public class LLMProviderException extends TransientAgentException {
    public LLMProviderException(String message) { super(message); }
    public LLMProviderException(String message, Throwable cause) { super(message, cause); }
}
