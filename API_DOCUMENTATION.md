# API Documentation

Interactive docs: Swagger UI `/swagger-ui.html`, OpenAPI `/v3/api-docs`. Auth is HTTP Basic (see README for users).
Errors share one shape: `{timestamp,status,error,code,message,path,details[]}`.

## URL shortener

| Method & path | Auth | Success | Errors |
|---|---|---|---|
| `POST /api/v1/urls` | ENGINEER | **201** + `Location` + body | 400 invalid URL/alias/expiry, 409 `ALIAS_ALREADY_EXISTS`, 401 |
| `GET /api/v1/urls/{shortCode}` | **public** | **302** `Location: <original>`, `Cache-Control: no-store`; records click | 404 unknown, **410** `SHORT_URL_EXPIRED` / `SHORT_URL_INACTIVE` |
| `GET /api/v1/urls/{shortCode}/analytics` | ENGINEER | 200 | 404 |
| `GET /api/v1/urls/{shortCode}/details` | ENGINEER | 200 | 404 |
| `GET /api/v1/urls?page=0&size=20&active=true` | ENGINEER | 200 paged (`size` 1-100, newest first) | 400 |
| `PUT /api/v1/urls/{shortCode}/status` `{"active":true}` | ENGINEER | 200 | 404, 409 `URL_EXPIRED` (expired links cannot be reactivated) |
| `DELETE /api/v1/urls/{shortCode}` | ENGINEER | **204** soft deactivation, idempotent | 404 |

```json
POST /api/v1/urls
{ "originalUrl": "https://example.com/articles/spring-boot", "customAlias": "spring-guide", "expirationDate": "2027-01-01T00:00:00" }
-> 201 { "shortCode":"spring-guide", "shortUrl":"http://localhost:8080/api/v1/urls/spring-guide", "originalUrl":"...",
         "customAlias":true, "active":true, "expired":false, "createdAt":"...", "expirationDate":"2027-01-01T00:00:00", "clickCount":0 }

GET /api/v1/urls/spring-guide/analytics
-> { "shortCode":"spring-guide", "totalClicks":3, "firstAccessedAt":"...", "lastAccessedAt":"...",
     "referrers":[{"referrer":"news.example.org","clicks":2},{"referrer":"direct","clicks":1}],
     "dailyClicks":[{"date":"2026-11-08","clicks":3}] }
```
`expirationDate` is a UTC `LocalDateTime` and must be in the future. Only the referrer **host** is stored.

## Agentic platform

| Method & path | Role | Purpose |
|---|---|---|
| `POST /api/v1/workflows` `{"requirement","title?"}` | ENGINEER | create (not started). 400 length 10-2000, 422 shell/credential patterns |
| `GET /api/v1/workflows?page&size` | ENGINEER | list |
| `GET /api/v1/workflows/{id}` | ENGINEER | status, scenario, task counts, **pending approvals**, safe-stop reason |
| `GET /api/v1/workflows/{id}/tasks[?includeOutput=true]` | ENGINEER | DAG nodes with `dependsOn`, status, revision, attempts, failure kind/reason |
| `POST /api/v1/workflows/{id}/execute` `{"acknowledgement?"}` | ENGINEER (APPROVER to resume a safe-stop) | start/resume; **synchronous** until completion, an approval gate, failure or safe-stop. 409 if already running/terminal |
| `POST /api/v1/workflows/{id}/approve` `{"approvalId?","comment?"}` | APPROVER | approve a pending action and resume. `approvalId` required when several are pending |
| `POST /api/v1/workflows/{id}/reject` `{"approvalId?","comment?"}` | APPROVER | reject; gates -> **ROLLED_BACK**; replan proposals are discarded |
| `POST /api/v1/workflows/{id}/stop` `{"reason"}` | ENGINEER | safe-stop (category `OPERATOR_REQUEST`) |
| `POST /api/v1/workflows/{id}/cancel` `{"reason?"}` | ENGINEER | permanent cancel |
| `POST /api/v1/workflows/{id}/replan` | ENGINEER | controlled replanning (below) |
| `GET /api/v1/workflows/{id}/artifacts[?includeContent=true]` | ENGINEER | versioned artifacts with status |
| `GET /api/v1/workflows/{id}/decisions` | ENGINEER | decision lineage (`parentDecisionIds`, `superseded`) |
| `GET /api/v1/workflows/{id}/approvals` | ENGINEER | all approvals |
| `GET /api/v1/workflows/{id}/summary` | ENGINEER | engineering summary + release readiness (409 until available) |

```json
POST /api/v1/workflows/{id}/replan
{ "changedTask":"architecture", "changeType":"ARCHITECTURE_CHANGE", "reason":"switch to event-driven click recording",
  "newRequirement":null, "addDependencies":[{"from":"test-planning","to":"implementation"}] }
-> { "applied":false, "approvalRequired":true, "approvalId":7, "highImpact":true,
     "impactedTasks":["architecture","architecture-approval","implementation", "..."],
     "preservedTasks":["requirements","assumption-approval","planning"],
     "revisedDependencies":[{"from":"...","to":"..."}], "workflowStatus":"WAITING_FOR_APPROVAL" }
```
`changeType`: `REQUIREMENT_CHANGE`, `ARCHITECTURE_CHANGE` (high impact, approval needed), `DESIGN_DETAIL_CHANGE`,
`TEST_STRATEGY_CHANGE`, `DOCUMENTATION_CHANGE` (applied immediately). `newRequirement` is only valid with `REQUIREMENT_CHANGE`.
A cycle in `addDependencies` -> 422. Replanning a RUNNING workflow -> 409.

## Observability

| Endpoint | Content |
|---|---|
| `GET /api/v1/metrics/summary` | totals by status, success/failure rate, retry count, rollback count + frequency, safe-stop count + frequency, average end-to-end latency, average approval wait, task success rate, per-agent execution count and mean duration, MTTR + sample count, and the **definition of every figure** |
| `GET /api/v1/metrics/workflows/{id}` | per-workflow latency, active agent time, executions, retries, approval wait, safe-stops, rollbacks, per-task attempts and duration |
| `GET /api/v1/audit/workflows/{id}` | ordered audit events with redacted details |

All figures are computed from persisted rows. **MTTR** = mean time from a workflow's first `SAFE_STOPPED`/`FAILED` audit event to its
next `WORKFLOW_COMPLETED`; only recovered workflows count, and it includes human reaction time, so in this prototype it
measures operator latency rather than automated recovery speed. Not a production SLO metric.

## Admin (fault injection, mock LLM only)

`POST /api/v1/admin/fault-injection` (ADMIN) `{"operation":"DECOMPOSE_TASKS","mode":"TIMEOUT","times":2,"includeFallback":false}`;
`GET` lists active rules; `DELETE` clears them. Modes: `TIMEOUT`, `PROVIDER_UNAVAILABLE`, `DATABASE_TRANSIENT`,
`MALFORMED_OUTPUT`, `SECRET_LEAK`. Disable entirely with `agentic.llm.mock.fault-injection-enabled=false` (then 404).

## Status code summary

400 validation / malformed - 401 unauthenticated - 403 wrong role - 404 not found - 405 wrong method - 409 invalid state or conflict -
410 expired/deactivated short URL - 415 non-JSON body - 422 policy violation, unsafe input, cyclic graph - 500 unexpected (generic message).