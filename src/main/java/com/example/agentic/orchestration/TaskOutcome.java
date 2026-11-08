package com.example.agentic.orchestration;

record TaskOutcome(TaskSnapshot task, ExecutionOutcome success, TaskExecutionException failure) {
}
