package com.example.agentic.dto;

import com.example.agentic.orchestration.ReplanChangeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ReplanRequest(@NotBlank String changedTask, @NotNull ReplanChangeType changeType,
                            @NotBlank @Size(max = 1000) String reason, @Size(max = 2000) String newRequirement,
                            List<@Valid EdgeDto> addDependencies) {
}
