package com.example.agentic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
        @DefaultValue("http://localhost:8080") String baseUrl,
        @DefaultValue("7") int codeLength,
        @DefaultValue("5") int maxCodeAttempts,
        @DefaultValue("true") boolean blockPrivateHosts) {
}
