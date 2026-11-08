package com.example.agentic.urlshortener.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Append-only access record. Kept separate from ShortUrl so redirects never rewrite a wide row. */
@Entity
@Table(name = "url_clicks", indexes = {
        @Index(name = "idx_click_url_time", columnList = "short_url_id,accessedAt"),
        @Index(name = "idx_click_url_ref", columnList = "short_url_id,referrer")})
@Getter
@Setter
public class UrlClick {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "short_url_id")
    private ShortUrl shortUrl;
    private LocalDateTime accessedAt;
    /** Referrer host only (no path/query) to limit personal data; "direct" when absent. */
    @Column(length = 255)
    private String referrer;
}