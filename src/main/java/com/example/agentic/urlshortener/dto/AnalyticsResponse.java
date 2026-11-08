package com.example.agentic.urlshortener.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record AnalyticsResponse(String shortCode, long totalClicks, LocalDateTime firstAccessedAt,
                                LocalDateTime lastAccessedAt, List<ReferrerStat> referrers,
                                List<DailyStat> dailyClicks) {
    public record ReferrerStat(String referrer, long clicks) {}
    public record DailyStat(LocalDate date, long clicks) {}
}