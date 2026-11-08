package com.example.agentic.exception;

public class CyclicDependencyException extends ValidationFailureException {
    public CyclicDependencyException(String message) {
        super(message, false);
    }
}
