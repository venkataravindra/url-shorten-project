package com.example.agentic.llm;

import java.util.Map;

/** Structured, JSON-compatible response. {@code simulated} is true for every response produced by the mock. */
public record LLMResponse(LLMOperation operation, String provider, boolean simulated, boolean degraded,
                          Map<String, Object> payload, long latencyMs, String notice) {
}