package com.example.agentic.dto;

import java.time.Instant;

public record ArtifactResponse(Long id, String name, String type, String taskKey, int version, String status,
                               String path, String sha256, Instant createdAt, String content) {
}
