package com.example.agentic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StopRequest(@NotBlank @Size(max = 500) String reason) {
}
