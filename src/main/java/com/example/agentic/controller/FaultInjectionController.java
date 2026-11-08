package com.example.agentic.controller;

import com.example.agentic.audit.AuditEventType;
import com.example.agentic.audit.AuditService;
import com.example.agentic.config.AgenticProperties;
import com.example.agentic.exception.ResourceNotFoundException;
import com.example.agentic.llm.FailureMode;
import com.example.agentic.llm.FaultInjector;
import com.example.agentic.llm.LLMOperation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** ADMIN-only demo hook for retry / fallback / safe-stop demonstrations. Disabled with agentic.llm.mock.fault-injection-enabled=false. */
@RestController
@RequestMapping("/api/v1/admin/fault-injection")
@Tag(name = "Admin: fault injection (mock LLM only)")
public class FaultInjectionController {
    public record InjectRequest(LLMOperation operation, @NotNull FailureMode mode, @Min(1) @Max(50) int times,
                                boolean includeFallback) { }

    private final FaultInjector injector;
    private final AgenticProperties props;
    private final AuditService audit;

    public FaultInjectionController(FaultInjector injector, AgenticProperties props, AuditService audit) {
        this.injector = injector;
        this.props = props;
        this.audit = audit;
    }

    @PostMapping
    @Operation(summary = "Make the next N mock LLM calls fail (operation omitted = any operation)")
    public List<Map<String, Object>> inject(@Valid @RequestBody InjectRequest request, Authentication auth) {
        enabled();
        injector.inject(request.operation(), request.mode(), request.times(), request.includeFallback());
        audit.record(null, AuditEventType.FAULT_INJECTED, auth.getName(), Map.of("operation", String.valueOf(request.operation()),
                "mode", request.mode().name(), "times", request.times(), "includeFallback", request.includeFallback()));
        return injector.snapshot();
    }

    @GetMapping
    public List<Map<String, Object>> active() {
        enabled();
        return injector.snapshot();
    }

    @DeleteMapping
    public List<Map<String, Object>> clear() {
        enabled();
        injector.clear();
        return injector.snapshot();
    }

    private void enabled() {
        if (!props.llm().mock().faultInjectionEnabled()) {
            throw new ResourceNotFoundException("Fault injection is disabled");
        }
    }
}