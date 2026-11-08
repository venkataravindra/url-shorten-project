package com.example.agentic.governance;

import java.util.regex.Pattern;

/** Removes credential-like substrings before anything is logged or persisted in audit/failure fields. */
public final class SecretRedactor {
    private static final Pattern[] PATTERNS = {
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("sk-[A-Za-z0-9]{20,}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("(?i)((?:api[_-]?key|secret|password|passwd|token|authorization)\\s*[=:]\\s*)['\"]?[^\\s'\"]{6,}"),
            Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{10,}")
    };

    private SecretRedactor() { }

    public static String redact(String text) {
        if (text == null) {
            return null;
        }
        String out = text;
        for (Pattern p : PATTERNS) {
            out = p.matcher(out).replaceAll(m -> m.groupCount() >= 1 ? m.group(1) + "[REDACTED]" : "[REDACTED]");
        }
        return out;
    }

    public static boolean containsSecret(String text) {
        if (text == null) {
            return false;
        }
        for (Pattern p : PATTERNS) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}