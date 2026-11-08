package com.example.agentic.urlshortener.service;

import com.example.agentic.config.ShortenerProperties;
import com.example.agentic.exception.*;
import com.example.agentic.urlshortener.dto.*;
import com.example.agentic.urlshortener.entity.ShortUrl;
import com.example.agentic.urlshortener.entity.UrlClick;
import com.example.agentic.urlshortener.repository.ShortUrlRepository;
import com.example.agentic.urlshortener.repository.UrlClickRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class UrlShortenerService {
    private static final int MAX_PAGE_SIZE = 100;

    private final ShortUrlRepository urls;
    private final UrlClickRepository clicks;
    private final ShortUrlWriter writer;
    private final ShortCodeGenerator generator;
    private final UrlValidator validator;
    private final ShortenerProperties props;
    private final Clock clock;

    public UrlShortenerService(ShortUrlRepository urls, UrlClickRepository clicks, ShortUrlWriter writer,
                               ShortCodeGenerator generator, UrlValidator validator,
                               ShortenerProperties props, Clock clock) {
        this.urls = urls;
        this.clicks = clicks;
        this.writer = writer;
        this.generator = generator;
        this.validator = validator;
        this.props = props;
        this.clock = clock;
    }

    /** Not transactional itself: each insert attempt runs in ShortUrlWriter's own transaction. */
    public ShortUrlResponse create(CreateShortUrlRequest request) {
        URI target = validator.validateTarget(request.originalUrl().trim());
        LocalDateTime now = now();
        if (request.expirationDate() != null && !request.expirationDate().isAfter(now)) {
            throw new InvalidRequestException("expirationDate must be in the future");
        }
        ShortUrl url = new ShortUrl();
        url.setOriginalUrl(target.toString());
        url.setCreatedAt(now);
        url.setExpirationDate(request.expirationDate());

        if (request.customAlias() != null && !request.customAlias().isBlank()) {
            String alias = validator.validateAlias(request.customAlias());
            if (urls.existsByShortCode(alias)) {
                throw new AliasAlreadyExistsException(alias);
            }
            url.setShortCode(alias);
            url.setCustomAlias(true);
            try {
                return toResponse(writer.insert(url));
            } catch (DataIntegrityViolationException race) {
                throw new AliasAlreadyExistsException(alias);
            }
        }
        for (int attempt = 0; attempt < props.maxCodeAttempts(); attempt++) {
            url.setShortCode(generator.next());
            if (urls.existsByShortCode(url.getShortCode())) {
                continue;
            }
            try {
                return toResponse(writer.insert(url));
            } catch (DataIntegrityViolationException collision) {
                url.setId(null);
            }
        }
        throw new ConflictException("CODE_GENERATION_FAILED", "Could not allocate a unique short code, retry the request");
    }

    /** One SELECT, one atomic counter UPDATE and one INSERT. Returns the redirect target. */
    @Transactional
    public String resolveAndRecord(String shortCode, String referrerHeader) {
        ShortUrl url = urls.findByShortCode(shortCode)
                .orElseThrow(() -> new ResourceNotFoundException("Short URL '" + shortCode + "' not found"));
        LocalDateTime now = now();
        if (!url.isActive()) {
            throw new ShortUrlGoneException("SHORT_URL_INACTIVE", "Short URL '" + shortCode + "' has been deactivated");
        }
        if (isExpired(url, now)) {
            throw new ShortUrlGoneException("SHORT_URL_EXPIRED", "Short URL '" + shortCode + "' has expired");
        }
        urls.registerClick(url.getId(), now);
        UrlClick click = new UrlClick();
        click.setShortUrl(url);
        click.setAccessedAt(now);
        click.setReferrer(referrerHost(referrerHeader));
        clicks.save(click);
        return url.getOriginalUrl();
    }

    @Transactional(readOnly = true)
    public AnalyticsResponse analytics(String shortCode) {
        ShortUrl url = find(shortCode);
        List<AnalyticsResponse.ReferrerStat> referrers = clicks.referrerStats(url.getId()).stream()
                .map(r -> new AnalyticsResponse.ReferrerStat((String) r[0], (Long) r[1])).toList();
        List<AnalyticsResponse.DailyStat> daily = clicks.dailyStats(url.getId()).stream()
                .map(r -> new AnalyticsResponse.DailyStat(toLocalDate(r[0]), (Long) r[1])).toList();
        return new AnalyticsResponse(url.getShortCode(), url.getClickCount(), url.getFirstAccessedAt(),
                url.getLastAccessedAt(), referrers, daily);
    }

    @Transactional(readOnly = true)
    public PagedResponse<ShortUrlResponse> list(int page, int size, Boolean active) {
        if (page < 0) {
            throw new InvalidRequestException("page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        var result = active == null ? urls.findAll(pageable) : urls.findByActive(active, pageable);
        return PagedResponse.of(result, this::toResponse);
    }

    @Transactional(readOnly = true)
    public ShortUrlResponse get(String shortCode) {
        return toResponse(find(shortCode));
    }

    /** Soft delete: history and analytics are kept. Idempotent. */
    @Transactional
    public void deactivate(String shortCode) {
        ShortUrl url = find(shortCode);
        url.setActive(false);
    }

    @Transactional
    public ShortUrlResponse setActive(String shortCode, boolean active) {
        ShortUrl url = find(shortCode);
        if (active && isExpired(url, now())) {
            throw new ConflictException("URL_EXPIRED", "Expired URLs cannot be reactivated");
        }
        url.setActive(active);
        return toResponse(url);
    }

    private ShortUrl find(String shortCode) {
        return urls.findByShortCode(shortCode)
                .orElseThrow(() -> new ResourceNotFoundException("Short URL '" + shortCode + "' not found"));
    }

    private boolean isExpired(ShortUrl url, LocalDateTime now) {
        return url.getExpirationDate() != null && !url.getExpirationDate().isAfter(now);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public static String referrerHost(String header) {
        if (header == null || header.isBlank()) {
            return "direct";
        }
        try {
            String host = URI.create(header.trim()).getHost();
            return host == null ? "direct" : (host.length() > 255 ? host.substring(0, 255) : host.toLowerCase());
        } catch (IllegalArgumentException e) {
            return "direct";
        }
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate d) {
            return d;
        }
        if (value instanceof Date d) {
            return d.toLocalDate();
        }
        return LocalDate.parse(value.toString());
    }

    private ShortUrlResponse toResponse(ShortUrl u) {
        return new ShortUrlResponse(u.getShortCode(), props.baseUrl() + "/api/v1/urls/" + u.getShortCode(),
                u.getOriginalUrl(), u.isCustomAlias(), u.isActive(), isExpired(u, now()), u.getCreatedAt(),
                u.getExpirationDate(), u.getClickCount());
    }
}