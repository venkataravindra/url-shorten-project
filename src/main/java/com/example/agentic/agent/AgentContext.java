package com.example.agentic.agent;

import com.example.agentic.llm.LLMOperation;
import com.example.agentic.llm.LLMProvider;
import com.example.agentic.llm.LLMRequest;
import com.example.agentic.llm.LLMResponse;
import com.example.agentic.orchestration.WorkflowContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Everything an agent may use: upstream outputs, its parameters and the LLM provider chosen for this attempt. */
public final class AgentContext {
    private final String workflowId;
    private final String taskKey;
    private final Map<String, String> params;
    private final WorkflowContext workflow;
    private final LLMProvider llm;
    private final int attempt;
    private final AtomicBoolean degraded = new AtomicBoolean();
    private final AtomicReference<String> provider = new AtomicReference<>("none");

    public AgentContext(String workflowId, String taskKey, Map<String, String> params, WorkflowContext workflow,
                        LLMProvider llm, int attempt) {
        this.workflowId = workflowId;
        this.taskKey = taskKey;
        this.params = params;
        this.workflow = workflow;
        this.llm = llm;
        this.attempt = attempt;
    }

    public String workflowId() { return workflowId; }
    public String taskKey() { return taskKey; }
    public Map<String, String> params() { return params; }
    public WorkflowContext workflow() { return workflow; }
    public int attempt() { return attempt; }
    public String requirement() { return workflow.requirement(); }

    public LLMResponse call(LLMOperation operation, Map<String, Object> context) {
        LLMResponse response = llm.complete(new LLMRequest(operation, requirement(), context, workflowId + ":" + taskKey, attempt));
        if (response.degraded()) {
            degraded.set(true);
        }
        provider.set(response.provider());
        return response;
    }

    public AgentResult result(Map<String, Object> output, List<ArtifactDraft> artifacts, List<DecisionDraft> decisions) {
        return new AgentResult(output, artifacts, decisions, degraded.get(), provider.get());
    }
}