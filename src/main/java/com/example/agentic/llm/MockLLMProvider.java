package com.example.agentic.llm;

import com.example.agentic.exception.LLMProviderException;
import com.example.agentic.exception.LLMTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SIMULATION ONLY. Produces deterministic, rule-based responses keyed on the operation and on keywords in the
 * requirement. It performs no reasoning and calls no external service. Supports configurable delay,
 * deterministic pseudo-random transient failures and programmable fault injection for retry testing.
 */
public class MockLLMProvider implements LLMProvider {
    static final String NOTICE = "SIMULATED RESPONSE: produced by deterministic rules in MockLLMProvider, not by a real LLM.";

    private final String name;
    private final long delayMs;
    private final double failureProbability;
    private final long seed;
    private final boolean degraded;
    private final FaultInjector injector;

    public MockLLMProvider(String name, long delayMs, double failureProbability, long seed, boolean degraded,
                           FaultInjector injector) {
        this.name = name;
        this.delayMs = delayMs;
        this.failureProbability = failureProbability;
        this.seed = seed;
        this.degraded = degraded;
        this.injector = injector;
    }

    @Override
    public String name() { return name; }

    @Override
    public boolean isSimulated() { return true; }

    @Override
    public LLMResponse complete(LLMRequest request) {
        long start = System.nanoTime();
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LLMTimeoutException("Interrupted while waiting for the mock LLM");
            }
        }
        FailureMode injected = injector == null ? null : injector.consume(request.operation(), degraded);
        if (injected != null) {
            switch (injected) {
                case TIMEOUT -> throw new LLMTimeoutException("Injected timeout calling " + request.operation());
                case PROVIDER_UNAVAILABLE -> throw new LLMProviderException("Injected 503 from " + name);
                case DATABASE_TRANSIENT ->
                        throw new TransientDataAccessResourceException("Injected transient database failure");
                default -> { /* payload-level failures handled below */ }
            }
        }
        if (!degraded && randomFailure(request)) {
            throw new LLMTimeoutException("Simulated random timeout calling " + request.operation());
        }
        Map<String, Object> payload = injected == FailureMode.MALFORMED_OUTPUT
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(MockResponses.respond(request, RequirementSignals.of(request.requirement())));
        if (injected == FailureMode.SECRET_LEAK) {
            leakSecret(payload);
        }
        payload.put("simulationNotice", NOTICE);
        long latency = (System.nanoTime() - start) / 1_000_000;
        return new LLMResponse(request.operation(), name, true, degraded, payload, latency, NOTICE);
    }

    private boolean randomFailure(LLMRequest r) {
        if (failureProbability <= 0) {
            return false;
        }
        int h = (int) (seed * 31 + r.operation().name().hashCode() * 17L + String.valueOf(r.requirement()).hashCode()
                + r.attempt() * 7919L);
        return Math.floorMod(h, 10_000) / 10_000.0 < failureProbability;
    }

    @SuppressWarnings("unchecked")
    private static void leakSecret(Map<String, Object> payload) {
        Object docs = payload.get("documents");
        if (docs instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) first);
            copy.put("content", copy.get("content") + "\n\naws_access_key_id=AKIAABCDEFGHIJKLMNOP\n");
            List<Object> updated = new ArrayList<>(list);
            updated.set(0, copy);
            payload.put("documents", updated);
        }
    }
}