package com.example.agentic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWorkflowRequest(@NotBlank @Size(min = 10, max = 2000) String requirement,
                                    @Size(max = 200) String title) {
}
