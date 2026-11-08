> **Web console:** http://localhost:8080/ (after starting the app). **Execution video, screenshots and PDF:** [docs/ui-walkthrough/](docs/ui-walkthrough/README.md).

> **New here?** Read [START_HERE.md](START_HERE.md) first: run, record a video, DB login and 403 fixes in one page.

# Agentic Software Engineering System - URL Shortener

A runnable prototype that turns a software requirement into reviewable engineering outcomes using a
**DAG-orchestrated multi-agent workflow**, with **human approval gates**, **retry / fallback / rollback / safe-stop**
and **persisted audit + reliability metrics**. The engineered application is a **URL shortener** that ships in the same
codebase and is also the *brownfield baseline* the platform analyses.

> **The LLM is a simulation.** Out of the box every agent talks to `MockLLMProvider`, which returns deterministic,
> rule-based responses selected by keywords in the requirement. It performs no reasoning and needs no API key or internet.
> An optional OpenAI adapter exists but is disabled and was not exercised by the tests (see [AGENT_DESIGN.md](AGENT_DESIGN.md)).

## Technology stack

Java 21 (target; built on JDK 21+), Spring Boot 3.4.1, Spring Web, Spring Data JPA / Hibernate 6, Spring Validation,
Spring Security (HTTP Basic, roles), springdoc OpenAPI, H2 (default, file based) and PostgreSQL (profile `postgres`),
Maven, JUnit 5, Mockito, Spring Boot Test, SLF4J/Logback, Lombok (entities only), Docker / Docker Compose (optional).
No other frameworks: the workflow engine is plain Java.

## Prerequisites (low-memory friendly)

| Need | Notes |
|---|---|
| JDK 21 or newer | `java -version` |
| Maven 3.9+ | `mvn -v` |
| ~1 GB free RAM | the app runs in a 384 MB heap (measured ~330 MB resident); Maven and tests are capped at 512 MB |
| Docker | **optional**, only for the PostgreSQL compose setup |

## Run locally (no Docker, no API key)

```bash
mvn clean verify                 # build + run all tests (about 2 minutes)
mvn -DskipTests package          # builds target/agentic-engineering-platform-1.0.0.jar
java -Xmx384m -XX:+UseSerialGC -jar target/agentic-engineering-platform-1.0.0.jar
```

Or without packaging: `mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-Xmx384m"`.

| What | URL |
|---|---|
| Health | http://localhost:8080/api/v1/health |
| Swagger UI | http://localhost:8080/swagger-ui.html (click **Authorize**, use a user below) |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| H2 console | http://localhost:8080/h2-console - JDBC URL `jdbc:h2:file:./data/agenticdb`, user `sa`, empty password |

Development users (override with `AGENTIC_ENGINEER_PASSWORD`, `AGENTIC_APPROVER_PASSWORD`, `AGENTIC_ADMIN_PASSWORD`):

| User | Password | Roles | Can |
|---|---|---|---|
| `engineer` | `engineer123` | ENGINEER | create/execute/stop/replan workflows, manage URLs |
| `approver` | `approver123` | ENGINEER, APPROVER | additionally approve/reject and resume safe-stopped workflows |
| `admin` | `admin123` | all | additionally use the fault-injection endpoints |

Workflow state lives in `./data/agenticdb` (H2 file) so it survives restarts; generated artifacts are written to `./workspace`.
Delete both folders to start clean.

## One-command demo

With the app running, in a second terminal (Windows PowerShell):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/demo.ps1          # add -Pause to step through while recording
```

It exercises the URL shortener, the three mandatory scenarios (greenfield, brownfield, ambiguous), retries with
exponential back-off, a security safe-stop, a replan and the metrics. `scripts/demo.sh` does a shorter curl-only run
(untested on this machine, see [INTERVIEW_DEMO_GUIDE.md](INTERVIEW_DEMO_GUIDE.md)).
A real captured run of the PowerShell demo is in [docs/demo-run-output.txt](docs/demo-run-output.txt); a timed script for recording a video is in section B of the interview guide.

## API examples (curl)

```bash
# URL shortener
curl -u engineer:engineer123 -H "Content-Type: application/json" -X POST localhost:8080/api/v1/urls \
  -d '{"originalUrl":"https://example.com/articles/spring-boot","customAlias":"spring-guide","expirationDate":"2027-01-01T00:00:00"}'
