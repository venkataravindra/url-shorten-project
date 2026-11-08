# Interview and Demo Guide

## A. Run it (3 commands)

```bash
mvn clean verify                                   # optional: ~2 min, runs all tests
mvn -DskipTests package
java -Xmx384m -XX:+UseSerialGC -jar target/agentic-engineering-platform-1.0.0.jar
```
Open http://localhost:8080/swagger-ui.html, click **Authorize**, enter `approver` / `approver123`.
Second terminal: `powershell -ExecutionPolicy Bypass -File scripts/demo.ps1 -Pause`.

## B. Recording a 6-minute execution video (Windows)

Tools: **Win+G** (Xbox Game Bar -> Record) or OBS Studio. Terminal font large, two windows side by side
(server log on the left, demo script or Swagger on the right). Narrate; `-Pause` lets you talk between sections.

| Time | Show | Say |
|---|---|---|
| 0:00 | README diagram, `mvn -q test` result or `target/surefire-reports` | "Spring Boot platform that turns a requirement into reviewable engineering output with a DAG of agents. The LLM is a labelled simulation so everything is reproducible offline." |
| 0:40 | start the jar (384 MB heap), Swagger UI | "Runs on a small laptop, no API key." |
| 1:10 | demo script: URL shortener create, 302 redirect, analytics | "The engineered app: aliases, expiry, 410 for gone links, atomic click counting." |
| 2:00 | Scenario 1, task table | "implementation and test-planning ran in parallel; security-review joins documentation and validation; the human final approval pauses the workflow and it resumes on approve." |
| 3:00 | Scenario 2, change-impact JSON | "Brownfield: the architect agent reflection-scans this app's real URL module - entities, endpoints, repositories - and reports impact, compatibility and regression risks." |
| 3:45 | Scenario 3, three pending approvals | "Ambiguous: no plan exists until a human approves the assumptions; the cache design needs a second approval." |
| 4:45 | retry section, safe-stop section | "Two injected timeouts -> 200 ms then 400 ms backoff. A critical security finding safe-stops; only an approver with an acknowledgement can resume." |
| 5:30 | replan, `metrics/summary` | "Replan preserves unaffected work and supersedes decisions; metrics come from persisted rows." |

## C. Suggested live-demo order for an interviewer

1. Greenfield (shows the DAG and the pause/resume). 2. Ambiguous (shows governance). 3. Fault injection + safe-stop.
4. Replan. 5. Kill the process mid-approval, restart, approve (state survives).

## D. Interview questions with implementation-specific answers

**1. Why Spring Boot?** Validation, security, JPA, OpenAPI and test support are one dependency each, it runs as one small jar, and the thing under assessment is the orchestration design, not plumbing. Trade-off: monolith, single-instance guard.

**2. Why a DAG instead of a linear workflow?** Test planning needs only the architecture, not the implementation; a chain would serialise them. A DAG gives parallelism, join points, conditional nodes (`assumption-approval` only when ambiguous) and precise impact analysis for replanning (`DependencyResolver.descendants`).

**3. How does parallel execution work?** `WorkflowEngine` finds ready tasks (all dependencies COMPLETED/SKIPPED), submits each to a bounded pool (`WorkflowScheduler`, `agentic.orchestration.parallelism`), then blocks on a `CompletionService`. A test proves two agent executions overlap in time. Only the coordinator thread mutates state, so no races on the workflow aggregate.

**4. Why a single-writer coordinator?** It removes lost updates without distributed locks; workers only insert append-only `AgentExecution` rows. Cost: coordinator is a serial section (fine here, tasks are long, state changes tiny).

**5. How is workflow state persisted and recovered?** Every transition is a small transaction on `WorkflowExecution`/`WorkflowTask`; outputs are JSON on the task. The in-memory `WorkflowSnapshot` is rebuilt from rows. On startup, workflows stuck in RUNNING/RETRYING get their in-flight tasks reset and are safe-stopped; waiting ones need nothing. `RestartRecoveryTest` restarts two contexts on one H2 file.

**6. How do you keep state transitions consistent?** A transition table in `WorkflowStatus` plus compare-and-set SQL (`UPDATE ... WHERE status = :from`) in `WorkflowStateStore.transition`; illegal moves raise `InvalidWorkflowStateException` (409).

**7. How can the LLM provider be replaced?** Agents depend on `LLMProvider.complete()`. `LlmConfig` picks the bean from `agentic.llm.provider`. No agent or engine change. There is an OpenAI adapter, but I never ran it against the real API.

**8. Why is a mock LLM enough for the prototype?** The assignment evaluates orchestration, governance and judgement. A deterministic mock makes scenarios, failures and approvals reproducible and assertable offline. It is labelled a simulation in every response.

**9. How do you stop the mock from being "the orchestrator in disguise"?** Agents contain real logic: graph validation, cycle detection, critical path, traceability coverage, secret scanning, policy checks, reflection-based codebase analysis. The mock only supplies the model-like text, in a separate class.

**10. How is human approval enforced?** Gates are DAG nodes. The engine creates an `ApprovalRequest` row and the task sits in `WAITING_APPROVAL`; nothing downstream is ready. Only `POST /approve` by an APPROVER (checked in `SecurityConfig`) completes it. Agents re-check via `ChangeControlService` (`CodingAgent`, `ReleaseAgent`), so skipping the API is not enough.

