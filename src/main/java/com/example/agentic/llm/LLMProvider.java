package com.example.agentic.llm;

/**
 * Port for any text-generation backend. Agents depend only on this interface, so the mock can be replaced
 * by a real model without touching agents or orchestration.
 */
public interface LLMProvider {
    String name();

    /** True when responses are produced by deterministic rules rather than a real model. */
    boolean isSimulated();

    LLMResponse complete(LLMRequest request);
}