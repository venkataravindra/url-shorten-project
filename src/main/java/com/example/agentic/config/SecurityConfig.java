package com.example.agentic.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;

import java.util.List;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

/**
 * HTTP Basic with role separation: ENGINEER drives workflows, APPROVER decides approvals, ADMIN injects faults.
 * Users come from configuration (dev defaults, overridable by environment variables).
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(4);
    }

    @Bean
    public UserDetailsService userDetailsService(AgenticProperties props, PasswordEncoder encoder) {
        List<UserDetails> users = props.security().users().stream()
                .map(u -> User.withUsername(u.username())
                        .password(encoder.encode(u.password()))
                        .roles(u.roles().toArray(new String[0])).build())
                .toList();
        return new InMemoryUserDetailsManager(users);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .headers(h -> h.frameOptions(f -> f.sameOrigin()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(antMatcher("/api/v1/health"), antMatcher("/v3/api-docs/**"),
                                antMatcher("/swagger-ui/**"), antMatcher("/swagger-ui.html"),
                                antMatcher("/h2-console/**")).permitAll()
                        // static single-page console (the API calls it makes are still authenticated)
                        .requestMatchers(antMatcher("/"), antMatcher("/index.html"), antMatcher("/styles.css"),
                                antMatcher("/app.js"), antMatcher("/flows.js"), antMatcher("/favicon.ico")).permitAll()
                        // public redirect: exactly one path segment after /api/v1/urls/
                        .requestMatchers(antMatcher(HttpMethod.GET, "/api/v1/urls/{shortCode}")).permitAll()
                        .requestMatchers(antMatcher("/api/v1/admin/**")).hasRole("ADMIN")
                        .requestMatchers(antMatcher(HttpMethod.POST, "/api/v1/workflows/*/approve"),
                                antMatcher(HttpMethod.POST, "/api/v1/workflows/*/reject")).hasRole("APPROVER")
                        .anyRequest().hasRole("ENGINEER"))
                .exceptionHandling(e -> e.accessDeniedHandler((request, response, ex) -> {
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    String who = auth == null ? "anonymous" : auth.getName() + " " + auth.getAuthorities();
                    String message = "Forbidden: you are signed in as " + who.replace("\"", "") + ". Approve/reject and resuming a safe-stopped "
                            + "workflow need the APPROVER role (user approver); fault injection needs ADMIN (user admin). Check GET /api/v1/whoami.";
                    response.setStatus(403);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"status\":403,\"error\":\"Forbidden\",\"code\":\"ACCESS_DENIED\",\"message\":\""
                            + message + "\",\"path\":\"" + request.getRequestURI().replace("\"", "") + "\"}");
                }))
                .httpBasic(basic -> basic.authenticationEntryPoint(ajaxAwareEntryPoint()));
        return http.build();
    }

    /**
     * Browser scripts (X-Requested-With: XMLHttpRequest) get a JSON 401 without the WWW-Authenticate header, so the
     * browser does not pop up its native login dialog; every other client gets the standard Basic challenge.
     */
    private AuthenticationEntryPoint ajaxAwareEntryPoint() {
        BasicAuthenticationEntryPoint basic = new BasicAuthenticationEntryPoint();
        basic.setRealmName("Agentic Platform");
        return (request, response, ex) -> {
            if ("XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
                response.setStatus(401);
                response.setContentType("application/json");
                response.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"code\":\"UNAUTHENTICATED\","
                        + "\"message\":\"Sign in with a valid user and password\",\"path\":\""
                        + request.getRequestURI().replace("\"", "") + "\"}");
            } else {
                basic.commence(request, response, ex);
            }
        };
    }
}
