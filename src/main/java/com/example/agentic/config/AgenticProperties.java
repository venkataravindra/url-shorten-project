package com.example.agentic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@ConfigurationProperties(prefix = "agentic")
public record AgenticProperties(
        @DefaultValue Orchestration orchestration,
        @DefaultValue Retry retry,
        @DefaultValue Llm llm,
        @DefaultValue Workspace workspace,
        @DefaultValue Recovery recovery,
        @DefaultValue Security security) {

    public record Orchestration(@DefaultValue("2") int parallelism) {}

    public record Retry(@DefaultValue("3") int maxRetries,
                        @DefaultValue("200") long initialBackoffMs,
                        @DefaultValue("2.0") double multiplier,
                        @DefaultValue("5000") long maxBackoffMs,
                        @DefaultValue("true") boolean fallbackEnabled) {}

    public record Llm(@DefaultValue("mock") String provider, @DefaultValue Mock mock, @DefaultValue OpenAi openai) {}

    public record Mock(@DefaultValue("0") long delayMs,
                       @DefaultValue("0.0") double failureProbability,
                       @DefaultValue("42") long seed,
                       @DefaultValue("true") boolean faultInjectionEnabled) {}

    public record OpenAi(@DefaultValue("false") boolean enabled,
                         @DefaultValue("gpt-4o-mini") String model,
                         @DefaultValue("https://api.openai.com/v1") String baseUrl,
                         @DefaultValue("") String apiKey) {}

    public record Workspace(@DefaultValue("./workspace") String root) {}

    public record Recovery(@DefaultValue("false") boolean autoResume) {}

    public record Security(@DefaultValue List<UserProps> users) {}

    public record UserProps(String username, String password, List<String> roles) {}
}
