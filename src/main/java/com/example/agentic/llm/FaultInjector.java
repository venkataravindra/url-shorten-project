package com.example.agentic.llm;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Programmable failure source for demos and tests. Thread-safe; each rule fires a fixed number of times. */
@Component
public class FaultInjector {

    private static final class Rule {
        final LLMOperation operation; // null = any operation
        final FailureMode mode;
        final boolean includeFallback;
        int remaining;

        Rule(LLMOperation operation, FailureMode mode, int remaining, boolean includeFallback) {
            this.operation = operation;
            this.mode = mode;
            this.remaining = remaining;
            this.includeFallback = includeFallback;
        }
    }

    private final List<Rule> rules = new ArrayList<>();

    public synchronized void inject(LLMOperation operation, FailureMode mode, int times, boolean includeFallback) {
        if (times < 1) {
            throw new IllegalArgumentException("times must be >= 1");
        }
        rules.add(new Rule(operation, mode, times, includeFallback));
    }

    /** Returns the failure to apply to this call, or null. */
    public synchronized FailureMode consume(LLMOperation operation, boolean fallbackProvider) {
        for (Rule r : rules) {
            if (r.remaining > 0 && (r.operation == null || r.operation == operation)
                    && (!fallbackProvider || r.includeFallback)) {
                r.remaining--;
                return r.mode;
            }
        }
        return null;
    }

    public synchronized void clear() {
        rules.clear();
    }

    public synchronized List<Map<String, Object>> snapshot() {
        return rules.stream().filter(r -> r.remaining > 0).map(r -> Map.<String, Object>of(
                "operation", r.operation == null ? "ANY" : r.operation.name(),
                "mode", r.mode.name(), "remaining", r.remaining, "includeFallback", r.includeFallback)).toList();
    }
}