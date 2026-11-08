# Design Decisions

| # | Decision | Why | Alternative considered |
|---|---|---|---|
| 1 | **Spring Boot monolith** | one deployable, strong ecosystem for validation/security/JPA/OpenAPI, easy to run on a small laptop; the interesting part is the engine, not distribution | microservices (operational cost with no benefit at this scale) |
| 2 | **Custom DAG engine in plain Java** | the brief asks for explicit dependency orchestration; ~600 lines are fully testable and show the mechanism; no framework hides behaviour | Spring Batch / Temporal / Camunda (powerful but heavy and would hide what is being assessed) |
| 3 | **Single-writer coordinator + worker pool** | parallel agents, but state is changed by one thread, so no lost updates on the aggregate; workers only append `AgentExecution` rows | per-task locks / optimistic versions on every row |
| 4 | **State in the database, derived views in memory** | restart recovery and inspection for free; `WorkflowSnapshot` makes the engine pure and testable | in-memory graph with periodic checkpoints |
| 5 | **Compare-and-set status transitions + transition table** | consistent states even with concurrent stop/approve/execute | `@Version` on the whole workflow row (would also conflict with child updates) |
| 6 | **Approvals as rows + gate tasks** | durable, queryable, auditable; the engine cannot pass a gate without a row in APPROVED | in-memory flags / Spring events |
| 7 | **Conditional nodes instead of branching code** | ambiguity and high-impact design gates appear only when needed, the graph shape is constant, tasks show `SKIPPED` | different workflow definitions per scenario |
| 8 | **Retry in the executor, safe-stop in the engine** | retry is a local concern (per attempt); stopping is a workflow decision | retry at the workflow level |
| 9 | **Fallback = degraded second provider after retries** | demonstrates graceful degradation distinct from retry; clearly flagged in output and audit | skipping the task |
| 10 | **Rollback = revert artifact versions** | safe and reversible, no destructive operation | restoring DB snapshots |
| 11 | **Replan supersedes, never deletes** | preserves decision lineage and lets reviewers see what changed and why | overwrite outputs |
| 12 | **Agents return maps validated at the boundary** | provider-agnostic; the same validation protects against a real LLM drifting from the schema | typed DTO per operation (safer types, much more boilerplate for a prototype) |
| 13 | **Mock keeps content separate from agents** | agents contain real logic (graph math, traceability, scans); the mock only supplies "model" text | all logic in the mock |
| 14 | **Reflection-based brownfield baseline** | honest: analyses what is actually on the classpath instead of pretending to read a repo | canned impact text |
| 15 | **URL shortener: append-only clicks + atomic counter** | cheap redirect path, no lost increments, scalable analytics source of truth | updating one wide row per click without atomicity |
| 16 | **302 redirect, soft delete, 410 for gone links** | every click observable, history kept, correct semantics for expired content | 301 / hard delete |
| 17 | **HTTP Basic with roles, config users** | enough to demonstrate *authorised* approvals without an identity provider | OAuth2/OIDC (production recommendation) |
| 18 | **H2 file by default, PostgreSQL profile** | zero setup, survives restarts, low memory | in-memory only |
| 19 | **Lombok only on entities** | removes getter/setter noise where it clearly helps; DTOs are records | Lombok everywhere |
| 20 | **Synchronous `execute`** | simple to reason about, demo and test; the call returns the new state | async with polling (listed as a production change) |