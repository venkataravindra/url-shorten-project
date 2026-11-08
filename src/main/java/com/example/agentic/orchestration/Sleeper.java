package com.example.agentic.orchestration;

/** Indirection over Thread.sleep so retry back-off is observable and instant in tests. */
@FunctionalInterface
public interface Sleeper {
    void sleep(long millis) throws InterruptedException;
}
