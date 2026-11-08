# Trade-offs and Limitations (honest list)

## The LLM is simulated
Agents do real validation and computation, but the "understanding" is keyword rules. Different wording of the same
requirement can classify differently (e.g. a vague request that also names a concrete feature is treated as not ambiguous).
The OpenAI adapter was never run against the real API.

## Things asked for, and how far they go
* **"Execute tests" (brownfield)**: the platform validates plans statically and never runs generated code (a security rule). Real tests of this repo run through Maven.
* **Rollback** reverts artifact versions and withdraws approvals only; there is no deployment to undo.
* **Parallelism** is real (bounded thread pool) but with the default mock it is in-process and small (`parallelism=2`); speed-up is only visible with a slow provider (`delay-ms`).
* **MTTR** is derived from audit events and includes human think-time; it is a prototype indicator, not an SLO.
* **Restart recovery** safe-stops interrupted workflows (human resumes) rather than auto-resuming; auto-resume is an opt-in flag and only lightly exercised.

## Engineering shortcuts
* `ddl-auto=update` instead of Flyway/Liquibase migrations.
* In-memory users with dev default passwords; no rate limiting; the H2 console is on by default.
* `execute` is synchronous and holds an HTTP thread for the run (seconds with the mock; long with a real LLM).
* The "is this workflow running" guard is an in-JVM set: **single instance only**. Multiple instances need a DB lease/lock or a queue.
* No four-eyes rule and no per-workflow ownership checks.
* Click recording is synchronous with the redirect; analytics `GROUP BY` queries will not scale to very large click tables without rollups/partitioning. No cache in front of redirects.
* Referrer is the browser `Referer` host only and is trivially spoofable; clicks are counted, not de-duplicated or bot-filtered.
* Expired links are detected lazily on redirect; there is intentionally no scheduler (that is the brownfield exercise).
* Artifacts are files plus DB rows with no retention policy.

## Verified vs not verified
| Verified by execution | Not verified |
|---|---|
| unit + integration suite, full boot of the packaged jar with a 384 MB heap, the PowerShell demo against it | Docker image and compose stack (no Docker on the build machine), PostgreSQL profile, OpenAI adapter, `scripts/demo.sh`, Linux/macOS runs, Java 21 runtime (built and tested on JDK 23 with `--release 21`) |

## Production-readiness gaps
Identity provider + fine-grained authorisation; secrets manager; Flyway migrations; async execution with a durable queue and
leases; idempotency keys on API calls; metrics export (Micrometer/Prometheus), tracing and structured logs; rate limiting and
bot protection; cache for redirects and async click ingestion; retention/archival; load, chaos and security testing (SAST/DAST,
dependency scanning); CI/CD with the human approval step wired to real change management; a real LLM with prompt versioning,
evaluation sets and output schemas.