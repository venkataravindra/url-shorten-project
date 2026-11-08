package com.example.agentic.urlshortener.service;

import com.example.agentic.config.ShortenerProperties;
import com.example.agentic.exception.InvalidRequestException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Prevents open-redirect and abuse vectors: only http/https, a real host, no embedded credentials,
 * no control characters, and (optionally) no loopback/private literal hosts. Performs no DNS lookups.
 */
@Component
public class UrlValidator {
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Pattern CONTROL_OR_SPACE = Pattern.compile("[\\p{Cntrl}\\s]");
    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern ALIAS = Pattern.compile("^[A-Za-z0-9_-]{3,32}$");
    private static final Set<String> RESERVED_ALIASES =
            Set.of("api", "admin", "swagger-ui", "h2-console", "v3", "health", "actuator", "analytics");

    private final ShortenerProperties props;

    public UrlValidator(ShortenerProperties props) {
        this.props = props;
    }

    public URI validateTarget(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException("originalUrl must not be blank");
        }
        if (raw.length() > 2048) {
            throw new InvalidRequestException("originalUrl exceeds 2048 characters");
        }
        if (CONTROL_OR_SPACE.matcher(raw).find()) {
            throw new InvalidRequestException("originalUrl must not contain whitespace or control characters");
        }
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            throw new InvalidRequestException("originalUrl is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new InvalidRequestException("Only http and https URLs are allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidRequestException("originalUrl must contain a host");
        }
        if (uri.getUserInfo() != null) {
            throw new InvalidRequestException("URLs with embedded credentials are not allowed");
        }
        if (props.blockPrivateHosts() && isPrivateHost(host.toLowerCase(Locale.ROOT))) {
            throw new InvalidRequestException("Loopback and private network hosts are not allowed");
        }
        return uri;
    }

    public String validateAlias(String alias) {
        if (!ALIAS.matcher(alias).matches()) {
            throw new InvalidRequestException("customAlias must be 3-32 chars of letters, digits, '_' or '-'");
        }
        if (RESERVED_ALIASES.contains(alias.toLowerCase(Locale.ROOT))) {
            throw new InvalidRequestException("customAlias '" + alias + "' is reserved");
        }
        return alias;
    }

    static boolean isPrivateHost(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost") || host.equals("[::1]") || host.equals("::1")) {
            return true;
        }
        var m = IPV4.matcher(host);
        if (m.matches()) {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            return a == 10 || a == 127 || a == 0 || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
        }
        return false;
    }
}