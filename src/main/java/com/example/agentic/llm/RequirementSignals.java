package com.example.agentic.llm;

import java.util.Locale;
import java.util.regex.Pattern;

/** Keyword signals extracted from a requirement. This is how the mock "understands" input: rules, not reasoning. */
record RequirementSignals(String text, boolean url, boolean alias, boolean expiration, boolean redirect,
                          boolean analytics, boolean deactivation, boolean performance, boolean reliability,
                          boolean existing, boolean vague, boolean hasNumbers, boolean insecure, String scenario) {

    private static final Pattern VAGUE = Pattern.compile(
            "improve|better|faster|more reliable|reliab|robust|scalab|efficien|optimi[sz]e|performance|resilien");
    private static final Pattern EXISTING = Pattern.compile("existing|enhance|modify|extend|refactor|upgrade|legacy");
    private static final Pattern INSECURE = Pattern.compile(
            "disable (the )?(auth|authentication|security)|hard-?code[a-z ]*(password|secret|credential)|bypass (auth|security)");

    static RequirementSignals of(String requirement) {
        String t = requirement == null ? "" : requirement.toLowerCase(Locale.ROOT);
        boolean alias = t.contains("alias") || t.contains("custom");
        boolean expiration = t.contains("expir");
        boolean redirect = t.contains("redirect");
        boolean analytics = t.contains("analytic") || t.contains("click");
        boolean deactivation = t.contains("deactivat");
        boolean concreteFeature = alias || expiration || redirect || analytics || deactivation;
        boolean vague = VAGUE.matcher(t).find();
        boolean numbers = t.matches(".*\\d.*");
        boolean existing = EXISTING.matcher(t).find();
        String scenario;
        if (vague && !numbers && !concreteFeature) {
            scenario = "AMBIGUOUS";
        } else if (existing) {
            scenario = "BROWNFIELD";
        } else {
            scenario = "GREENFIELD";
        }
        return new RequirementSignals(t, t.contains("url") || t.contains("short"), alias, expiration, redirect,
                analytics, deactivation, t.contains("performance") || t.contains("faster") || t.contains("latency"),
                t.contains("reliab") || t.contains("resilien") || t.contains("availab"), existing, vague, numbers,
                INSECURE.matcher(t).find(), scenario);
    }

    boolean ambiguous() { return scenario.equals("AMBIGUOUS"); }
    boolean brownfield() { return scenario.equals("BROWNFIELD"); }
    boolean greenfield() { return scenario.equals("GREENFIELD"); }
}