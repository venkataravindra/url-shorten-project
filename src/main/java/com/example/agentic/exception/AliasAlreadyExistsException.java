package com.example.agentic.exception;

public class AliasAlreadyExistsException extends ConflictException {
    public AliasAlreadyExistsException(String alias) {
        super("ALIAS_ALREADY_EXISTS", "Short code or alias '" + alias + "' is already in use");
    }
}