curl -i localhost:8080/api/v1/urls/spring-guide                        # 302 + Location, click recorded
curl -u engineer:engineer123 localhost:8080/api/v1/urls/spring-guide/analytics

# Agentic workflow (greenfield scenario)
curl -u engineer:engineer123 -H "Content-Type: application/json" -X POST localhost:8080/api/v1/workflows \
  -d '{"requirement":"Build a URL shortener that supports custom aliases, expiration, redirects, and click analytics."}'
curl -u engineer:engineer123 -X POST localhost:8080/api/v1/workflows/{id}/execute      # runs until an approval gate
curl -u engineer:engineer123 localhost:8080/api/v1/workflows/{id}/tasks                # DAG state + dependencies
curl -u approver:approver123 -X POST localhost:8080/api/v1/workflows/{id}/approve      # human final approval
curl -u engineer:engineer123 localhost:8080/api/v1/workflows/{id}/summary              # engineering summary
curl -u engineer:engineer123 localhost:8080/api/v1/metrics/summary
```

Full endpoint list: [API_DOCUMENTATION.md](API_DOCUMENTATION.md). A ready-made request file is `requests.http`.

## The three mandatory scenarios

| Scenario | Input | Visible behaviour |
|---|---|---|
| Greenfield | "Build a URL shortener that supports custom aliases, expiration, redirects, and click analytics." | requirements -> plan -> design -> implementation plan and test plan **in parallel** -> docs + validation -> review -> **final approval** -> release readiness -> summary |
| Brownfield | "Enhance the existing URL shortener to support URL expiration and automatic deactivation of expired links." | architecture stage **reflection-scans this application's own URL module** and produces a change-impact analysis (impacted APIs, entities, services, repositories, test discovery, regression risks, backward compatibility) |
| Ambiguous | "Improve the URL shortener performance and make it more reliable." | blocking ambiguities, clarification questions, provisional assumptions -> workflow **pauses for approval of the assumptions** before any plan exists; the cache design needs a second approval; final approval third |

## Sample workflow session

```text
POST /workflows            -> CREATED
POST /workflows/{id}/execute  -> WAITING_FOR_APPROVAL   (assumption-approval pending, planning still PENDING)
POST /workflows/{id}/approve  -> WAITING_FOR_APPROVAL   (architecture-approval pending; test-planning already COMPLETED)
POST /workflows/{id}/approve  -> WAITING_FOR_APPROVAL   (final-approval pending)
POST /workflows/{id}/approve  -> COMPLETED
```

## Mock LLM explanation

`LLMProvider` is a port with `complete(LLMRequest) -> LLMResponse`. `MockLLMProvider` implements it with predefined,
scenario-specific payloads for ten operations (requirement analysis, ambiguity identification, task decomposition,
architecture, code-generation plan, test cases, code review, risks, documentation, summary). Properties under
`agentic.llm.mock`: `delay-ms`, `failure-probability` (deterministic per operation/attempt, e.g. `0.2`), `seed`,
`fault-injection-enabled`. Every response carries a `simulationNotice`. Agents validate provider output, so a malformed
response fails validation rather than flowing downstream.

## PostgreSQL / Docker (optional, not verified on the authoring machine)

```bash
docker compose up --build        # app + PostgreSQL, app on http://localhost:8080
```
Docker was not installed where this project was built, so the Dockerfile and compose file were reviewed but **not executed**.
The `postgres` profile uses the same entities; the schema comes from Hibernate `ddl-auto=update` (Flyway is the production recommendation).

## Documentation map

[ARCHITECTURE](ARCHITECTURE.md) - [WORKFLOW_DESIGN](WORKFLOW_DESIGN.md) - [AGENT_DESIGN](AGENT_DESIGN.md) -
[GOVERNANCE_AND_SECURITY](GOVERNANCE_AND_SECURITY.md) - [API_DOCUMENTATION](API_DOCUMENTATION.md) -
[TESTING_STRATEGY](TESTING_STRATEGY.md) - [DESIGN_DECISIONS](DESIGN_DECISIONS.md) -
[TRADEOFFS_AND_LIMITATIONS](TRADEOFFS_AND_LIMITATIONS.md) - [INTERVIEW_DEMO_GUIDE](INTERVIEW_DEMO_GUIDE.md)