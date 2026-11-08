package com.example.agentic.agent;

import java.text.Normalizer;

/** Cleans free-text requirements so downstream rules see a consistent string. */
public final class RequirementNormalizer {
    private RequirementNormalizer() { }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        s = s.replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }
}