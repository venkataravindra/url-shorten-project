# Complete API Execution Guide

Every flow, in order, with copy-paste commands and the result you should see.
Fastest check of everything: `powershell -ExecutionPolicy Bypass -File scripts/all-flows.ps1` (71 checks, prints PASS/FAIL per flow).

The app must be running: `java -Xmx384m -XX:+UseSerialGC -jar target/agentic-engineering-platform-1.0.0.jar`
and `http://localhost:8080/api/v1/health` must answer `{"status":"UP"}`.

---
## 0. Fix "403 Forbidden" first

**403 means "I know who you are, but your role is not allowed".** (401 means wrong/missing password.) The cause is almost always
that you are logged in as `engineer` and called an endpoint that needs a higher role.

| You want to call | You must be | Password |
|---|---|---|
| `GET /api/v1/health`, `GET /api/v1/urls/{code}` (redirect), Swagger, H2 console | nobody (public) | - |
| create/list/inspect URLs, create/execute/stop/cancel/replan workflows, artifacts, decisions, approvals, summary, metrics, audit | `engineer` (or approver/admin) | `engineer123` |
| **`POST /workflows/{id}/approve`**, **`/reject`**, and **`/execute` on a SAFE_STOPPED workflow** | **`approver`** | `approver123` |
| **`/api/v1/admin/fault-injection`** (POST/GET/DELETE) | **`admin`** | `admin123` |

**Check who you are:** `GET /api/v1/whoami` returns your user and roles (`canApprove`, `canInjectFaults`). A 403 response now also says
which user/roles the server saw and what is needed.

**Swagger UI (most common place to get 403):**
1. Open http://localhost:8080/swagger-ui.html and click **Authorize** (top right).
2. Enter username `approver`, password `approver123`, click **Authorize**, then **Close**.
3. To switch user: click **Authorize** again -> **Logout** -> enter the other user -> **Authorize**. Swagger keeps the old login until you log out.
4. Run `GET /api/v1/whoami` -> **Try it out** -> **Execute** and confirm the user.

**curl:** always pass the user: `curl -u approver:approver123 -X POST http://localhost:8080/api/v1/workflows/<id>/approve`
**Postman:** Authorization tab -> Type *Basic Auth* -> username/password. **PowerShell:** use the helper below.

Other causes: the app was restarted with different passwords (`AGENTIC_*_PASSWORD` environment variables), or you changed `application.yml` users.

---
## 1. PowerShell helper (paste once per terminal)

```powershell
$Base = "http://localhost:8080"
function Auth($u,$p){ @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${u}:${p}")) } }
$eng = Auth engineer engineer123;  $apr = Auth approver approver123;  $adm = Auth admin admin123
function Api($m,$path,$h,$body=$null){
  $a = @{ Method=$m; Uri="$Base$path"; Headers=$h; ContentType="application/json" }
  if($body){ $a.Body = ($body | ConvertTo-Json -Depth 8) }
  Invoke-RestMethod @a | ForEach-Object { $_ } }
$G = "Build a URL shortener that supports custom aliases, expiration, redirects, and click analytics."
$B = "Enhance the existing URL shortener to support URL expiration and automatic deactivation of expired links."
$A = "Improve the URL shortener performance and make it more reliable."
Api GET /api/v1/whoami $apr          # confirm the helper works
```
(PowerShell variable names are case-insensitive, so use unique names: below the workflow ids are `$wf1`, `$wf2`, ...)

---
## 2. URL shortener flows

