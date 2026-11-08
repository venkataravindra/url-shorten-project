package com.example.agentic.llm;

import com.example.agentic.exception.LLMProviderException;
import com.example.agentic.exception.LLMTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MockLLMProviderTest {
    public static final String GREENFIELD = "Build a URL shortener that supports custom aliases, expiration, redirects, and click analytics.";
    public static final String BROWNFIELD = "Enhance the existing URL shortener to support URL expiration and automatic deactivation of expired links.";
    public static final String AMBIGUOUS = "Improve the URL shortener performance and make it more reliable.";

    private final FaultInjector injector = new FaultInjector();
    private final MockLLMProvider provider = new MockLLMProvider("mock", 0, 0, 7, false, injector);

    private LLMResponse call(LLMOperation op, String requirement) {
        return provider.complete(new LLMRequest(op, requirement, Map.of("acceptanceCriteria", List.of(), "facts", Map.of()), "t", 1));
    }

    @Test
    void classifiesTheThreeMandatoryScenarios() {
        assertThat(RequirementSignals.of(GREENFIELD).scenario()).isEqualTo("GREENFIELD");
        assertThat(RequirementSignals.of(BROWNFIELD).scenario()).isEqualTo("BROWNFIELD");
        assertThat(RequirementSignals.of(AMBIGUOUS).scenario()).isEqualTo("AMBIGUOUS");
    }

    @Test
    void responsesDifferPerScenario() {
        var g = call(LLMOperation.ANALYZE_REQUIREMENT, GREENFIELD).payload();
        var b = call(LLMOperation.ANALYZE_REQUIREMENT, BROWNFIELD).payload();
        var a = call(LLMOperation.ANALYZE_REQUIREMENT, AMBIGUOUS).payload();
        assertThat(g.get("scenario")).isEqualTo("GREENFIELD");
        assertThat(b.get("scenario")).isEqualTo("BROWNFIELD");
        assertThat(a.get("scenario")).isEqualTo("AMBIGUOUS");
        assertThat(g.get("functionalRequirements")).isNotEqualTo(b.get("functionalRequirements"));
        assertThat(a.get("missingInformation").toString()).contains("baseline");
    }

    @Test
    void ambiguousRequirementProducesBlockingAmbiguitiesButGreenfieldDoesNot() {
        assertThat(call(LLMOperation.IDENTIFY_AMBIGUITIES, AMBIGUOUS).payload().toString()).contains("BLOCKING");
        assertThat(call(LLMOperation.IDENTIFY_AMBIGUITIES, GREENFIELD).payload().toString()).doesNotContain("BLOCKING");
    }

    @Test
    void everyOperationReturnsStructuredPayloadAndSimulationNotice() {
        for (LLMOperation op : LLMOperation.values()) {
            LLMResponse r = call(op, GREENFIELD);
            assertThat(r.simulated()).isTrue();
            assertThat(r.payload()).isNotEmpty();
            assertThat(r.notice()).contains("SIMULATED");
            assertThat(r.payload().get("simulationNotice").toString()).contains("not by a real LLM");
        }
        assertThat(provider.isSimulated()).isTrue();
    }

    @Test
    void sameInputGivesSameOutput() {
        assertThat(call(LLMOperation.DECOMPOSE_TASKS, GREENFIELD).payload()).isEqualTo(call(LLMOperation.DECOMPOSE_TASKS, GREENFIELD).payload());
    }

    @Test
    void insecureRequirementYieldsCriticalReviewFinding() {
        var payload = call(LLMOperation.REVIEW_CODE, "Build a URL shortener and disable authentication for admin endpoints").payload();
        assertThat(payload.get("verdict")).isEqualTo("REJECT");
        assertThat(payload.toString()).contains("CRITICAL");
    }

    @Test
    void injectedFailuresFireExactlyTheRequestedNumberOfTimes() {
        injector.inject(LLMOperation.DECOMPOSE_TASKS, FailureMode.TIMEOUT, 2, false);
        assertThatThrownBy(() -> call(LLMOperation.DECOMPOSE_TASKS, GREENFIELD)).isInstanceOf(LLMTimeoutException.class);
        assertThatThrownBy(() -> call(LLMOperation.DECOMPOSE_TASKS, GREENFIELD)).isInstanceOf(LLMTimeoutException.class);
        assertThat(call(LLMOperation.DECOMPOSE_TASKS, GREENFIELD).payload()).containsKey("tasks");
        injector.inject(LLMOperation.REVIEW_CODE, FailureMode.PROVIDER_UNAVAILABLE, 1, false);
        assertThat(call(LLMOperation.DECOMPOSE_TASKS, GREENFIELD).payload()).containsKey("tasks");
        assertThatThrownBy(() -> call(LLMOperation.REVIEW_CODE, GREENFIELD)).isInstanceOf(LLMProviderException.class);
    }

    @Test
    void databaseTransientModeThrowsRetryableSpringException() {
        injector.inject(null, FailureMode.DATABASE_TRANSIENT, 1, false);
        assertThatThrownBy(() -> call(LLMOperation.ANALYZE_REQUIREMENT, GREENFIELD)).isInstanceOf(TransientDataAccessException.class);
    }

    @Test
    void malformedOutputHasNoDomainFields() {
        injector.inject(LLMOperation.ANALYZE_REQUIREMENT, FailureMode.MALFORMED_OUTPUT, 1, false);
        assertThat(call(LLMOperation.ANALYZE_REQUIREMENT, GREENFIELD).payload()).doesNotContainKey("scenario");
    }

    @Test
    void randomFailuresAreDeterministicAndDependOnAttempt() {
        MockLLMProvider flaky = new MockLLMProvider("flaky", 0, 0.5, 3, false, null);
        int failures = 0;
        StringBuilder first = new StringBuilder();
        StringBuilder second = new StringBuilder();
        for (int attempt = 1; attempt <= 40; attempt++) {
            for (StringBuilder sb : List.of(first, second)) {
                try {
                    flaky.complete(new LLMRequest(LLMOperation.REVIEW_CODE, GREENFIELD, Map.of(), "t", attempt));
                    sb.append('.');
                } catch (LLMTimeoutException e) {
                    sb.append('x');
                    if (sb == first) failures++;
                }
            }
        }
        assertThat(failures).isBetween(5, 35);
        assertThat(first.toString()).isEqualTo(second.toString());
    }

    @Test
    void fallbackProviderIsDegradedAndNeverFailsRandomly() {
        MockLLMProvider fallback = new MockLLMProvider("fb", 0, 1.0, 3, true, null);
        LLMResponse r = fallback.complete(new LLMRequest(LLMOperation.REVIEW_CODE, GREENFIELD, Map.of(), "t", 1));
        assertThat(r.degraded()).isTrue();
    }

    @Test
    void fallbackIgnoresInjectedFailuresUnlessAskedTo() {
        MockLLMProvider fallback = new MockLLMProvider("fb", 0, 0, 3, true, injector);
        injector.inject(null, FailureMode.TIMEOUT, 1, false);
        assertThat(fallback.complete(new LLMRequest(LLMOperation.REVIEW_CODE, GREENFIELD, Map.of(), "t", 1)).payload()).containsKey("findings");
        injector.clear();
        injector.inject(null, FailureMode.TIMEOUT, 1, true);
        assertThatThrownBy(() -> fallback.complete(new LLMRequest(LLMOperation.REVIEW_CODE, GREENFIELD, Map.of(), "t", 1)))
                .isInstanceOf(LLMTimeoutException.class);
    }
}