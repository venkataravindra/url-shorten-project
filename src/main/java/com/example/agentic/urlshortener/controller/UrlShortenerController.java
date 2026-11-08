package com.example.agentic.urlshortener.controller;

import com.example.agentic.urlshortener.dto.*;
import com.example.agentic.urlshortener.service.UrlShortenerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/urls")
@Tag(name = "URL Shortener")
public class UrlShortenerController {
    private final UrlShortenerService service;

    public UrlShortenerController(UrlShortenerService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create a short URL (optional custom alias and expiration)")
    public ResponseEntity<ShortUrlResponse> create(@Valid @RequestBody CreateShortUrlRequest request) {
        ShortUrlResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/urls/" + created.shortCode())).body(created);
    }

    @GetMapping("/{shortCode}")
    @Operation(summary = "Redirect (302) to the original URL; records a click")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode,
                                         @RequestHeader(value = HttpHeaders.REFERER, required = false) String referer) {
        String target = service.resolveAndRecord(shortCode, referer);
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, target)
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @GetMapping("/{shortCode}/analytics")
    public AnalyticsResponse analytics(@PathVariable String shortCode) {
        return service.analytics(shortCode);
    }

    @GetMapping("/{shortCode}/details")
    public ShortUrlResponse details(@PathVariable String shortCode) {
        return service.get(shortCode);
    }

    @GetMapping
    public PagedResponse<ShortUrlResponse> list(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                @RequestParam(required = false) Boolean active) {
        return service.list(page, size, active);
    }

    @PutMapping("/{shortCode}/status")
    @Operation(summary = "Activate or deactivate a short URL")
    public ShortUrlResponse setStatus(@PathVariable String shortCode, @Valid @RequestBody StatusUpdateRequest body) {
        return service.setActive(shortCode, body.active());
    }

    @DeleteMapping("/{shortCode}")
    @Operation(summary = "Deactivate (soft delete) a short URL")
    public ResponseEntity<Void> deactivate(@PathVariable String shortCode) {
        service.deactivate(shortCode);
        return ResponseEntity.noContent().build();
    }
}