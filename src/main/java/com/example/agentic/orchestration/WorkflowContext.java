package com.example.agentic.orchestration;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Read-only view of everything upstream stages have produced: the cross-stage shared context. */
public final class WorkflowContext {
    private final String workflowId;
    private final String requirement;
    private final Map<String, Map<String, Object>> outputs;

    public WorkflowContext(String workflowId, String requirement, Map<String, Map<String, Object>> outputs) {
        this.workflowId = workflowId;
        this.requirement = requirement;
        this.outputs = Collections.unmodifiableMap(outputs);
    }

    public String workflowId() { return workflowId; }
    public String requirement() { return requirement; }
    public boolean has(String taskKey) { return outputs.containsKey(taskKey); }
    public Map<String, Object> output(String taskKey) { return outputs.getOrDefault(taskKey, Map.of()); }

    /** Looks up "task.field.subfield". Returns null if any segment is missing. */
    public Object get(String path) {
        String[] parts = path.split("\\.");
        Object cur = outputs.get(parts[0]);
        for (int i = 1; i < parts.length && cur != null; i++) {
            cur = cur instanceof Map<?, ?> m ? m.get(parts[i]) : null;
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    public List<Object> list(String path) {
        return get(path) instanceof List<?> l ? (List<Object>) l : List.of();
    }

    public String string(String path, String fallback) {
        Object v = get(path);
        return v == null ? fallback : v.toString();
    }

    public boolean flag(String path) {
        return Boolean.parseBoolean(String.valueOf(get(path)));
    }
}