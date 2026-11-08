package com.example.agentic.orchestration;

import org.springframework.stereotype.Component;

@Component
public class ThreadSleeper implements Sleeper {
    @Override
    public void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }
}