```powershell
$alias = "guide-" + (Get-Random -Maximum 99999)        # unique alias so this section can be pasted repeatedly
# 2.1 create (generated code)                          -> 201, 7-char shortCode
$u = Api POST /api/v1/urls $eng @{ originalUrl = "https://example.com/articles/spring-boot" }; $u

# 2.2 create with custom alias + expiration            -> 201, customAlias=true
Api POST /api/v1/urls $eng @{ originalUrl="https://example.com/a"; customAlias=$alias; expirationDate="2027-01-01T00:00:00" }

# 2.3 duplicate alias                                  -> 409 ALIAS_ALREADY_EXISTS
try { Api POST /api/v1/urls $eng @{ originalUrl="https://example.com/b"; customAlias=$alias } } catch { "409 expected: " + $_.Exception.Message }
# 2.4 invalid input                                    -> 400 (try javascript:alert(1), http://192.168.1.5/x, a past expirationDate)
try { Api POST /api/v1/urls $eng @{ originalUrl = "javascript:alert(1)" } } catch { "rejected: " + $_.Exception.Message }

# 2.5 redirect (public, no login)                      -> HTTP 302 + Location, click recorded
curl.exe -i -H "Referer: https://news.example.org/post" http://localhost:8080/api/v1/urls/$alias

# 2.6 analytics                                        -> totalClicks, first/last access, referrers, dailyClicks
Api GET /api/v1/urls/$alias/analytics $eng

# 2.7 list (paged) and details
Api GET "/api/v1/urls?page=0&size=10" $eng
Api GET /api/v1/urls/$alias/details $eng

# 2.8 deactivate (soft delete), then redirect          -> 204, then 410 SHORT_URL_INACTIVE
Invoke-WebRequest -Method DELETE http://localhost:8080/api/v1/urls/$alias -Headers $eng -UseBasicParsing | Select-Object StatusCode
curl.exe -i http://localhost:8080/api/v1/urls/$alias

# 2.9 reactivate                                       -> 200 active=true, redirect 302 again
Api PUT /api/v1/urls/$alias/status $eng @{ active = $true }

# 2.10 expiry: create with expiration 5 s ahead, wait, redirect -> 410 SHORT_URL_EXPIRED
$exp = (Get-Date).ToUniversalTime().AddSeconds(5).ToString("yyyy-MM-ddTHH:mm:ss")
Api POST /api/v1/urls $eng @{ originalUrl="https://example.com/soon"; customAlias="soon$alias"; expirationDate=$exp } | Out-Null
Start-Sleep 7; curl.exe -i http://localhost:8080/api/v1/urls/soon$alias

# 2.11 unknown code -> 404;  create without login -> 401
curl.exe -i http://localhost:8080/api/v1/urls/doesnotexist
```

---
## 3. Workflow flows

Every flow: **create -> execute -> (approve)...** `execute` runs synchronously until completion or a pause (a few seconds).

### 3.1 Scenario 1 - Greenfield (engineer + approver)
```powershell
$wf1 = (Api POST /api/v1/workflows $eng @{ requirement = $G; title = "greenfield" }).id
$r = Api POST /api/v1/workflows/$wf1/execute $eng
$r.status; $r.pendingApprovals          # WAITING_FOR_APPROVAL, one RELEASE_EXECUTION approval
Api GET /api/v1/workflows/$wf1/tasks $eng | Format-Table taskKey,status,revision,attempts,@{n="dependsOn";e={$_.dependsOn -join ","}}
Api POST /api/v1/workflows/$wf1/approve $apr @{ comment = "ship it" }     # -> COMPLETED   (engineer here gives 403)
Api GET /api/v1/workflows/$wf1/summary $eng
```
Look for: `assumption-approval`/`architecture-approval` SKIPPED; `implementation` and `test-planning` ran in parallel.

### 3.2 Scenario 2 - Brownfield
```powershell
$wf2 = (Api POST /api/v1/workflows $eng @{ requirement = $B }).id
Api POST /api/v1/workflows/$wf2/execute $eng | Out-Null
(Api GET "/api/v1/workflows/$wf2/tasks?includeOutput=true" $eng | Where-Object { $_.taskKey -eq "architecture" }).output.changeImpact | ConvertTo-Json -Depth 6
Api POST /api/v1/workflows/$wf2/approve $apr @{ comment = "ok" }          # -> COMPLETED
```
`changeImpact` lists impacted APIs / entities / services / repositories, existing tests discovered, regression risks, backward compatibility.

