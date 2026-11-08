package com.example.agentic.governance;

public record PolicyDecision(Outcome outcome, String reason) {
    public enum Outcome { ALLOW, REQUIRE_APPROVAL, DENY }
}