# Testing Strategy

Everything below is executed by `mvn clean verify` (Surefire JVM capped at 512 MB). Last full run on the authoring machine:
**90 tests, 0 failures, 0 errors.** (Re-run it yourself; do not trust a number in a document.)

## Layers

| Layer | Classes | What they prove |
|---|---|---|
| Unit - LLM | `MockLLMProviderTest` (12) | scenario classification, per-scenario content, all 10 operations, determinism, injected failures fire exactly N times, random failures deterministic per attempt, degraded fallback |
| Unit - graph | `DependencyResolverTest` (7), `ConditionEvaluatorTest` (3) | topological order, parallel levels (test planning before implementation), join, cycle + unknown-node detection, descendants, readiness incl. STALE/SKIPPED, condition grammar |
| Unit - governance | `GovernanceUnitTest` (5), `WorkflowStatusTest` (3) | approval-required vs denied actions, shell/secret/length validation, path traversal, redaction, legal state transitions |
| Unit - agents | `AgentPipelineTest` (13), `RequirementNormalizerTest` (2) | every agent independently without database or engine: three full scenario pipelines, brownfield uses the real reflection baseline, cyclic plan rejected, unsafe paths rejected, approval required for sensitive actions, critical review finding, credential in artifact, uncovered criteria, missing release evidence |
| Unit - resilience | `ResilientAgentExecutorTest` (10) | exponential back-off values (100/200/400, cap), DB-transient retried, validation/policy/unknown **not** retried, exhaustion, fallback success, fallback failure |
| Unit - shortener | `UrlShortenerServiceTest` (10) | code generation, collision retry, duplicate alias (pre-check and insert race), expiry rules, one counter update + one click per redirect, 410 without counting, referrer parsing, open-redirect/private-host validator |
| Integration - shortener | `UrlShortenerIntegrationTest` (7) | create + 302 redirect, alias + 409, validation matrix, 404/410, deactivate/reactivate, persisted analytics (referrers, daily), pagination, auth rules |
| Integration - workflows | `WorkflowScenarioIntegrationTest` (14) | **scenario 1, 2, 3 end to end**, parallel overlap proof, approvals pause/resume, role enforcement, rejection -> rollback, security safe-stop + gated resume, retry, fallback, validation failure, secret leak, low/high-impact replan, rejected replan, requirement change, cancel, API validation, 404/405/415 |
| Integration - resilience | `ResilienceIntegrationTest` (3) | retry exhaustion -> safe-stop `MAX_RETRIES_EXCEEDED` -> human resume -> complete + MTTR sample; operator stop/resume; recovery of an interrupted workflow |
| Integration - restart | `RestartRecoveryTest` (1) | **two Spring contexts over one H2 file**: waiting workflow keeps its pending approval and completes after restart; an interrupted workflow is safe-stopped by startup recovery |

## Failure injection coverage (assignment list)

LLM timeout (`TIMEOUT`), database-related transient failure (`DATABASE_TRANSIENT` in the mock and `TransientDataAccessResourceException` in the executor test),
agent execution failure (`PROVIDER_UNAVAILABLE`, unknown exception), validation failure (`MALFORMED_OUTPUT`), approval rejection,
maximum-retry exhaustion, upstream requirement change (replan), plus credential leakage (`SECRET_LEAK`).

## Conventions

* Integration tests share one cached Spring context (`IntegrationTestBase`, profile `test`: in-memory H2, 1 ms back-off, 25 ms mock delay so parallelism is observable). Resilience tests use a second context with the fallback disabled.
* Faults are cleared after each test. No test sleeps for real back-off.
* The test profile is the only difference from production wiring; the engine, repositories, security and controllers are the real ones.

## Not covered / honest gaps

* The OpenAI adapter, Docker files and PostgreSQL profile are not exercised by any test.
* No load/performance tests, no concurrency stress test of two simultaneous `execute` calls on one workflow (a guard exists; only reasoning supports it).
* No mutation or coverage tooling is configured, so there is no coverage percentage claim.
* The brownfield "existing test coverage" is test-class *discovery* on the classpath, not line coverage.