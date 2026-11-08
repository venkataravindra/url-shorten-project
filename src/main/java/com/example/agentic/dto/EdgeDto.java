package com.example.agentic.dto;

import jakarta.validation.constraints.NotBlank;

public record EdgeDto(@NotBlank String from, @NotBlank String to) {
}
