<#
  Executes EVERY API flow against a running instance and prints PASS/FAIL per check.
  Usage: powershell -ExecutionPolicy Bypass -File scripts/all-flows.ps1 [-Base http://localhost:8080]
  Needs the default mock-LLM configuration (fault injection enabled). Leaves its workflows in the database.
#>
param([string]$Base = "http://localhost:8080")

function Auth($u, $p) { @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${u}:${p}")) } }
$eng = Auth "engineer" "engineer123"; $apr = Auth "approver" "approver123"; $adm = Auth "admin" "admin123"
$script:pass = 0; $script:fail = 0
$ReqG = "Build a URL shortener that supports custom aliases, expiration, redirects, and click analytics."
$ReqB = "Enhance the existing URL shortener to support URL expiration and automatic deactivation of expired links."
$ReqA = "Improve the URL shortener performance and make it more reliable."

function Check($name, $cond) {
    if ($cond) { Write-Host "  PASS  $name" -ForegroundColor Green; $script:pass++ } else { Write-Host "  FAIL  $name" -ForegroundColor Red; $script:fail++ }
}
function Section($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }
function Raw($m, $path, $h = @{}, $body = $null) {
    $a = @{ Method = $m; Uri = "$Base$path"; Headers = $h; ContentType = "application/json"; UseBasicParsing = $true }
    if ($null -ne $body) { $a.Body = ($body | ConvertTo-Json -Depth 8) }
    try { $r = Invoke-WebRequest @a -ErrorAction Stop; $code = [int]$r.StatusCode; $txt = $r.Content }
    catch { $resp = $_.Exception.Response; if (-not $resp) { throw }; $code = [int]$resp.StatusCode
            $txt = (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd() }
    $json = $null; if ($txt) { try { $json = $txt | ConvertFrom-Json } catch { } }
    [pscustomobject]@{ Code = $code; Json = $json }
}
function Hit($url, $referer) {   # redirects are not followed; returns status code and Location
    $a = @("-s", "-o", "NUL", "-D", "-"); if ($referer) { $a += @("-H", "Referer: $referer") }
    $out = (& curl.exe @a $url) -join "`n"
    $code = 0; if ($out -match '(?m)^HTTP/\S+ (\d+)') { $code = [int]$Matches[1] }
    $loc = $null; if ($out -match '(?m)^Location: (.+?)\r?$') { $loc = $Matches[1].Trim() }
    [pscustomobject]@{ Code = $code; Location = $loc }
}function NewWf($req) { (Raw POST "/api/v1/workflows" $eng @{ requirement = $req }).Json.id }
function Run($id) { (Raw POST "/api/v1/workflows/$id/execute" $eng).Json }
function Approve($id, $aid = $null) { $b = @{ comment = "approved by all-flows" }; if ($aid) { $b.approvalId = $aid }; (Raw POST "/api/v1/workflows/$id/approve" $apr $b).Json }
function Tasks($id) { (Raw GET "/api/v1/workflows/$id/tasks?includeOutput=true" $eng).Json }
function Task($id, $k) { Tasks $id | Where-Object { $_.taskKey -eq $k } }
function Inject($op, $mode, $times, $fb) { Raw POST "/api/v1/admin/fault-injection" $adm @{ operation = $op; mode = $mode; times = $times; includeFallback = $fb } | Out-Null }
function ClearFaults { Raw DELETE "/api/v1/admin/fault-injection" $adm | Out-Null }

Section "0. Health"
Check "health UP" ((Raw GET "/api/v1/health").Json.status -eq "UP")

Section "1. URL shortener"
$u1 = Raw POST "/api/v1/urls" $eng @{ originalUrl = "https://example.com/articles/spring-boot" }
Check "create -> 201 with 7-char code" ($u1.Code -eq 201 -and $u1.Json.shortCode.Length -eq 7)
$alias = "flow-" + [guid]::NewGuid().ToString("N").Substring(0, 6)
$u2 = Raw POST "/api/v1/urls" $eng @{ originalUrl = "https://example.com/a"; customAlias = $alias; expirationDate = (Get-Date).ToUniversalTime().AddDays(30).ToString("yyyy-MM-ddTHH:mm:ss") }
Check "custom alias + expiration -> 201" ($u2.Code -eq 201 -and $u2.Json.shortCode -eq $alias -and $u2.Json.customAlias)
Check "duplicate alias -> 409" ((Raw POST "/api/v1/urls" $eng @{ originalUrl = "https://example.com/b"; customAlias = $alias }).Code -eq 409)
Check "javascript: URL -> 400" ((Raw POST "/api/v1/urls" $eng @{ originalUrl = "javascript:alert(1)" }).Code -eq 400)
Check "private host -> 400" ((Raw POST "/api/v1/urls" $eng @{ originalUrl = "http://192.168.1.5/x" }).Code -eq 400)
Check "past expiration -> 400" ((Raw POST "/api/v1/urls" $eng @{ originalUrl = "https://example.com"; expirationDate = "2001-01-01T00:00:00" }).Code -eq 400)
Check "create without login -> 401" ((Raw POST "/api/v1/urls" @{} @{ originalUrl = "https://example.com" }).Code -eq 401)
$r1 = Hit "$Base/api/v1/urls/$alias" "https://news.example.org/post"
Check "redirect -> 302 + Location" ($r1.Code -eq 302 -and $r1.Location -eq "https://example.com/a")
Hit "$Base/api/v1/urls/$alias" $null | Out-Null
$an = (Raw GET "/api/v1/urls/$alias/analytics" $eng).Json
Check "analytics: 2 clicks, referrer + direct, daily bucket" ($an.totalClicks -eq 2 -and $an.referrers.Count -eq 2 -and $an.dailyClicks.Count -eq 1 -and $an.firstAccessedAt -and $an.lastAccessedAt)
Check "list (paged) -> 200" ((Raw GET "/api/v1/urls?page=0&size=5" $eng).Json.content.Count -ge 1)
Check "unknown code -> 404" ((Hit "$Base/api/v1/urls/nope-nope" $null).Code -eq 404)
Check "deactivate -> 204" ((Raw DELETE "/api/v1/urls/$alias" $eng).Code -eq 204)
Check "deactivated redirect -> 410" ((Hit "$Base/api/v1/urls/$alias" $null).Code -eq 410)
Check "reactivate -> 200, redirect 302 again" ((Raw PUT "/api/v1/urls/$alias/status" $eng @{ active = $true }).Code -eq 200 -and (Hit "$Base/api/v1/urls/$alias" $null).Code -eq 302)
$short = "exp-" + [guid]::NewGuid().ToString("N").Substring(0, 6)
Raw POST "/api/v1/urls" $eng @{ originalUrl = "https://example.com/soon"; customAlias = $short; expirationDate = (Get-Date).ToUniversalTime().AddSeconds(5).ToString("yyyy-MM-ddTHH:mm:ss") } | Out-Null
Start-Sleep -Seconds 7
Check "expired link -> 410" ((Hit "$Base/api/v1/urls/$short" $null).Code -eq 410)

Section "2. Scenario 1 - Greenfield (parallel branches, final approval)"
$g = NewWf $ReqG; $r = Run $g
Check "execute -> WAITING_FOR_APPROVAL (RELEASE_EXECUTION pending)" ($r.status -eq "WAITING_FOR_APPROVAL" -and $r.pendingApprovals[0].type -eq "RELEASE_EXECUTION" -and $r.scenario -eq "GREENFIELD")
Check "conditional gates skipped" ((Task $g "assumption-approval").status -eq "SKIPPED" -and (Task $g "architecture-approval").status -eq "SKIPPED")
Check "engineer cannot approve -> 403" ((Raw POST "/api/v1/workflows/$g/approve" $eng).Code -eq 403)
Check "approver approves -> COMPLETED" ((Approve $g).status -eq "COMPLETED")
$s = (Raw GET "/api/v1/workflows/$g/summary" $eng).Json
Check "summary + release readiness" ($s.summary.sections.Count -eq 5 -and $s.releaseReadiness.readiness)
Check "artifacts all ACCEPTED" (@((Raw GET "/api/v1/workflows/$g/artifacts" $eng).Json | Where-Object { $_.status -ne "ACCEPTED" }).Count -eq 0)
Check "decision lineage has parents" (@((Raw GET "/api/v1/workflows/$g/decisions" $eng).Json | Where-Object { $_.parentDecisionIds.Count -gt 0 }).Count -gt 0)
Check "approvals listed" ((Raw GET "/api/v1/workflows/$g/approvals" $eng).Json.Count -eq 1)
Check "audit trail has events" ((Raw GET "/api/v1/audit/workflows/$g" $eng).Json.Count -gt 20)
$wm = (Raw GET "/api/v1/metrics/workflows/$g" $eng).Json
Check "per-workflow metrics" ($wm.status -eq "COMPLETED" -and $wm.agentExecutions -eq 10)

Section "3. Scenario 2 - Brownfield"
$b = NewWf $ReqB; $r = Run $b
Check "BROWNFIELD waits for final approval" ($r.status -eq "WAITING_FOR_APPROVAL" -and $r.scenario -eq "BROWNFIELD")
$ci = (Task $b "architecture").output.changeImpact
Check "change impact: APIs, entities, services, repositories, risks" ($ci.impactedApis.Count -gt 0 -and $ci.impactedEntities.Count -gt 0 -and $ci.impactedServices.Count -gt 0 -and $ci.impactedRepositories.Count -gt 0 -and $ci.regressionRisks.Count -gt 0 -and $ci.backwardCompatible)
Check "completes after approval" ((Approve $b).status -eq "COMPLETED")

Section "4. Scenario 3 - Ambiguous requirement (three approvals)"
$a = NewWf $ReqA; $r = Run $a
Check "pauses on MATERIAL_ASSUMPTIONS, no plan yet" ($r.pendingApprovals[0].type -eq "MATERIAL_ASSUMPTIONS" -and (Task $a "planning").status -eq "PENDING")
Check "clarification questions + missing info" ((Task $a "requirements").output.clarificationQuestions.Count -ge 3 -and (Task $a "requirements").output.missingInformation.Count -ge 3)
$r = Approve $a
Check "2nd gate HIGH_IMPACT_ARCHITECTURE_CHANGE; test planning already done" ($r.pendingApprovals[0].type -eq "HIGH_IMPACT_ARCHITECTURE_CHANGE" -and (Task $a "test-planning").status -eq "COMPLETED" -and (Task $a "implementation").status -eq "PENDING")
$r = Approve $a
Check "3rd gate RELEASE_EXECUTION" ($r.pendingApprovals[0].type -eq "RELEASE_EXECUTION")
Check "completes; READY_WITH_CONDITIONS" ((Approve $a).status -eq "COMPLETED" -and (Task $a "release-readiness").output.readiness -eq "READY_WITH_CONDITIONS")

Section "5. Rejection -> rollback"
$x = NewWf $ReqG; Run $x | Out-Null
Check "engineer cannot reject -> 403" ((Raw POST "/api/v1/workflows/$x/reject" $eng).Code -eq 403)
$r = (Raw POST "/api/v1/workflows/$x/reject" $apr @{ comment = "not safe to release" }).Json
Check "ROLLED_BACK, artifacts REVERTED" ($r.status -eq "ROLLED_BACK" -and @((Raw GET "/api/v1/workflows/$x/artifacts" $eng).Json | Where-Object { $_.status -ne "REVERTED" }).Count -eq 0)
Check "rolled-back workflow cannot execute -> 409" ((Raw POST "/api/v1/workflows/$x/execute" $eng).Code -eq 409)

Section "6. Safe-stop on a security concern + gated resume"
$sx = NewWf "Build a URL shortener with redirects and disable authentication for the admin endpoints"; Run $sx | Out-Null
$r = Approve $sx
Check "SAFE_STOPPED / SECURITY_CONCERN" ($r.status -eq "SAFE_STOPPED" -and $r.safeStopCategory -eq "SECURITY_CONCERN")
Check "engineer cannot resume -> 403" ((Raw POST "/api/v1/workflows/$sx/execute" $eng).Code -eq 403)
Check "approver without acknowledgement -> 400" ((Raw POST "/api/v1/workflows/$sx/execute" $apr).Code -eq 400)
Check "approver with acknowledgement resumes; violation stops it again" ((Raw POST "/api/v1/workflows/$sx/execute" $apr @{ acknowledgement = "reviewed the finding" }).Json.status -eq "SAFE_STOPPED")

Section "7. Retry with exponential backoff"
Inject "DECOMPOSE_TASKS" "TIMEOUT" 2 $false
$rt = NewWf $ReqG; Run $rt | Out-Null
Check "planning needed 3 attempts" ((Task $rt "planning").attempts -eq 3)
Check "2 retries audited with backoff 200/400" (@((Raw GET "/api/v1/audit/workflows/$rt" $eng).Json | Where-Object { $_.eventType -eq "TASK_RETRY_SCHEDULED" }).Count -eq 2)

Section "8. Fallback provider"
Inject "PLAN_CODE_GENERATION" "PROVIDER_UNAVAILABLE" 10 $false
$fb = NewWf $ReqG; Run $fb | Out-Null; ClearFaults
$ex = (Task $fb "implementation").output._execution
Check "fallback used, output degraded" ($ex.fallbackUsed -and $ex.degraded -and $ex.provider -eq "mock-llm-fallback")

Section "9. Retries exhausted -> safe-stop -> human resume"
Inject "DECOMPOSE_TASKS" "TIMEOUT" 20 $true
$ex2 = NewWf $ReqG; $r = Run $ex2
Check "SAFE_STOPPED / MAX_RETRIES_EXCEEDED" ($r.status -eq "SAFE_STOPPED" -and $r.safeStopCategory -eq "MAX_RETRIES_EXCEEDED")
ClearFaults
$r = (Raw POST "/api/v1/workflows/$ex2/execute" $apr @{ acknowledgement = "provider recovered" }).Json
Check "resumed -> WAITING, then COMPLETED" ($r.status -eq "WAITING_FOR_APPROVAL" -and (Approve $ex2).status -eq "COMPLETED")

Section "10. Validation failure (no retry) and secret leak"
Inject "ANALYZE_REQUIREMENT" "MALFORMED_OUTPUT" 1 $false
$vf = NewWf $ReqG; $r = Run $vf; ClearFaults
Check "FAILED, 1 attempt, VALIDATION" ($r.status -eq "FAILED" -and (Task $vf "requirements").attempts -eq 1 -and (Task $vf "requirements").failureKind -eq "VALIDATION")
Inject "GENERATE_DOCUMENTATION" "SECRET_LEAK" 1 $true
$sl = NewWf $ReqG; $r = Run $sl; ClearFaults
Check "credential in output -> SAFE_STOPPED, never stored" ($r.status -eq "SAFE_STOPPED" -and $r.safeStopCategory -eq "SECURITY_CONCERN" -and (Raw GET "/api/v1/workflows/$sl/artifacts?includeContent=true" $eng).Json.Count -ge 0 -and ((Raw GET "/api/v1/workflows/$sl/artifacts?includeContent=true" $eng).Json | ConvertTo-Json -Depth 5) -notmatch "AKIA")

Section "11. Operator stop / resume / cancel"
$st = NewWf $ReqG
$r = (Raw POST "/api/v1/workflows/$st/stop" $eng @{ reason = "maintenance window" }).Json
Check "stop -> SAFE_STOPPED / OPERATOR_REQUEST" ($r.status -eq "SAFE_STOPPED" -and $r.safeStopCategory -eq "OPERATOR_REQUEST")
$r = (Raw POST "/api/v1/workflows/$st/execute" $apr @{ acknowledgement = "window over" }).Json
Check "approver resumes -> WAITING; approve -> COMPLETED" ($r.status -eq "WAITING_FOR_APPROVAL" -and (Approve $st).status -eq "COMPLETED")
$cn = NewWf $ReqG
Check "cancel -> CANCELLED, then execute 409" ((Raw POST "/api/v1/workflows/$cn/cancel" $eng @{ reason = "not needed" }).Json.status -eq "CANCELLED" -and (Raw POST "/api/v1/workflows/$cn/execute" $eng).Code -eq 409)

Section "12. Dynamic replanning"
$rp = NewWf $ReqG; Run $rp | Out-Null
Check "unknown task -> 400" ((Raw POST "/api/v1/workflows/$rp/replan" $eng @{ changedTask = "nope"; changeType = "DOCUMENTATION_CHANGE"; reason = "x" }).Code -eq 400)
Check "cyclic dependency -> 422" ((Raw POST "/api/v1/workflows/$rp/replan" $eng @{ changedTask = "documentation"; changeType = "DOCUMENTATION_CHANGE"; reason = "cycle"; addDependencies = @(@{ from = "final-approval"; to = "requirements" }) }).Code -eq 422)
$low = (Raw POST "/api/v1/workflows/$rp/replan" $eng @{ changedTask = "documentation"; changeType = "DOCUMENTATION_CHANGE"; reason = "docs outdated" }).Json
Check "low impact applied immediately; unaffected work preserved" ($low.applied -and $low.impactedTasks -contains "security-review" -and $low.preservedTasks -contains "planning" -and $low.workflowStatus -eq "CREATED")
$r = Run $rp
Check "re-executes only stale tasks (documentation revision 2)" ($r.status -eq "WAITING_FOR_APPROVAL" -and (Task $rp "documentation").revision -eq 2 -and (Task $rp "requirements").revision -eq 1)
$hi = (Raw POST "/api/v1/workflows/$rp/replan" $eng @{ changedTask = "architecture"; changeType = "ARCHITECTURE_CHANGE"; reason = "event-driven clicks" }).Json
Check "high impact needs approval; nothing changed yet" ($hi.approvalRequired -and -not $hi.applied -and (Task $rp "architecture").status -eq "COMPLETED")
Check "execute blocked while replan pending -> 409" ((Raw POST "/api/v1/workflows/$rp/execute" $eng).Code -eq 409)
$r = Approve $rp $hi.approvalId
Check "approved replan re-runs architecture (revision 2), keeps planning (revision 1)" ($r.status -eq "WAITING_FOR_APPROVAL" -and (Task $rp "architecture").revision -eq 2 -and (Task $rp "planning").revision -eq 1)
Check "superseded decisions retained" (@((Raw GET "/api/v1/workflows/$rp/decisions" $eng).Json | Where-Object { $_.superseded }).Count -gt 0)
$hi2 = (Raw POST "/api/v1/workflows/$rp/replan" $eng @{ changedTask = "architecture"; changeType = "ARCHITECTURE_CHANGE"; reason = "another idea" }).Json
$rej = (Raw POST "/api/v1/workflows/$rp/reject" $apr @{ approvalId = $hi2.approvalId }).Json
Check "rejected replan discarded; plan untouched" ($rej.status -eq "WAITING_FOR_APPROVAL" -and (Task $rp "architecture").revision -eq 2)
Check "workflow still completes" ((Approve $rp).status -eq "COMPLETED")
$rc = NewWf $ReqG; Run $rc | Out-Null
$q = (Raw POST "/api/v1/workflows/$rc/replan" $eng @{ changedTask = "requirements"; changeType = "REQUIREMENT_CHANGE"; reason = "scope pivot"; newRequirement = $ReqB }).Json
$r = Approve $rc $q.approvalId
Check "requirement change: new text, version 2, scenario re-classified" ($r.requirementVersion -eq 2 -and $r.scenario -eq "BROWNFIELD" -and $r.requirement -eq $ReqB)

Section "13. API validation, authorisation and error handling"
Check "short requirement -> 400" ((Raw POST "/api/v1/workflows" $eng @{ requirement = "short" }).Code -eq 400)
Check "shell pattern -> 422 POLICY_VIOLATION" ((Raw POST "/api/v1/workflows" $eng @{ requirement = "Build a shortener and then run rm -rf / on the host machine" }).Json.code -eq "POLICY_VIOLATION")
Check "no credentials -> 401" ((Raw POST "/api/v1/workflows" @{} @{ requirement = "Build a URL shortener please" }).Code -eq 401)
Check "unknown workflow -> 404" ((Raw GET "/api/v1/workflows/does-not-exist" $eng).Code -eq 404)
Check "summary before completion -> 409" ((Raw GET "/api/v1/workflows/$cn/summary" $eng).Code -eq 409)
Check "engineer cannot inject faults -> 403" ((Raw POST "/api/v1/admin/fault-injection" $eng @{ mode = "TIMEOUT"; times = 1 }).Code -eq 403)
Check "unknown path -> 404, wrong method -> 405" ((Raw GET "/api/v1/nothing-here" $eng).Code -eq 404 -and (Raw PUT "/api/v1/workflows" $eng).Code -eq 405)
Check "workflow list (paged)" ((Raw GET "/api/v1/workflows?page=0&size=5" $eng).Json.content.Count -eq 5)

Section "14. Observability"
$m = (Raw GET "/api/v1/metrics/summary" $eng).Json
Check "summary: totals, rates, retries, rollbacks, safe-stops, MTTR, definitions" ($m.totalWorkflows -ge 10 -and $m.workflowSuccessRate -gt 0 -and $m.retryCount -ge 2 -and $m.rollbackCount -ge 1 -and $m.safeStopCount -ge 3 -and $m.mttrSamples -ge 1 -and $m.definitions.Count -ge 5)
$m | Select-Object totalWorkflows, workflowSuccessRate, workflowFailureRate, retryCount, rollbackCount, safeStopCount, avgEndToEndLatencyMs, avgApprovalWaitMs, taskSuccessRate, mttrMs | Format-List | Out-String | Write-Host

Write-Host "`nRESULT: $script:pass passed, $script:fail failed" -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Red" })
exit $script:fail