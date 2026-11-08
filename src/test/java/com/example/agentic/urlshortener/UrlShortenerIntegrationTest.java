package com.example.agentic.urlshortener;

import com.example.agentic.IntegrationTestBase;
import com.example.agentic.urlshortener.entity.ShortUrl;
import com.example.agentic.urlshortener.repository.ShortUrlRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UrlShortenerIntegrationTest extends IntegrationTestBase {
    @Autowired ShortUrlRepository repository;

    private JsonNode create(Map<String, Object> req) throws Exception {
        return body(mvc.perform(post("/api/v1/urls").with(engineer()).contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(req))).andExpect(status().isCreated()).andReturn());
    }

    @Test
    void createAndRedirect() throws Exception {
        JsonNode created = create(Map.of("originalUrl", "https://example.com/articles/spring-boot"));
        String code = created.get("shortCode").asText();
        assertThat(code).hasSize(7);
        assertThat(created.get("shortUrl").asText()).endsWith("/api/v1/urls/" + code);
        mvc.perform(get("/api/v1/urls/" + code)).andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/articles/spring-boot"));
    }

    @Test
    void customAliasAndDuplicateAlias() throws Exception {
        LocalDateTime exp = LocalDateTime.now(ZoneOffset.UTC).plusDays(30).withNano(0);
        JsonNode created = create(Map.of("originalUrl", "https://example.com/a", "customAlias", "spring-guide", "expirationDate", exp.toString()));
        assertThat(created.get("shortCode").asText()).isEqualTo("spring-guide");
        assertThat(created.get("customAlias").asBoolean()).isTrue();
        mvc.perform(post("/api/v1/urls").with(engineer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originalUrl\":\"https://example.com/b\",\"customAlias\":\"spring-guide\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALIAS_ALREADY_EXISTS"));
    }

    @Test
    void validationRejectsUnsafeAndMalformedInput() throws Exception {
        for (String bad : new String[]{"javascript:alert(1)", "data:text/html,x", "file:///etc/passwd", "ftp://example.com/x",
                "https://user:pw@example.com/", "http://localhost/admin", "http://192.168.1.5/x", "not a url", ""}) {
            mvc.perform(post("/api/v1/urls").with(engineer()).contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(Map.of("originalUrl", bad)))).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/urls").with(engineer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"originalUrl\":\"https://example.com\",\"customAlias\":\"a b\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/urls").with(engineer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"originalUrl\":\"https://example.com\",\"customAlias\":\"admin\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/urls").with(engineer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"originalUrl\":\"https://example.com\",\"expirationDate\":\"2001-01-01T00:00:00\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/urls").with(engineer()).contentType(MediaType.APPLICATION_JSON).content("{broken")).andExpect(status().isBadRequest());
    }

    @Test
    void unknownExpiredAndDeactivatedCodes() throws Exception {
        mvc.perform(get("/api/v1/urls/doesnotexist")).andExpect(status().isNotFound());

        ShortUrl expired = new ShortUrl();
        expired.setShortCode("expired1");
        expired.setOriginalUrl("https://example.com/old");
        expired.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(2));
        expired.setExpirationDate(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        repository.save(expired);
        mvc.perform(get("/api/v1/urls/expired1")).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("SHORT_URL_EXPIRED"));

        String code = create(Map.of("originalUrl", "https://example.com/live")).get("shortCode").asText();
        mvc.perform(delete("/api/v1/urls/" + code).with(engineer())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/urls/" + code).with(engineer())).andExpect(status().isNoContent()); // idempotent
        mvc.perform(get("/api/v1/urls/" + code)).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("SHORT_URL_INACTIVE"));
        mvc.perform(put("/api/v1/urls/" + code + "/status").with(engineer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        mvc.perform(get("/api/v1/urls/" + code)).andExpect(status().isFound());
        mvc.perform(put("/api/v1/urls/expired1/status").with(engineer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":true}")).andExpect(status().isConflict());
    }

    @Test
    void analyticsArePersistedAndAggregated() throws Exception {
        String code = create(Map.of("originalUrl", "https://example.com/analytics")).get("shortCode").asText();
        mvc.perform(get("/api/v1/urls/" + code).header("Referer", "https://news.example.org/a/b?x=1")).andExpect(status().isFound());
        mvc.perform(get("/api/v1/urls/" + code).header("Referer", "https://news.example.org/other")).andExpect(status().isFound());
        mvc.perform(get("/api/v1/urls/" + code)).andExpect(status().isFound());

        JsonNode a = body(mvc.perform(get("/api/v1/urls/" + code + "/analytics").with(engineer())).andExpect(status().isOk()).andReturn());
        assertThat(a.get("totalClicks").asLong()).isEqualTo(3);
        assertThat(a.get("firstAccessedAt").isNull()).isFalse();
        assertThat(a.get("lastAccessedAt").isNull()).isFalse();
        assertThat(a.get("referrers").get(0).get("referrer").asText()).isEqualTo("news.example.org");
        assertThat(a.get("referrers").get(0).get("clicks").asLong()).isEqualTo(2);
        assertThat(a.get("referrers").get(1).get("referrer").asText()).isEqualTo("direct");
        assertThat(a.get("dailyClicks")).hasSize(1);
        assertThat(a.get("dailyClicks").get(0).get("date").asText()).isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());
        assertThat(a.get("dailyClicks").get(0).get("clicks").asLong()).isEqualTo(3);
        assertThat(repository.findByShortCode(code).orElseThrow().getClickCount()).isEqualTo(3);
    }

    @Test
    void listIsPaginatedAndFilterable() throws Exception {
        for (int i = 0; i < 3; i++) {
            create(Map.of("originalUrl", "https://example.com/p" + i));
        }
        JsonNode page = body(mvc.perform(get("/api/v1/urls?page=0&size=2").with(engineer())).andExpect(status().isOk()).andReturn());
        assertThat(page.get("content")).hasSize(2);
        assertThat(page.get("totalElements").asLong()).isGreaterThanOrEqualTo(3);
        mvc.perform(get("/api/v1/urls?size=1000").with(engineer())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/urls?active=false").with(engineer())).andExpect(status().isOk());
    }

    @Test
    void managementEndpointsRequireAuthenticationButRedirectIsPublic() throws Exception {
        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content("{\"originalUrl\":\"https://example.com\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/urls")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void webConsoleIsPublicAndBrowserScriptsGetANonPoppingJson401() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Agentic Engineering Console")));
        mvc.perform(get("/app.js")).andExpect(status().isOk());
        mvc.perform(get("/styles.css")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/workflows").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/workflows")).andExpect(status().isUnauthorized()).andExpect(header().exists("WWW-Authenticate"));
    }
}