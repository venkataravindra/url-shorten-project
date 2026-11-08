package com.example.agentic.orchestration;

import com.example.agentic.exception.ValidationFailureException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConditionEvaluatorTest {
    private final ConditionEvaluator evaluator = new ConditionEvaluator();
    private final WorkflowContext ctx = new WorkflowContext("w", "r", Map.of(
            "requirements", Map.of("blockingAmbiguity", true, "scenario", "AMBIGUOUS", "items", List.of(1)),
            "architecture", Map.of("requiresApproval", false, "empty", List.of())));

    @Test
    void evaluatesEqualityInequalityAndNonEmpty() {
        assertThat(evaluator.evaluate("requirements.blockingAmbiguity == true", ctx)).isTrue();
        assertThat(evaluator.evaluate("architecture.requiresApproval == true", ctx)).isFalse();
        assertThat(evaluator.evaluate("requirements.scenario != GREENFIELD", ctx)).isTrue();
        assertThat(evaluator.evaluate("requirements.scenario == 'AMBIGUOUS'", ctx)).isTrue();
        assertThat(evaluator.evaluate("requirements.items nonEmpty", ctx)).isTrue();
        assertThat(evaluator.evaluate("architecture.empty nonEmpty", ctx)).isFalse();
    }

    @Test
    void blankConditionAlwaysRunsAndMissingValuesAreFalse() {
        assertThat(evaluator.evaluate(null, ctx)).isTrue();
        assertThat(evaluator.evaluate("  ", ctx)).isTrue();
        assertThat(evaluator.evaluate("nothing.here == true", ctx)).isFalse();
    }

    @Test
    void rejectsUnsupportedSyntax() {
        assertThatThrownBy(() -> evaluator.evaluate("a > 3", ctx)).isInstanceOf(ValidationFailureException.class);
    }
}