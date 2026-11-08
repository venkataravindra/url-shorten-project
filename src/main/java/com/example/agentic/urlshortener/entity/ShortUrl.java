package com.example.agentic.urlshortener.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "short_urls",
        uniqueConstraints = @UniqueConstraint(name = "uq_short_code", columnNames = "shortCode"),
        indexes = {
                @Index(name = "idx_short_active_exp", columnList = "active,expirationDate"),
                @Index(name = "idx_short_created", columnList = "createdAt")})
@Getter
@Setter
public class ShortUrl {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(length = 32, nullable = false)
    private String shortCode;
    @Column(length = 2048, nullable = false)
    private String originalUrl;
    private boolean customAlias;
    private boolean active = true;
    private LocalDateTime createdAt;
    private LocalDateTime expirationDate;
    /** Denormalised counter updated atomically; click rows remain the source of truth for analytics. */
    private long clickCount;
    private LocalDateTime firstAccessedAt;
    private LocalDateTime lastAccessedAt;
}