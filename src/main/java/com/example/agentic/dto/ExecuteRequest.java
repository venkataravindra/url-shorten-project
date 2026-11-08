package com.example.agentic.dto;

import jakarta.validation.constraints.Size;

/** acknowledgement is mandatory when resuming a SAFE_STOPPED workflow. */
public record ExecuteRequest(@Size(max = 500) String acknowledgement) {
}
