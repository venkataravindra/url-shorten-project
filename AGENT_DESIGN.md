# Agent Design

## Contract

```java
public interface Agent { String id(); AgentResult execute(AgentContext ctx); }
```
`AgentContext` exposes the immutable upstream outputs (`WorkflowContext`), the node parameters (e.g. `mode=PLAN`) and the
`LLMProvider` chosen for this attempt. `AgentResult` = output map + artifact drafts + decision drafts. Agents never touch
the database for their own output; the engine persists it. Agents are idempotent and return plain JSON-compatible maps.

## Agents

| Agent (`id`) | Stage | What it does (beyond calling the LLM) |
|---|---|---|
| `RequirementAgent` (`requirement`) | 1 | normalises text, calls `ANALYZE_REQUIREMENT` + `IDENTIFY_AMBIGUITIES`, validates fields, derives `blockingAmbiguity`, clarification questions, provisional assumptions, writes `requirements-analysis.md` |
| `PlanningAgent` (`planning`) | 2 | validates task ids/estimates, builds the **task graph**, rejects cycles, computes topological order, parallel groups and the **critical path** (longest path by estimate) |
| `ArchitectureAgent` (`architecture`) | 3 | for brownfield runs `CodebaseAnalyzer` first; validates approval actions against `PolicyEngine`; sets `requiresApproval`; writes `architecture.md` and (brownfield) `change-impact-analysis.md` |
| `CodingAgent` (`coding`) | 4 | **plan only**: checks each sensitive action has a valid approval (`ChangeControlService`), validates every planned path stays inside the project tree. Never writes into a project, compiles or runs code |
| `TestingAgent` (`testing`) mode `PLAN` | 5 | generates test cases, builds the criteria -> test traceability matrix and coverage % |
| `TestingAgent` mode `VALIDATE` | 5 | static validation: every acceptance criterion covered, plan has files, scenario rules (brownfield needs REGRESSION tests; ambiguous needs LOAD + RELIABILITY/CHAOS and approved assumptions). Failure is a *critical* validation error |
| `DocumentationAgent` mode `DOCS` / `SUMMARY` | 6 / 8 | change documentation and runbook; final engineering summary from facts aggregated from all stages |
| `ReviewAgent` (`review`) | 7 | code-review findings + risks from the LLM **plus deterministic secret scan** of every active artifact; any CRITICAL finding or credential -> `SecurityConcernException` |
| `ReleaseAgent` (`release`) | 8 | checks evidence (all stages, validation, verdict, final approval, rollback plan); computes `READY` / `READY_WITH_CONDITIONS` (MEDIUM/HIGH risks or provisional targets become conditions); **never deploys** |

"Execute tests" in the brownfield brief: the platform performs *plan-level validation*; it deliberately does not run
generated code. This repository's own tests are executed by Maven (`mvn verify`).

## LLM abstraction

```java
public interface LLMProvider { String name(); boolean isSimulated(); LLMResponse complete(LLMRequest request); }
```
`LLMRequest(operation, requirement, context, correlationId, attempt)` -> `LLMResponse(payload map, provider, simulated, degraded, latency, notice)`.
Selection is configuration (`agentic.llm.provider=mock|openai`) in `LlmConfig`; agents and the engine are unaware.

### MockLLMProvider (simulation)

* Ten operations: requirement analysis, ambiguity identification, task decomposition, architecture, code-generation plan, test cases, code review, risks, documentation, summary.
* Output depends on the requirement via `RequirementSignals` (keyword rules): greenfield / brownfield / ambiguous, plus feature flags (alias, expiration, analytics, ...), and an "insecure request" flag that makes the review return a CRITICAL finding.
* Content lives in `MockResponses`, separate from agents and orchestrator. The brownfield impact analysis is computed from the **real** baseline passed in the request, not canned.
* Configurable delay and failure probability; random failures are a deterministic hash of `(seed, operation, requirement, attempt)` so a retry can succeed and runs are reproducible.
* `FaultInjector` (admin endpoint + tests) forces `TIMEOUT`, `PROVIDER_UNAVAILABLE`, `DATABASE_TRANSIENT`, `MALFORMED_OUTPUT` or `SECRET_LEAK` for N calls, optionally also on the fallback provider.
* The fallback provider is a second mock instance flagged `degraded`; its use is audited and visible on the task output (`_execution`).

### OpenAILLMProvider (optional, disabled)

Plain `java.net.http` client for chat completions with JSON output. Enabled only with `agentic.llm.provider=openai`,
`agentic.llm.openai.enabled=true` and `OPENAI_API_KEY`. **It was compiled but never run against the real API** (no key/network
in the build environment); its response mapping is unverified. Agent-side validation exists precisely so a mismatching real
model response fails loudly.

## Swapping the provider

Implement `LLMProvider`, expose it as the `@Primary` bean (or extend `LlmConfig`). No agent, engine or test of the engine changes.

## Why a mock is sufficient for this prototype

The assignment evaluates orchestration, governance and engineering judgment, not model intelligence. A deterministic
provider makes the three scenarios, failure paths and metrics **reproducible and testable offline**; real-model variance
would make approvals, replans and retries impossible to assert. It is a simulation and is labelled as such in every response.