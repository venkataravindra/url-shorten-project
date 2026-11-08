package com.example.agentic.orchestration;

import com.example.agentic.agent.Agent;
import com.example.agentic.exception.ValidationFailureException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentRegistry {
    private final Map<String, Agent> agents = new HashMap<>();

    public AgentRegistry(List<Agent> all) {
        all.forEach(a -> agents.put(a.id(), a));
    }

    public Agent get(String id) {
        Agent a = agents.get(id);
        if (a == null) {
            throw new ValidationFailureException("No agent registered with id '" + id + "'");
        }
        return a;
    }
}