package com.example.agentic.urlshortener;

import com.example.agentic.config.ShortenerProperties;
import com.example.agentic.exception.*;
import com.example.agentic.urlshortener.dto.CreateShortUrlRequest;
import com.example.agentic.urlshortener.entity.ShortUrl;
import com.example.agentic.urlshortener.repository.ShortUrlRepository;
import com.example.agentic.urlshortener.repository.UrlClickRepository;
import com.example.agentic.urlshortener.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class UrlShortenerServiceTest {
    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
    private final ShortUrlRepository urls = mock(ShortUrlRepository.class);
    private final UrlClickRepository clicks = mock(UrlClickRepository.class);
    private final ShortUrlWriter writer = mock(ShortUrlWriter.class);
    private final ShortenerProperties props = new ShortenerProperties("http://localhost:8080", 7, 3, true);
    private UrlShortenerService service;

    @BeforeEach
    void setUp() {
        service = new UrlShortenerService(urls, clicks, writer, new ShortCodeGenerator(props), new UrlValidator(props),
                props, Clock.fixed(NOW, ZoneOffset.UTC));
        when(writer.insert(any())).thenAnswer(i -> i.getArgument(0));
    }

    private ShortUrl stored(String code, boolean active, LocalDateTime expiry) {
        ShortUrl u = new ShortUrl();
        u.setId(1L);
        u.setShortCode(code);
        u.setOriginalUrl("https://example.com/x");
        u.setActive(active);
        u.setExpirationDate(expiry);
        return u;
    }

    @Test
    void generatesBase62CodesOfConfiguredLength() {
        ShortCodeGenerator g = new ShortCodeGenerator(props);
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 500; i++) {
            String c = g.next();
            assertThat(c).matches("[0-9A-Za-z]{7}");
            seen.add(c);
        }
        assertThat(seen.size()).isGreaterThan(495);
    }

    @Test
    void createsWithGeneratedCode() {
        var r = service.create(new CreateShortUrlRequest("https://example.com/a", null, null));
        assertThat(r.shortCode()).hasSize(7);
        assertThat(r.customAlias()).isFalse();
        assertThat(r.shortUrl()).isEqualTo("http://localhost:8080/api/v1/urls/" + r.shortCode());
    }

    @Test
    void retriesGeneratedCodeWhenTheDatabaseReportsACollision() {
        when(writer.insert(any())).thenThrow(new DataIntegrityViolationException("dup")).thenAnswer(i -> i.getArgument(0));
        assertThat(service.create(new CreateShortUrlRequest("https://example.com/a", null, null)).shortCode()).isNotBlank();
        verify(writer, times(2)).insert(any());
    }

    @Test
    void givesUpAfterConfiguredAttempts() {
        when(urls.existsByShortCode(anyString())).thenReturn(true);
        assertThatThrownBy(() -> service.create(new CreateShortUrlRequest("https://example.com/a", null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void duplicateAliasIsRejectedBeforeAndAfterTheInsertRace() {
        when(urls.existsByShortCode("taken")).thenReturn(true);
        assertThatThrownBy(() -> service.create(new CreateShortUrlRequest("https://example.com/a", "taken", null)))
                .isInstanceOf(AliasAlreadyExistsException.class);
        when(writer.insert(any())).thenThrow(new DataIntegrityViolationException("dup"));
        assertThatThrownBy(() -> service.create(new CreateShortUrlRequest("https://example.com/a", "racy", null)))
                .isInstanceOf(AliasAlreadyExistsException.class);
    }

    @Test
    void expirationMustBeInTheFuture() {
        assertThatThrownBy(() -> service.create(new CreateShortUrlRequest("https://example.com/a", null,
                LocalDateTime.of(2026, 6, 1, 10, 0, 0)))).isInstanceOf(InvalidRequestException.class);
        assertThat(service.create(new CreateShortUrlRequest("https://example.com/a", null,
                LocalDateTime.of(2026, 6, 1, 10, 0, 1))).expired()).isFalse();
    }

    @Test
    void resolveRecordsOneClickWithReferrerHost() {
        when(urls.findByShortCode("abc")).thenReturn(Optional.of(stored("abc", true, null)));
        assertThat(service.resolveAndRecord("abc", "https://News.Example.org/path?q=1")).isEqualTo("https://example.com/x");
        verify(urls).registerClick(1L, LocalDateTime.of(2026, 6, 1, 10, 0));
        var captor = ArgumentCaptor.forClass(com.example.agentic.urlshortener.entity.UrlClick.class);
        verify(clicks).save(captor.capture());
        assertThat(captor.getValue().getReferrer()).isEqualTo("news.example.org");
    }

    @Test
    void expiredAndInactiveUrlsAreGoneAndNotCounted() {
        when(urls.findByShortCode("old")).thenReturn(Optional.of(stored("old", true, LocalDateTime.of(2026, 5, 31, 0, 0))));
        when(urls.findByShortCode("off")).thenReturn(Optional.of(stored("off", false, null)));
        assertThatThrownBy(() -> service.resolveAndRecord("old", null)).isInstanceOf(ShortUrlGoneException.class).hasMessageContaining("expired");
        assertThatThrownBy(() -> service.resolveAndRecord("off", null)).isInstanceOf(ShortUrlGoneException.class).hasMessageContaining("deactivated");
        verify(urls, never()).registerClick(any(), any());
        verifyNoInteractions(clicks);
    }

    @Test
    void referrerParsingIsDefensive() {
        assertThat(UrlShortenerService.referrerHost(null)).isEqualTo("direct");
        assertThat(UrlShortenerService.referrerHost("  ")).isEqualTo("direct");
        assertThat(UrlShortenerService.referrerHost("::::")).isEqualTo("direct");
        assertThat(UrlShortenerService.referrerHost("android-app://com.x")).isNotBlank();
    }

    @Test
    void validatorBlocksOpenRedirectSchemesAndPrivateHosts() {
        UrlValidator v = new UrlValidator(props);
        assertThat(v.validateTarget("https://example.com/path?q=1#f").getHost()).isEqualTo("example.com");
        for (String bad : new String[]{"javascript:alert(1)", "ftp://x.com", "//evil.com", "http://127.0.0.1/", "http://10.0.0.1/",
                "http://172.20.1.1/", "http://169.254.169.254/latest", "http://a.localhost/", "https://u:p@x.com/"}) {
            assertThatThrownBy(() -> v.validateTarget(bad)).as(bad).isInstanceOf(InvalidRequestException.class);
        }
        assertThat(v.validateTarget("http://172.32.0.1/").getHost()).isEqualTo("172.32.0.1");
    }
}