package com.example.agentic.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/whoami")
public class WhoAmIController {
    @GetMapping
    @Operation(summary = "Shows the signed-in user and roles - the first thing to check when you get HTTP 403")
    public Map<String, Object> whoAmI(Authentication auth) {
        List<String> roles = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList();
        return Map.of("user", auth.getName(), "roles", roles,
                "canApprove", roles.contains("ROLE_APPROVER"), "canInjectFaults", roles.contains("ROLE_ADMIN"));
    }
}