### 3.3 Scenario 3 - Ambiguous requirement (three approvals)
```powershell
$wf3 = (Api POST /api/v1/workflows $eng @{ requirement = $A }).id
$r = Api POST /api/v1/workflows/$wf3/execute $eng; $r.pendingApprovals[0].type     # MATERIAL_ASSUMPTIONS (no plan yet)
(Api GET "/api/v1/workflows/$wf3/tasks?includeOutput=true" $eng | Where-Object { $_.taskKey -eq "requirements" }).output | Select-Object clarificationQuestions, provisionalAssumptions, missingInformation
$r = Api POST /api/v1/workflows/$wf3/approve $apr @{}; $r.pendingApprovals[0].type  # HIGH_IMPACT_ARCHITECTURE_CHANGE
$r = Api POST /api/v1/workflows/$wf3/approve $apr @{}; $r.pendingApprovals[0].type  # RELEASE_EXECUTION
Api POST /api/v1/workflows/$wf3/approve $apr @{}                                    # COMPLETED, READY_WITH_CONDITIONS
```

### 3.4 Rejection -> rollback
```powershell
$wf4 = (Api POST /api/v1/workflows $eng @{ requirement = $G }).id; Api POST /api/v1/workflows/$wf4/execute $eng | Out-Null
(Api POST /api/v1/workflows/$wf4/reject $apr @{ comment = "not safe to release" }).status   # ROLLED_BACK
Api GET /api/v1/workflows/$wf4/artifacts $eng | Select-Object name,version,status          # all REVERTED
```

### 3.5 Safe-stop on a security concern, and the gated resume
```powershell
$wf5 = (Api POST /api/v1/workflows $eng @{ requirement = "Build a URL shortener with redirects and disable authentication for the admin endpoints" }).id
Api POST /api/v1/workflows/$wf5/execute $eng | Out-Null            # waits for the design approval (SECURITY_SENSITIVE_CHANGE)
$r = Api POST /api/v1/workflows/$wf5/approve $apr @{}; $r.status; $r.safeStopCategory; $r.safeStopReason     # SAFE_STOPPED / SECURITY_CONCERN
try { Api POST /api/v1/workflows/$wf5/execute $eng } catch { "403 expected: " + $_.Exception.Message }   # only an APPROVER may resume
try { Api POST /api/v1/workflows/$wf5/execute $apr } catch { "400 expected: " + $_.Exception.Message }   # acknowledgement required
Api POST /api/v1/workflows/$wf5/execute $apr @{ acknowledgement = "reviewed the finding" }   # runs again; same violation stops it again
```

