package com.example.agentic.agent;

/** A unit of engineering work. Implementations must be idempotent: the engine may run them again after a retry. */
public interface Agent {
    String id();

    AgentResult execute(AgentContext context);
}