package com.example.agentic.urlshortener.dto;

import java.time.LocalDateTime;

public record ShortUrlResponse(String shortCode, String shortUrl, String originalUrl, boolean customAlias,
                               boolean active, boolean expired, LocalDateTime createdAt,
                               LocalDateTime expirationDate, long clickCount) {
}