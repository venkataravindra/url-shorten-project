package com.example.agentic.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record CreateShortUrlRequest(
        @NotBlank @Size(max = 2048) String originalUrl,
        @Pattern(regexp = "^[A-Za-z0-9_-]{3,32}$", message = "must be 3-32 chars of letters, digits, '_' or '-'")
        String customAlias,
        LocalDateTime expirationDate) {
}