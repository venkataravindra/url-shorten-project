# Governance and Security

## Controlled autonomy

| Control | Where | Behaviour |
|---|---|---|
| Human approval gates | `assumption-approval`, `architecture-approval`, `final-approval`, replan approvals | workflow persists `WAITING_FOR_APPROVAL`; only `POST approve` by an **APPROVER** can pass the gate |
| Policy engine | `PolicyEngine` | production deployment, destructive DB operations, security-sensitive changes, protected API changes, high-impact architecture, release execution, material assumptions, high-impact replans -> `REQUIRE_APPROVAL`; shell command execution and generated-code execution -> `DENY` always |
| Change control | `ChangeControlService.authorize(workflow, action)` | called by `CodingAgent` (for the architecture's sensitive actions) and `ReleaseAgent` (release execution); throws `ApprovalRequiredException` unless an APPROVED, not-superseded request covers the action |
| Safe-stop | `SafeStopService` | policy violation, security concern, critical validation, retries exhausted, operator request, restart interruption; reason persisted; resume needs APPROVER + acknowledgement |
| Rollback | `RollbackService` | reversible artifact revert on rejection |
| Audit | `AuditService` | append-only `AuditLog` for creation, starts, task results, retries, fallbacks, approvals, safe-stops, rollbacks, replans, policy denials, fault injection |

Approvals become invalid when their basis changes: a replan supersedes granted approvals of impacted gates, so a stale
"yes" cannot authorise new work (the release agent re-checks at execution time).

## Guardrails

* **Input validation** everywhere: Bean Validation on DTOs, `SecurityValidator.validateRequirement` (length, control characters), URL validator, alias patterns, pagination bounds; unreadable bodies -> 400, wrong media type -> 415.
* **No shell execution / no generated-code execution**: there is no code path that spawns a process or compiles generated code. Requirements containing shell patterns (`rm -rf`, `curl ... | sh`, `$(...)`, backticks, ...) are rejected with 422; `SHELL_COMMAND_EXECUTION` and `GENERATED_CODE_EXECUTION` are `DENY` in the policy engine.
* **Approved workspace only**: artifacts are written by `WorkspaceService` to `workspace/{workflowId}/v{n}/{name}`; artifact names are whitelisted (`[A-Za-z0-9._-]`, no separators) and the resolved path must stay under the root (path traversal -> `SecurityConcernException`). Planned project paths are validated as relative and traversal-free.
* **Secrets**: requirements with credential patterns are rejected; every artifact is scanned before it is stored (AWS keys, `sk-` keys, private key headers, `password/token=` forms, bearer tokens); `ReviewAgent` re-scans; `SecretRedactor` scrubs audit details, failure reasons and log lines. A test injects a leaking "LLM" and shows the artifact is never stored, the workflow safe-stops and the secret appears nowhere in artifacts or audit.
* **Open redirect / SSRF-style abuse** in the shortener: scheme allow-list, host required, no embedded credentials, loopback/private literal hosts blocked, no DNS lookups, reserved aliases.
* **AuthN/Z**: HTTP Basic, stateless, BCrypt, roles ENGINEER / APPROVER / ADMIN. Redirect (`GET /api/v1/urls/{code}`), health, Swagger and the H2 console are public; everything else needs ENGINEER, approvals need APPROVER, fault injection ADMIN. Security headers: frame options same-origin (needed for the H2 console), `nosniff`.
* **Error hygiene**: consistent `ApiError` JSON; unexpected errors return a generic 500 and are logged server-side; no stack traces or SQL in responses.

## Security considerations that are *not* solved (see TRADEOFFS_AND_LIMITATIONS)

Dev credentials are defaults in `application.yml`; users are in memory; no rate limiting, no TLS termination, no CSRF
(stateless Basic API), the H2 console is enabled by default for local use (disable via `spring.h2.console.enabled=false`),
no four-eyes rule (the same approver may approve what they requested), and no per-resource authorisation (any ENGINEER sees any workflow).