**11. What happens when an approval is rejected?** `RollbackService`: staged artifacts -> REVERTED (previous versions restored), pending approvals withdrawn, workflow ROLLED_BACK, audited.

**12. How does dynamic replanning work?** `propose` computes impacted (changed + descendants) and preserved sets and validates the revised graph is acyclic; high-impact types create a `HIGH_IMPACT_REPLAN` approval holding the exact proposal; `apply` marks impacted tasks STALE (revision+1), supersedes their decisions/artifacts/approvals, returns the workflow to CREATED, and the engine re-runs only STALE work. Rejection discards the proposal.

**13. Why supersede instead of overwrite?** Auditability and decision lineage - reviewers can see what changed and why; also lets rollback restore a prior artifact version.

**14. How do retries work?** `ResilientAgentExecutor`: only TRANSIENT failures (timeouts, 503, Spring `TransientDataAccessException`) are retried, up to `max-retries`, delay `initial * multiplier^n` capped; each attempt is a persisted row. Validation, policy and unknown errors are never retried (`FailureClassifier`). Idempotency comes from pure agents and coordinator-only persistence.

**15. What is the difference between retry, fallback, rollback and safe-stop?** Retry repeats the same call; fallback switches to a degraded provider after retries; rollback reverts staged output after a human rejection; safe-stop freezes the workflow for policy/security/critical/exhaustion cases and needs a human to resume.

**16. When does the system safe-stop?** Policy violation or missing approval, security concern (secret in output, critical review finding, path escape), critical validation failure, exhausted retries, operator request, restart interruption. Reason persisted, redacted; resume needs APPROVER + acknowledgement.

**17. How do guardrails prevent unsafe execution?** No code path starts a process or compiles generated code; `SHELL_COMMAND_EXECUTION`/`GENERATED_CODE_EXECUTION` are DENY; requirements with shell patterns are rejected; artifacts only go through `WorkspaceService` with whitelisted names and root confinement; secrets are scanned on input, on artifact store and in review, and redacted from logs/audit.

**18. How are partial failures handled?** Completed tasks stay COMPLETED and durable; the failing task records kind/reason/attempts; independent in-flight tasks finish and are persisted; nothing new is scheduled; on resume only FAILED/interrupted tasks re-run.

**19. How do the three scenarios differ?** Same graph, different data. Greenfield: no gates except final. Brownfield: architecture runs `CodebaseAnalyzer` and returns change impact. Ambiguous: blocking ambiguities activate `assumption-approval` (no plan before approval) and the cache design activates `architecture-approval`.

**20. How is the ambiguous scenario kept from silently becoming decisions?** Ambiguities carry a severity; any BLOCKING one sets `blockingAmbiguity`, the conditional gate becomes active, `planning` depends on it, acceptance criteria are marked PROVISIONAL, and `TestingAgent` validation fails if they were never approved.

**21. How is the brownfield analysis honest?** `CodebaseAnalyzer` scans the running classpath (entities+fields, endpoints, services, repositories, `@Scheduled`, test classes). It reports `schedulerPresent=false` and "test classes not discoverable" when that is true; it never claims to read source it did not read.

**22. How does the URL shortener avoid collisions?** SecureRandom base62 (7 chars), pre-check, then the DB unique constraint decides; a violation retries in a new transaction (`ShortUrlWriter`). Custom aliases surface the same constraint as 409.

**23. How do you prevent open redirects?** Allow-list of http/https, mandatory host, no userinfo, no control chars, loopback/private literal hosts blocked, no DNS lookups; target stored as validated URI string and only ever used as the `Location` of a 302 the owner created.

**24. Why is the redirect path cheap, and how would analytics scale?** One SELECT, one atomic counter UPDATE, one INSERT. Clicks are append-only and indexed; today's `GROUP BY` can move to rollups/stream processing and an async writer without changing the API.

**25. How are metrics computed and what is MTTR here?** From persisted rows (workflows, tasks, agent executions, approvals, audit) at request time. MTTR = first failure/safe-stop event to next completion; includes human reaction time, so it is an operator-latency indicator in a prototype, not an automated-recovery measure.

**26. What would change before production?** Identity provider and per-resource authz; Flyway; async execution with a durable queue and DB leases (the in-JVM guard breaks with multiple instances); Micrometer/tracing; rate limiting; Redis/Caffeine cache and async click ingestion; secrets manager; a real model with prompt/version control and evals; load/chaos/security testing.

**27. What are you least happy with?** Synchronous `execute`, the keyword-based mock classification, schema via `ddl-auto`, and that Docker/PostgreSQL/OpenAI paths are unverified.

**28. How did you test failure behaviour?** A programmable `FaultInjector` makes the mock fail N times in specific ways (timeout, 503, DB-transient, malformed, secret leak); unit tests cover the executor in isolation, integration tests assert the resulting workflow states, audit events and metrics.

**29. How do you protect against a malicious or wrong LLM answer?** Output is validated at the agent boundary (required fields, id/estimate checks, graph acyclicity, path safety), secrets are scanned, critical findings stop the workflow, and no output is ever executed.

**30. Why 302 and 410?** 302 so each click reaches the server (counted, deactivation immediate); 410 tells clients the resource existed and is intentionally gone, unlike 404.