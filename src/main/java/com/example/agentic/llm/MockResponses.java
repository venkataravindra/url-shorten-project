package com.example.agentic.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canned, scenario-dependent content for the mock LLM. Everything here is predefined text selected by keyword
 * rules; it is intentionally kept out of agents and the orchestrator.
 */
final class MockResponses {
    private MockResponses() { }

    static Map<String, Object> respond(LLMRequest req, RequirementSignals s) {
        return switch (req.operation()) {
            case ANALYZE_REQUIREMENT -> analyze(s, req.requirement());
            case IDENTIFY_AMBIGUITIES -> ambiguities(s);
            case DECOMPOSE_TASKS -> decompose(s);
            case RECOMMEND_ARCHITECTURE -> architecture(s, req.context());
            case PLAN_CODE_GENERATION -> codePlan(s);
            case GENERATE_TEST_CASES -> testCases(s, req.context());
            case REVIEW_CODE -> review(s);
            case IDENTIFY_RISKS -> risks(s);
            case GENERATE_DOCUMENTATION -> documentation(s, req.context());
            case SUMMARIZE -> summary(s, req.context());
        };
    }

    // ------------------------------------------------------------------ helpers
    static Map<String, Object> m(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    static List<Object> l(Object... items) {
        return new ArrayList<>(List.of(items));
    }

    private static Map<String, Object> req(String id, String text) { return m("id", id, "text", text); }

    private static Map<String, Object> ac(String id, String text, String reqId, String type, String status) {
        return m("id", id, "text", text, "requirementId", reqId, "testType", type, "status", status);
    }

    // ------------------------------------------------------------------ requirement analysis
    private static Map<String, Object> analyze(RequirementSignals s, String requirement) {
        List<Object> fr = new ArrayList<>();
        List<Object> nfr = new ArrayList<>();
        List<Object> acs = new ArrayList<>();
        List<Object> assumptions = new ArrayList<>();
        List<Object> missing = new ArrayList<>();
        String summary;
        if (s.ambiguous()) {
            summary = "Request to improve performance and reliability of the existing URL shortener; no measurable targets given.";
            fr.add(req("FR-1", "Improve the speed of the URL shortener (operations and metrics unspecified)"));
            fr.add(req("FR-2", "Make the URL shortener more reliable (meaning of reliability unspecified)"));
            nfr.add(m("id", "NFR-1", "text", "Performance target", "target", "UNSPECIFIED"));
            nfr.add(m("id", "NFR-2", "text", "Reliability / availability target", "target", "UNSPECIFIED"));
            acs.add(ac("AC-1", "Redirect p95 latency <= 50 ms at 500 requests/s on one instance (provisional)", "FR-1", "LOAD", "PROVISIONAL"));
            acs.add(ac("AC-2", "Redirect availability >= 99.9% over a month; measured by health probes (provisional)", "FR-2", "RELIABILITY", "PROVISIONAL"));
            acs.add(ac("AC-3", "No committed short URL is lost across an application restart (provisional)", "FR-2", "RELIABILITY", "PROVISIONAL"));
            acs.add(ac("AC-4", "A failure while recording a click never breaks the redirect (provisional)", "FR-2", "RELIABILITY", "PROVISIONAL"));
            assumptions.add("Scope is the redirect path; creation and analytics are secondary.");
            missing.addAll(List.of("Current baseline latency and throughput", "Traffic profile and peak load",
                    "Availability SLO and error budget", "Infrastructure/budget constraints"));
        } else if (s.brownfield()) {
            summary = "Enhance the existing URL shortener so expired links are deactivated automatically.";
            fr.add(req("FR-1", "Redirects to expired links keep returning HTTP 410 (existing behaviour preserved)"));
            fr.add(req("FR-2", "A scheduled job automatically deactivates links whose expiration date has passed"));
            fr.add(req("FR-3", "Existing API contracts remain backward compatible"));
            nfr.add(m("id", "NFR-1", "text", "Deactivation job must be bounded and idempotent", "target", "batch <= 500 rows per run"));
            nfr.add(m("id", "NFR-2", "text", "No downtime for the schema change", "target", "additive nullable columns only"));
            acs.add(ac("AC-1", "Given links past their expiration, when the job runs, then active=false for all of them", "FR-2", "INTEGRATION", "AGREED"));
            acs.add(ac("AC-2", "Given an expired link, redirect returns 410 before and after the job runs", "FR-1", "REGRESSION", "AGREED"));
            acs.add(ac("AC-3", "Existing request and response contracts of all /api/v1/urls endpoints are unchanged", "FR-3", "REGRESSION", "AGREED"));
            acs.add(ac("AC-4", "Running the job twice changes nothing the second time and never exceeds the batch size", "FR-2", "UNIT", "AGREED"));
            assumptions.add("Expiration timestamps are stored in UTC.");
        } else {
            summary = "Build a URL shortener" + (s.alias() ? " with custom aliases" : "") + (s.expiration() ? ", expiration" : "")
                    + (s.redirect() ? ", redirects" : "") + (s.analytics() ? " and click analytics." : ".");
            fr.add(req("FR-1", "Create short URLs from valid http/https URLs"));
            acs.add(ac("AC-1", "Given a valid URL, POST /api/v1/urls returns 201 and a unique collision-resistant code", "FR-1", "INTEGRATION", "AGREED"));
            int n = 2;
            if (s.redirect() || s.url()) {
                fr.add(req("FR-" + n, "Redirect a short code to the original URL"));
                acs.add(ac("AC-" + n, "Given an active, unexpired code, GET returns 302 with the original URL", "FR-" + n, "INTEGRATION", "AGREED"));
                n++;
            }
            if (s.alias()) {
                fr.add(req("FR-" + n, "Support unique custom aliases"));
                acs.add(ac("AC-" + n, "Given a free alias it is used; a duplicate alias returns 409", "FR-" + n, "INTEGRATION", "AGREED"));
                n++;
            }
            if (s.expiration()) {
                fr.add(req("FR-" + n, "Support expiration dates"));
                acs.add(ac("AC-" + n, "Given an expired link, redirect returns 410 and past dates are rejected on creation", "FR-" + n, "INTEGRATION", "AGREED"));
                n++;
            }
            if (s.analytics()) {
                fr.add(req("FR-" + n, "Track clicks with timestamps and referrer, and expose analytics"));
                acs.add(ac("AC-" + n, "Each redirect increments the click count; analytics returns totals, referrers and daily clicks", "FR-" + n, "INTEGRATION", "AGREED"));
                n++;
            }
            nfr.add(m("id", "NFR-1", "text", "Only http/https targets (open-redirect prevention)", "target", "100% of other schemes rejected"));
            nfr.add(m("id", "NFR-2", "text", "Redirect path should avoid unnecessary DB calls", "target", "<= 3 statements per redirect"));
            acs.add(ac("AC-" + n, "javascript:, data: and file: URLs are rejected with 400", "NFR-1", "UNIT", "AGREED"));
            assumptions.add("Single region, relational database, no authentication on the public redirect.");
        }
        return m("scenario", s.scenario(), "summary", summary, "normalizedRequirement", requirement,
                "functionalRequirements", fr, "nonFunctionalRequirements", nfr, "acceptanceCriteria", acs,
                "assumptions", assumptions, "missingInformation", missing);
    }

    private static Map<String, Object> ambiguities(RequirementSignals s) {
        List<Object> list = new ArrayList<>();
        if (s.ambiguous()) {
            list.add(m("id", "AMB-1", "topic", "Performance targets", "severity", "BLOCKING",
                    "question", "Which operations (redirect, creation, analytics) are in scope, and what p95/p99 latency and throughput are required?",
                    "provisionalAssumption", "Redirect p95 <= 50 ms at 500 requests/s on a single instance"));
            list.add(m("id", "AMB-2", "topic", "Meaning of reliable", "severity", "BLOCKING",
                    "question", "Does reliable mean an availability SLO, durability of data, or graceful degradation (or all three)?",
                    "provisionalAssumption", "99.9% monthly availability for redirects and no loss of committed URL records"));
            list.add(m("id", "AMB-3", "topic", "Infrastructure constraints", "severity", "BLOCKING",
                    "question", "May new infrastructure such as a shared cache be introduced, or must we stay on the current database only?",
                    "provisionalAssumption", "A shared cache for redirect lookups may be introduced"));
            list.add(m("id", "AMB-4", "topic", "Baseline measurements", "severity", "MINOR",
                    "question", "Do baseline measurements exist?",
                    "provisionalAssumption", "A baseline load test is run before any change"));
        } else if (s.brownfield()) {
            list.add(m("id", "AMB-1", "topic", "Deactivation cadence", "severity", "MINOR",
                    "question", "How soon after expiry must a link be deactivated?",
                    "provisionalAssumption", "Within 5 minutes via a scheduled job"));
            list.add(m("id", "AMB-2", "topic", "Grace period", "severity", "MINOR",
                    "question", "Is there a grace period after expiry?", "provisionalAssumption", "No grace period"));
        } else {
            list.add(m("id", "AMB-1", "topic", "Alias rules", "severity", "MINOR",
                    "question", "Allowed alias length and characters?", "provisionalAssumption", "3-32 chars of [A-Za-z0-9_-]"));
            list.add(m("id", "AMB-2", "topic", "Expiration timezone", "severity", "MINOR",
                    "question", "Which timezone applies to expiration dates?", "provisionalAssumption", "UTC"));
            list.add(m("id", "AMB-3", "topic", "Redirect status code", "severity", "MINOR",
                    "question", "301 or 302?", "provisionalAssumption", "302 so every click is counted"));
        }
        return m("ambiguities", list);
    }

    // ------------------------------------------------------------------ planning
    private static Map<String, Object> task(String id, String title, String category, int hours, String... deps) {
        return m("id", id, "title", title, "category", category, "estimateHours", hours, "dependsOn", List.of(deps));
    }

    private static Map<String, Object> decompose(RequirementSignals s) {
        List<Object> t = new ArrayList<>();
        if (s.ambiguous()) {
            t.add(task("P1", "Establish baseline with a load test", "MEASUREMENT", 4));
            t.add(task("P2", "Profile the redirect path", "MEASUREMENT", 3));
            t.add(task("P3", "Add a redirect lookup cache", "PERFORMANCE", 8, "P2"));
            t.add(task("P4", "Record clicks asynchronously", "PERFORMANCE", 6, "P2"));
            t.add(task("P5", "Review database indexes and queries", "PERFORMANCE", 3, "P1"));
            t.add(task("P6", "Add timeouts and graceful degradation", "RELIABILITY", 6, "P1"));
            t.add(task("P7", "Add liveness and readiness probes", "RELIABILITY", 3, "P6"));
            t.add(task("P8", "Run load and fault tests against the agreed targets", "VALIDATION", 8, "P3", "P4", "P5", "P7"));
        } else if (s.brownfield()) {
            t.add(task("B1", "Analyse the existing URL module", "ANALYSIS", 3));
            t.add(task("B2", "Design the expiry scan and additive schema change", "DESIGN", 3, "B1"));
            t.add(task("B3", "Add a bounded bulk-deactivate repository query", "IMPLEMENTATION", 4, "B2"));
            t.add(task("B4", "Add the scheduled deactivation job", "IMPLEMENTATION", 5, "B3"));
            t.add(task("B5", "Keep redirect expiry check consistent", "IMPLEMENTATION", 2, "B2"));
            t.add(task("B6", "Write regression and job tests", "TESTING", 6, "B4", "B5"));
            t.add(task("B7", "Document rollout and rollback", "DOCUMENTATION", 2, "B6"));
        } else {
            t.add(task("T1", "Project skeleton and configuration", "SETUP", 2));
            t.add(task("T2", "Data model and constraints", "IMPLEMENTATION", 3, "T1"));
            t.add(task("T3", "Short code generator", "IMPLEMENTATION", 2, "T1"));
            t.add(task("T4", "Create-URL API with validation", "IMPLEMENTATION", 4, "T2", "T3"));
            t.add(task("T5", "Redirect endpoint", "IMPLEMENTATION", 3, "T2"));
            if (s.alias()) t.add(task("T6", "Custom alias handling", "IMPLEMENTATION", 2, "T4"));
            if (s.expiration()) t.add(task("T7", "Expiration handling", "IMPLEMENTATION", 2, "T4", "T5"));
            if (s.analytics()) t.add(task("T8", "Click tracking and analytics API", "IMPLEMENTATION", 5, "T5"));
            t.add(task("T9", "Error handling and OpenAPI", "IMPLEMENTATION", 2, "T4", "T5"));
            t.add(task("T10", "Automated tests", "TESTING", 6, "T9"));
        }
        return m("tasks", t);
    }

    // ------------------------------------------------------------------ architecture
    @SuppressWarnings("unchecked")
    private static Map<String, Object> architecture(RequirementSignals s, Map<String, Object> ctx) {
        List<Object> components = l(m("name", "UrlShortenerController", "responsibility", "Thin REST layer"),
                m("name", "UrlShortenerService", "responsibility", "Business rules and transactions"),
                m("name", "ShortUrlRepository / UrlClickRepository", "responsibility", "Spring Data JPA persistence"));
        List<Object> decisions = l(m("id", "AD-1", "title", "Layered Spring Boot monolith",
                "rationale", "Smallest operational footprint that meets the requirement",
                "alternatives", "Microservices", "tradeoffs", "Simpler deployment, less independent scaling"));
        List<Object> approvalActions = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        if (s.ambiguous()) {
            components.add(m("name", "RedirectCache", "responsibility", "Shared cache in front of redirect lookups"));
            components.add(m("name", "AsyncClickRecorder", "responsibility", "Buffered click writes off the redirect path"));
            decisions.add(m("id", "AD-2", "title", "Introduce a shared cache for redirects",
                    "rationale", "Redirects are read-heavy; caching removes the DB read from the hot path",
                    "alternatives", "In-process cache; read replicas", "tradeoffs", "New infrastructure and invalidation complexity"));
            approvalActions.add("HIGH_IMPACT_ARCHITECTURE_CHANGE");
        } else if (s.brownfield()) {
            components.add(m("name", "ExpiredUrlDeactivationScheduler", "responsibility", "Scheduled bounded batch deactivation"));
            decisions.add(m("id", "AD-2", "title", "Additive schema change with a scheduled job",
                    "rationale", "Nullable columns and a bounded job keep the change backward compatible",
                    "alternatives", "DB-level TTL; lazy-only expiry", "tradeoffs", "Job lag versus simplicity"));
            out.put("changeImpact", impact(ctx));
        } else {
            decisions.add(m("id", "AD-2", "title", "Base62 random codes with DB unique constraint",
                    "rationale", "Collision-resistant and non-enumerable; the constraint is the source of truth",
                    "alternatives", "Sequential ids encoded in base62", "tradeoffs", "Retry on rare collision"));
        }
        if (s.insecure()) {
            approvalActions.add("SECURITY_SENSITIVE_CHANGE");
        }
        out.put("style", "Layered Spring Boot application with relational persistence");
        out.put("components", components);
        out.put("dataModel", l(m("entity", "ShortUrl", "notes", "unique shortCode, active, expirationDate, clickCount"),
                m("entity", "UrlClick", "notes", "append-only, indexed by (short_url_id, accessedAt)")));
        out.put("decisions", decisions);
        out.put("approvalActions", approvalActions);
        out.put("highImpact", !approvalActions.isEmpty());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> impact(Map<String, Object> ctx) {
        Object baselineObj = ctx.get("baseline");
        Map<String, Object> baseline = baselineObj instanceof Map<?, ?> b ? (Map<String, Object>) b : Map.of();
        List<Object> apis = new ArrayList<>();
        List<Object> entities = new ArrayList<>();
        List<Object> services = new ArrayList<>();
        List<Object> repos = new ArrayList<>();
        for (Object c : (List<Object>) baseline.getOrDefault("controllers", List.of())) {
            for (Object e : (List<Object>) ((Map<String, Object>) c).getOrDefault("endpoints", List.of())) {
                String ep = e.toString();
                String note = ep.startsWith("POST") ? "No contract change; expirationDate semantics unchanged"
                        : ep.contains("analytics") || ep.contains("details") ? "Unaffected"
                        : ep.endsWith("/api/v1/urls") ? "Listing may show active=false after the job runs"
                        : ep.startsWith("GET") ? "No contract change; expired links already return 410"
                        : "Unaffected";
                apis.add(m("endpoint", ep, "impact", note));
            }
        }
        for (Object e : (List<Object>) baseline.getOrDefault("entities", List.of())) {
            Map<String, Object> ent = (Map<String, Object>) e;
            if (((List<Object>) ent.getOrDefault("fields", List.of())).contains("expirationDate")) {
                entities.add(m("name", ent.get("name"), "change", "Add nullable deactivatedAt and deactivationReason (additive)"));
            }
        }
        for (Object sv : (List<Object>) baseline.getOrDefault("services", List.of())) {
            if (sv.toString().contains("UrlShortener")) services.add(m("name", sv, "change", "Add deactivateExpired(batchSize)"));
        }
        for (Object r : (List<Object>) baseline.getOrDefault("repositories", List.of())) {
            if (r.toString().contains("ShortUrl")) repos.add(m("name", r, "change", "Add bounded bulk update on the (active, expirationDate) index"));
        }
        boolean discoverable = Boolean.TRUE.equals(baseline.get("testsDiscoverable"));
        Map<String, Object> coverage = discoverable
                ? m("discoveredTests", baseline.get("existingTests"), "note", "Test classes found on the classpath; line coverage not measured")
                : m("note", "Test classes are not on the runtime classpath, so existing coverage is unknown. Run mvn test.");
        return m("baselineAnalysed", !baseline.isEmpty(), "impactedApis", apis, "impactedEntities", entities,
                "impactedServices", services, "impactedRepositories", repos,
                "schedulerAlreadyPresent", baseline.getOrDefault("schedulerPresent", false),
                "existingTestCoverage", coverage, "backwardCompatible", true,
                "regressionRisks", l("Job could deactivate links that a user just extended (race on expirationDate)",
                        "Large backlog on first run could lock rows; bounded batches mitigate this",
                        "Clients may assume active=true until expirationDate; list responses now flip earlier"));
    }

    // ------------------------------------------------------------------ code generation plan
    private static Map<String, Object> file(String path, String action, String purpose) {
        return m("path", path, "action", action, "purpose", purpose);
    }

    private static Map<String, Object> codePlan(RequirementSignals s) {
        String base = "src/main/java/com/example/shortener/";
        List<Object> files = new ArrayList<>();
        List<Object> steps = new ArrayList<>();
        if (s.ambiguous()) {
            files.add(file(base + "cache/RedirectCache.java", "CREATE", "Cache-aside lookup for redirects"));
            files.add(file(base + "service/AsyncClickRecorder.java", "CREATE", "Bounded queue and batch insert of clicks"));
            files.add(file(base + "config/ResilienceConfig.java", "CREATE", "Timeouts and graceful degradation"));
            files.add(file(base + "service/UrlShortenerService.java", "MODIFY", "Use the cache and async recorder"));
            steps.addAll(List.of("Measure baseline", "Add cache behind a feature flag", "Move click writes off the hot path", "Validate against agreed targets"));
        } else if (s.brownfield()) {
            files.add(file(base + "scheduler/ExpiredUrlDeactivationScheduler.java", "CREATE", "@Scheduled bounded batch job"));
            files.add(file(base + "repository/ShortUrlRepository.java", "MODIFY", "Bulk deactivate query"));
            files.add(file(base + "service/UrlShortenerService.java", "MODIFY", "deactivateExpired(batchSize)"));
            files.add(file(base + "entity/ShortUrl.java", "MODIFY", "Nullable deactivatedAt, deactivationReason"));
            steps.addAll(List.of("Add columns", "Add repository query", "Add job behind a feature flag", "Run regression suite"));
        } else {
            files.add(file(base + "entity/ShortUrl.java", "CREATE", "Aggregate with unique short code"));
            files.add(file(base + "repository/ShortUrlRepository.java", "CREATE", "Persistence"));
            files.add(file(base + "service/UrlShortenerService.java", "CREATE", "Create, resolve, analytics"));
            files.add(file(base + "controller/UrlShortenerController.java", "CREATE", "REST endpoints"));
            files.add(file(base + "exception/GlobalExceptionHandler.java", "CREATE", "Consistent error responses"));
            steps.addAll(List.of("Model and constraints", "Service layer", "Controller", "Tests"));
        }
        if (s.insecure()) {
            files.add(file("src/main/java/com/example/shortener/config/SecurityConfig.java", "MODIFY",
                    "Permit all admin endpoints without authentication (as literally requested)"));
        }
        return m("files", files, "steps", steps, "executionPolicy", "PLAN_ONLY: generated code is never compiled or executed by the platform",
                "rollbackPlan", "Revert the change set; drop only the additive columns if they were added");
    }

    // ------------------------------------------------------------------ tests
    @SuppressWarnings("unchecked")
    private static Map<String, Object> testCases(RequirementSignals s, Map<String, Object> ctx) {
        List<Object> cases = new ArrayList<>();
        int n = 1;
        for (Object o : (List<Object>) ctx.getOrDefault("acceptanceCriteria", List.of())) {
            Map<String, Object> ac = (Map<String, Object>) o;
            cases.add(m("id", "TC-" + n++, "type", ac.getOrDefault("testType", "UNIT"),
                    "name", "Verifies " + ac.get("id"), "covers", List.of(ac.get("id")),
                    "description", String.valueOf(ac.get("text"))));
        }
        cases.add(m("id", "TC-" + n++, "type", "UNIT", "name", "Rejects blank and malformed input", "covers", List.of(),
                "description", "Negative-path validation tests"));
        if (s.ambiguous()) {
            cases.add(m("id", "TC-" + n, "type", "CHAOS", "name", "Kill the database connection during redirects",
                    "covers", List.of(), "description", "Redirect degrades gracefully and recovers"));
        } else if (s.brownfield()) {
            cases.add(m("id", "TC-" + n, "type", "REGRESSION", "name", "Existing endpoint smoke suite",
                    "covers", List.of(), "description", "All pre-existing integration tests still pass"));
        }
        return m("strategy", "Test pyramid: unit for rules, integration for APIs"
                + (s.ambiguous() ? ", plus load and fault-injection tests" : s.brownfield() ? ", plus regression" : ""),
                "testCases", cases);
    }

    // ------------------------------------------------------------------ review and risks
    private static Map<String, Object> review(RequirementSignals s) {
        List<Object> findings = new ArrayList<>();
        findings.add(m("id", "RV-1", "severity", "LOW", "category", "MAINTAINABILITY",
                "message", "Keep controllers thin; business rules live in the service", "recommendation", "No action"));
        findings.add(m("id", "RV-2", "severity", "MEDIUM", "category", "RELIABILITY",
                "message", "Add structured logging with correlation ids", "recommendation", "Add MDC fields"));
        if (s.insecure()) {
            findings.add(m("id", "RV-3", "severity", "CRITICAL", "category", "SECURITY",
                    "message", "The plan removes authentication or hard-codes credentials for admin endpoints",
                    "recommendation", "Reject the change; use role-based access and externalised secrets"));
        }
        return m("findings", findings, "verdict", s.insecure() ? "REJECT" : "APPROVE_WITH_COMMENTS");
    }

    private static Map<String, Object> risks(RequirementSignals s) {
        List<Object> r = new ArrayList<>();
        if (s.ambiguous()) {
            r.add(risk("R-1", "PERFORMANCE", "HIGH", "Targets are provisional; wrong targets waste effort", "Obtain sign-off on assumptions"));
            r.add(risk("R-2", "RELIABILITY", "MEDIUM", "Cache outage could take down redirects", "Fall back to the database on cache failure"));
            r.add(risk("R-3", "DATA", "MEDIUM", "Async click recording can lose clicks on crash", "Bounded queue and periodic flush; document the loss window"));
        } else if (s.brownfield()) {
            r.add(risk("R-1", "REGRESSION", "MEDIUM", "Job changes visible state of existing links", "Feature flag and regression tests"));
            r.add(risk("R-2", "DATA", "LOW", "Schema change on a large table", "Additive nullable columns only"));
        } else {
            r.add(risk("R-1", "SECURITY", "MEDIUM", "Open redirect or abusive targets", "Scheme allow-list and private-host blocking"));
            r.add(risk("R-2", "SCALABILITY", "LOW", "Click table grows without bound", "Partition or roll up later"));
        }
        if (s.insecure()) {
            r.add(risk("R-9", "SECURITY", "CRITICAL", "Requirement asks to weaken authentication", "Do not implement; escalate to a human"));
        }
        return m("risks", r);
    }

    private static Map<String, Object> risk(String id, String category, String severity, String description, String mitigation) {
        return m("id", id, "category", category, "severity", severity, "description", description, "mitigation", mitigation);
    }

    // ------------------------------------------------------------------ documentation and summary
    @SuppressWarnings("unchecked")
    private static Map<String, Object> documentation(RequirementSignals s, Map<String, Object> ctx) {
        Map<String, Object> facts = ctx.get("facts") instanceof Map<?, ?> f ? (Map<String, Object>) f : Map.of();
        StringBuilder readme = new StringBuilder("# ").append(facts.getOrDefault("headline", "Engineering change")).append("\n\n");
        readme.append("Scenario: ").append(s.scenario()).append("\n\n## Requirements\n");
        for (Object x : (List<Object>) facts.getOrDefault("requirements", List.of())) readme.append("- ").append(x).append('\n');
        readme.append("\n## Planned files\n");
        for (Object x : (List<Object>) facts.getOrDefault("plannedFiles", List.of())) readme.append("- ").append(x).append('\n');
        String runbook = "# Runbook\n\n- Health: GET /api/v1/health\n- Rollback: revert the change set"
                + (s.brownfield() ? "; disable the scheduler feature flag first" : "") + "\n";
        List<Object> docs = l(m("name", "change-documentation.md", "title", "Change documentation", "content", readme.toString()),
                m("name", "runbook.md", "title", "Operations runbook", "content", runbook));
        return m("documents", docs);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summary(RequirementSignals s, Map<String, Object> ctx) {
        Map<String, Object> f = ctx.get("facts") instanceof Map<?, ?> x ? (Map<String, Object>) x : Map.of();
        List<Object> sections = l(
                m("title", "Outcome", "body", s.scenario() + " workflow finished; release readiness is " + f.getOrDefault("readiness", "UNKNOWN") + "."),
                m("title", "Plan", "body", f.getOrDefault("taskCount", 0) + " engineering tasks, critical path " + f.getOrDefault("criticalPathHours", 0) + "h."),
                m("title", "Quality", "body", f.getOrDefault("testCaseCount", 0) + " planned test cases, acceptance coverage " + f.getOrDefault("coverage", "n/a") + "."),
                m("title", "Risk", "body", f.getOrDefault("riskCount", 0) + " risks recorded; review verdict " + f.getOrDefault("verdict", "n/a") + "."),
                m("title", "Governance", "body", "Approvals granted: " + f.getOrDefault("approvals", "none") + ". Generated code was never executed."));
        return m("headline", "Engineering summary (" + s.scenario() + ")", "sections", sections,
                "openItems", l("Review simulated content before acting on it", "Production deployment requires a separate manual approval"));
    }
}