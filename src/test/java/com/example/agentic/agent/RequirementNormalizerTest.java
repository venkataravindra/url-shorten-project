package com.example.agentic.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementNormalizerTest {
    @Test
    void collapsesWhitespaceAndStripsControlCharacters() {
        assertThat(RequirementNormalizer.normalize("  Build\ta   URL\u0007 shortener \n now  ")).isEqualTo("Build a URL shortener now");
    }

    @Test
    void normalisesUnicodeCompatibilityForms() {
        assertThat(RequirementNormalizer.normalize("Ｂuild")).isEqualTo("Build");
        assertThat(RequirementNormalizer.normalize(null)).isEmpty();
    }
}