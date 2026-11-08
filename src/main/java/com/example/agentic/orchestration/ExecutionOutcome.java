package com.example.agentic.orchestration;

import com.example.agentic.agent.AgentResult;

public record ExecutionOutcome(AgentResult result, int attempts, boolean fallbackUsed) {
}
