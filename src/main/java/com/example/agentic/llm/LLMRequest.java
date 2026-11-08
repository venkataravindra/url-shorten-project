package com.example.agentic.llm;

import java.util.Map;

/** Provider-neutral request: the operation, the (normalised) requirement and structured context from upstream stages. */
public record LLMRequest(LLMOperation operation, String requirement, Map<String, Object> context,
                         String correlationId, int attempt) {
    public LLMRequest {
        context = context == null ? Map.of() : context;
    }
}