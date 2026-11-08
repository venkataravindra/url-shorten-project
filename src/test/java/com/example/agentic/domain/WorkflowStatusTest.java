package com.example.agentic.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowStatusTest {
    @Test
    void terminalStatesCannotRestartExceptViaReplan() {
        assertThat(WorkflowStatus.ROLLED_BACK.allowedTargets()).isEmpty();
        assertThat(WorkflowStatus.CANCELLED.allowedTargets()).isEmpty();
        assertThat(WorkflowStatus.COMPLETED.canTransitionTo(WorkflowStatus.RUNNING)).isFalse();
        assertThat(WorkflowStatus.COMPLETED.canTransitionTo(WorkflowStatus.CREATED)).isTrue();
    }

    @Test
    void safeStopCanOnlyResumeThroughCreatedOrRunning() {
        assertThat(WorkflowStatus.SAFE_STOPPED.canTransitionTo(WorkflowStatus.COMPLETED)).isFalse();
        assertThat(WorkflowStatus.SAFE_STOPPED.canTransitionTo(WorkflowStatus.CREATED)).isTrue();
    }

    @Test
    void createdCannotJumpToCompleted() {
        assertThat(WorkflowStatus.CREATED.canTransitionTo(WorkflowStatus.COMPLETED)).isFalse();
        assertThat(WorkflowStatus.RUNNING.canTransitionTo(WorkflowStatus.RETRYING)).isTrue();
        assertThat(WorkflowStatus.RETRYING.canTransitionTo(WorkflowStatus.RUNNING)).isTrue();
    }
}