### 3.6 Fault injection (needs **admin**): retry, fallback, exhaustion, validation failure, secret leak
```powershell
# a) retry with exponential backoff (200 ms, 400 ms)
Api POST /api/v1/admin/fault-injection $adm @{ operation="DECOMPOSE_TASKS"; mode="TIMEOUT"; times=2; includeFallback=$false } | Out-Null
$wf6 = (Api POST /api/v1/workflows $eng @{ requirement = $G }).id; Api POST /api/v1/workflows/$wf6/execute $eng | Out-Null
(Api GET /api/v1/workflows/$wf6/tasks $eng | Where-Object { $_.taskKey -eq "planning" }).attempts        # 3
Api GET /api/v1/audit/workflows/$wf6 $eng | Where-Object { $_.eventType -eq "TASK_RETRY_SCHEDULED" }

# b) fallback provider after retries are exhausted (primary fails 10x, fallback is not injected)
Api POST /api/v1/admin/fault-injection $adm @{ operation="PLAN_CODE_GENERATION"; mode="PROVIDER_UNAVAILABLE"; times=10; includeFallback=$false } | Out-Null
$wf7 = (Api POST /api/v1/workflows $eng @{ requirement = $G }).id; Api POST /api/v1/workflows/$wf7/execute $eng | Out-Null
(Api GET "/api/v1/workflows/$wf7/tasks?includeOutput=true" $eng | Where-Object { $_.taskKey -eq "implementation" }).output._execution   # fallbackUsed, degraded
Api DELETE /api/v1/admin/fault-injection $adm                                                                                             # clear leftovers

# c) retries exhausted (fallback also fails) -> SAFE_STOPPED, then human resume
Api POST /api/v1/admin/fault-injection $adm @{ operation="DECOMPOSE_TASKS"; mode="TIMEOUT"; times=20; includeFallback=$true } | Out-Null
$wf8 = (Api POST /api/v1/workflows $eng @{ requirement = $G }).id
$r = Api POST /api/v1/workflows/$wf8/execute $eng; $r.status; $r.safeStopCategory      # SAFE_STOPPED / MAX_RETRIES_EXCEEDED
Api DELETE /api/v1/admin/fault-injection $adm | Out-Null                               # "the provider recovered"
Api POST /api/v1/workflows/$wf8/execute $apr @{ acknowledgement = "provider recovered" }   # WAITING_FOR_APPROVAL
Api POST /api/v1/workflows/$wf8/approve $apr @{}                                       # COMPLETED

# d) validation failure: not retried, workflow FAILED
Api POST /api/v1/admin/fault-injection $adm @{ operation="ANALYZE_REQUIREMENT"; mode="MALFORMED_OUTPUT"; times=1; includeFallback=$false } | Out-Null
$wf9 = (Api POST /api/v1/workflows $eng @{ requirement = $G }).id; (Api POST /api/v1/workflows/$wf9/execute $eng).status    # FAILED

# e) credential in generated output -> blocked, SAFE_STOPPED (SECURITY_CONCERN)
Api POST /api/v1/admin/fault-injection $adm @{ operation="GENERATE_DOCUMENTATION"; mode="SECRET_LEAK"; times=1; includeFallback=$true } | Out-Null
$wf10 = (Api POST /api/v1/workflows $eng @{ requirement = $G }).id; (Api POST /api/v1/workflows/$wf10/execute $eng).safeStopCategory
Api GET /api/v1/admin/fault-injection $adm       # list active rules; DELETE clears them
```
Modes: `TIMEOUT`, `PROVIDER_UNAVAILABLE`, `DATABASE_TRANSIENT`, `MALFORMED_OUTPUT`, `SECRET_LEAK`.

### 3.7 Operator stop / resume / cancel
```powershell
$wf11 = (Api POST /api/v1/workflows $eng @{ requirement = $G }).id
Api POST /api/v1/workflows/$wf11/stop $eng @{ reason = "maintenance window" }                  # SAFE_STOPPED / OPERATOR_REQUEST
Api POST /api/v1/workflows/$wf11/execute $apr @{ acknowledgement = "window over" }             # resumes (engineer would get 403)
Api POST /api/v1/workflows/$wf11/approve $apr @{}                                              # COMPLETED
$wf12 = (Api POST /api/v1/workflows $eng @{ requirement = $G }).id
Api POST /api/v1/workflows/$wf12/cancel $eng @{ reason = "not needed" }                        # CANCELLED (terminal)
```

