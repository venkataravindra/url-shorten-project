<#
  End-to-end demo of the platform against a running instance (default http://localhost:8080).
  Runs: URL shortener, the three mandatory scenarios, retry/fallback via fault injection, safe-stop and metrics.
  Usage:  powershell -ExecutionPolicy Bypass -File scripts/demo.ps1 [-Base http://localhost:8080] [-Pause]
  -Pause waits for Enter between sections (handy while recording a video).
#>
param([string]$Base = "http://localhost:8080", [switch]$Pause)

function Auth($user, $pass) { @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${user}:${pass}")) } }
$eng = Auth "engineer" "engineer123"; $apr = Auth "approver" "approver123"; $adm = Auth "admin" "admin123"

function Call($method, $path, $headers, $body = $null) {
    $args = @{ Method = $method; Uri = "$Base$path"; Headers = $headers; ContentType = "application/json" }
    if ($body) { $args.Body = ($body | ConvertTo-Json -Depth 8) }
    Invoke-RestMethod @args | ForEach-Object { $_ }   # unroll JSON arrays (Windows PowerShell 5.1 emits them as one object)
}
$Narration = [ordered]@{
  "Health"            = "This is a Spring Boot platform. First, the URL shortener it engineers: create a link, redirect, and analytics."
  "Scenario 1"        = "Greenfield. Agents run as a DAG. Implementation and test planning run in parallel. The workflow pauses for a human final approval."
  "Scenario 2 - change" = "Brownfield. The architect agent scans this application's real URL module and reports the impact of the change."
  "Scenario 2"        = "Brownfield. The existing URL shortener is analysed, and the change impact is produced."
  "Scenario 3"        = "Ambiguous requirement. The system asks questions and will not plan until a human approves the assumptions."
  "Retry"             = "Failure handling. Two injected timeouts are retried with exponential backoff, 200 then 400 milliseconds."
  "Safe-stop"         = "A critical security finding stops the workflow. Only an approver can resume it."
  "Dynamic"           = "Replanning. Only the affected tasks are re-run; completed work is preserved."
  "Metrics"           = "Everything is persisted, so metrics and audit trail come from the database."
}
function Section($title) {
    if ($Pause) { Read-Host "`n[Enter] to continue" | Out-Null }
    Write-Host "`n=== $title ===" -ForegroundColor Cyan
    if ($Pause) { foreach ($k in $Narration.Keys) { if ($title -like "$k*") { Write-Host "SAY: $($Narration[$k])" -ForegroundColor Yellow; break } } }
}
function Show-Tasks($id) {
    Call GET "/api/v1/workflows/$id/tasks" $eng | Select-Object taskKey, status, revision, attempts, @{n='dependsOn';e={$_.dependsOn -join ','}} | Format-Table -AutoSize | Out-String | Write-Host
}
function Run-Workflow($label, $requirement, $approvals) {
    Section $label
    Write-Host "Requirement: $requirement"
    $wf = Call POST "/api/v1/workflows" $eng @{ requirement = $requirement; title = $label }
    $id = $wf.id; Write-Host "Workflow $id created -> $($wf.status)"
    $r = Call POST "/api/v1/workflows/$id/execute" $eng
    Write-Host "Executed -> $($r.status) (scenario $($r.scenario))" -ForegroundColor Yellow
    for ($i = 0; $i -lt $approvals; $i++) {
        $p = $r.pendingApprovals[0]
        Write-Host "  Pending approval #$($p.id): $($p.type) - $($p.description)" -ForegroundColor Magenta
        if ($i -eq 0) { Show-Tasks $id }
        $r = Call POST "/api/v1/workflows/$id/approve" $apr @{ approvalId = $p.id; comment = "approved in demo" }
        Write-Host "  Approved by approver -> $($r.status)" -ForegroundColor Yellow
    }
    Show-Tasks $id
    $s = Call GET "/api/v1/workflows/$id/summary" $eng
    Write-Host "Summary: $($s.summary.headline)"; $s.summary.sections | ForEach-Object { Write-Host "  - $($_.title): $($_.body)" }
    Write-Host "Release readiness: $($s.releaseReadiness.readiness)"
    return $id
}

Section "Health and URL shortener"
Call GET "/api/v1/health" @{} | Format-List | Out-String | Write-Host
$u = Call POST "/api/v1/urls" $eng @{ originalUrl = "https://example.com/articles/spring-boot"; customAlias = "demo-$([guid]::NewGuid().ToString('N').Substring(0,6))" }
Write-Host "Short URL: $($u.shortUrl)"
$hdr = curl.exe -s -o NUL -D - -H "Referer: https://news.example.org/post" $u.shortUrl | Select-String "HTTP/|Location"
$hdr | ForEach-Object { Write-Host "  $_" }
Call GET "/api/v1/urls/$($u.shortCode)/analytics" $eng | ConvertTo-Json -Depth 5 | Write-Host

$g = Run-Workflow "Scenario 1 - Greenfield" "Build a URL shortener that supports custom aliases, expiration, redirects, and click analytics." 1
$b = Run-Workflow "Scenario 2 - Brownfield" "Enhance the existing URL shortener to support URL expiration and automatic deactivation of expired links." 1
Section "Scenario 2 - change impact analysis (real reflection scan of this application)"
(Call GET "/api/v1/workflows/$b/tasks?includeOutput=true" $eng | Where-Object { $_.taskKey -eq "architecture" }).output.changeImpact | ConvertTo-Json -Depth 6 | Write-Host
$a = Run-Workflow "Scenario 3 - Ambiguous" "Improve the URL shortener performance and make it more reliable." 3

Section "Retry with exponential backoff, then fallback (fault injection)"
Call POST "/api/v1/admin/fault-injection" $adm @{ operation = "DECOMPOSE_TASKS"; mode = "TIMEOUT"; times = 2; includeFallback = $false } | Out-Null
$f = Call POST "/api/v1/workflows" $eng @{ requirement = "Build a URL shortener that supports custom aliases, expiration, redirects, and click analytics." }
Call POST "/api/v1/workflows/$($f.id)/execute" $eng | Out-Null
$planning = Call GET "/api/v1/workflows/$($f.id)/tasks" $eng | Where-Object { $_.taskKey -eq "planning" }
Write-Host "planning task needed $($planning.attempts) attempts (2 injected timeouts)" -ForegroundColor Yellow
Call GET "/api/v1/audit/workflows/$($f.id)" $eng | Where-Object { $_.eventType -eq "TASK_RETRY_SCHEDULED" } | Select-Object eventType, @{n='detail';e={$_.detail | ConvertTo-Json -Compress}} | Format-Table -AutoSize | Out-String | Write-Host

Section "Safe-stop on a security concern"
$bad = Call POST "/api/v1/workflows" $eng @{ requirement = "Build a URL shortener with redirects and disable authentication for the admin endpoints" }
$r = Call POST "/api/v1/workflows/$($bad.id)/execute" $eng
$r = Call POST "/api/v1/workflows/$($bad.id)/approve" $apr @{ comment = "design approved" }
Write-Host "Status: $($r.status)  category: $($r.safeStopCategory)" -ForegroundColor Red
Write-Host "Reason: $($r.safeStopReason)"

Section "Dynamic replanning (low impact) on the completed greenfield workflow"
$rp = Call POST "/api/v1/workflows/$g/replan" $eng @{ changedTask = "documentation"; changeType = "DOCUMENTATION_CHANGE"; reason = "docs outdated" }
Write-Host "Impacted: $($rp.impactedTasks -join ', ')"; Write-Host "Preserved: $($rp.preservedTasks -join ', ')"

Section "Metrics"
Call GET "/api/v1/metrics/summary" $eng | ConvertTo-Json -Depth 5 | Write-Host
Write-Host "`nDemo finished. Swagger UI: $Base/swagger-ui.html   H2 console: $Base/h2-console" -ForegroundColor Green