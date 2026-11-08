package com.example.agentic.agent;

import java.util.List;
import java.util.Map;

public record AgentResult(Map<String, Object> output, List<ArtifactDraft> artifacts, List<DecisionDraft> decisions,
                          boolean degraded, String provider) {
}