### 3.8 Dynamic replanning
```powershell
$wf13 = (Api POST /api/v1/workflows $eng @{ requirement = $G }).id; Api POST /api/v1/workflows/$wf13/execute $eng | Out-Null

# low impact: applied immediately, unaffected work preserved
Api POST /api/v1/workflows/$wf13/replan $eng @{ changedTask="documentation"; changeType="DOCUMENTATION_CHANGE"; reason="docs outdated" }
Api POST /api/v1/workflows/$wf13/execute $eng | Out-Null       # re-runs only the stale tasks (documentation revision 2)

# high impact: needs approval; execute is blocked until it is decided
$p = Api POST /api/v1/workflows/$wf13/replan $eng @{ changedTask="architecture"; changeType="ARCHITECTURE_CHANGE"; reason="event-driven clicks" }
$p.approvalRequired; $p.impactedTasks; $p.preservedTasks
Api POST /api/v1/workflows/$wf13/approve $apr @{ approvalId = $p.approvalId }                   # applies the replan and re-executes
Api GET /api/v1/workflows/$wf13/decisions $eng | Select-Object taskKey,taskRevision,title,superseded    # old decisions kept, superseded

# rejected replan: discarded, plan untouched
$p2 = Api POST /api/v1/workflows/$wf13/replan $eng @{ changedTask="architecture"; changeType="ARCHITECTURE_CHANGE"; reason="another idea" }
Api POST /api/v1/workflows/$wf13/reject $apr @{ approvalId = $p2.approvalId }

# requirement change (new text; scenario is re-classified)
Api POST /api/v1/workflows/$wf13/replan $eng @{ changedTask="requirements"; changeType="REQUIREMENT_CHANGE"; reason="scope pivot"; newRequirement=$B }
# invalid: unknown task -> 400; a dependency that creates a cycle -> 422
try { Api POST /api/v1/workflows/$wf13/replan $eng @{ changedTask="documentation"; changeType="DOCUMENTATION_CHANGE"; reason="cycle"; addDependencies=@(@{from="final-approval";to="requirements"}) } } catch { "422 expected: " + $_.Exception.Message }
```
When several approvals are pending, `approve`/`reject` need `approvalId` (list them with `GET /workflows/{id}/approvals`).
Replan types: `REQUIREMENT_CHANGE`, `ARCHITECTURE_CHANGE` (high impact, approval needed); `DESIGN_DETAIL_CHANGE`, `TEST_STRATEGY_CHANGE`, `DOCUMENTATION_CHANGE`.

### 3.9 Restart recovery (state survives a restart)
```powershell
$wf14 = (Api POST /api/v1/workflows $eng @{ requirement = $G }).id; Api POST /api/v1/workflows/$wf14/execute $eng | Out-Null   # waiting for final approval
# stop the app (close its window or Stop-Process on the java process), start it again with the same command, then:
Api GET /api/v1/workflows/$wf14 $eng        # still WAITING_FOR_APPROVAL with its pending approval
Api POST /api/v1/workflows/$wf14/approve $apr @{}   # COMPLETED
```
A workflow that was *mid-run* when the app stopped comes back `SAFE_STOPPED` with category `RESTART_INTERRUPTED`; an approver resumes it with an acknowledgement.

---
## 4. Inspect everything

| Call (engineer) | Shows |
|---|---|
| `GET /api/v1/workflows?page=0&size=10` | all workflows |
| `GET /api/v1/workflows/{id}` | status, scenario, task counts, pending approvals, safe-stop reason |
| `GET /api/v1/workflows/{id}/tasks?includeOutput=true` | DAG nodes, dependencies, revisions, attempts, outputs |
| `GET /api/v1/workflows/{id}/artifacts?includeContent=true` | versioned artifacts and their status |
| `GET /api/v1/workflows/{id}/decisions` / `/approvals` | decision lineage / approval history |
| `GET /api/v1/audit/workflows/{id}` | ordered audit trail |
| `GET /api/v1/metrics/workflows/{id}` and `/api/v1/metrics/summary` | latency, retries, rollbacks, safe-stops, approval wait, MTTR (+ definitions) |

Generated artifact files are also on disk under `workspace/<workflowId>/v<n>/`. Database browser: http://localhost:8080/h2-console
(JDBC URL `jdbc:h2:file:./data/agenticdb`, user `sa`, empty password) - try `SELECT node_key, status, revision, attempts FROM workflow_tasks`.

## 5. Quick troubleshooting

| Symptom | Fix |
|---|---|
| **403** on approve / reject / resume / fault-injection | use `approver` (or `admin` for fault injection); check `GET /api/v1/whoami`; in Swagger do Authorize -> Logout -> log in again |
| 401 | missing or wrong username/password |
| 409 on `execute` | workflow already running, finished, rolled back, cancelled or a replan is waiting for approval |
| 400 on `execute` of a safe-stopped workflow | add `{"acknowledgement":"..."}` |
| 400/415 from PowerShell | send JSON (`ContentType application/json`); use the helper above |
| 422 on create | requirement contains a shell pattern or credential |
| connection refused | app not running / still starting (takes ~15 s) |
| port 8080 busy | `java ... -jar ... --server.port=8081` and change `$Base` |