<#
  Creates SQL backups of the running application's H2 database (tables + data as INSERT statements).
  Works WHILE the app is running: the H2 SCRIPT command is executed inside the app through its H2 console.
  Output (in -OutDir, relative to the folder the app was started from):
    agentic-full-backup.sql   tables, indexes, constraints and data  -> run this on an empty database to restore everything
    agentic-schema.sql        tables, indexes, constraints only
    agentic-data-only.sql     INSERT statements only (run agentic-schema.sql first)
  Usage: powershell -ExecutionPolicy Bypass -File scripts/backup-db.ps1 [-Base http://localhost:8080] [-OutDir backup] [-Timestamp]
  -Timestamp adds _yyyyMMdd-HHmmss to the file names so older backups are kept.
#>
param([string]$Base = "http://localhost:8080", [string]$OutDir = "backup", [switch]$Timestamp)
$ErrorActionPreference = "Stop"
$tables = 'AGENT_EXECUTIONS','APPROVAL_REQUESTS','AUDIT_LOGS','GENERATED_ARTIFACTS','SHORT_URLS','URL_CLICKS','WORKFLOW_DECISIONS','WORKFLOW_DEPENDENCIES','WORKFLOW_EXECUTIONS','WORKFLOW_METRICS','WORKFLOW_TASKS'
$q = ($tables | ForEach-Object { "SELECT '$_' T, COUNT(*) C FROM $_" }) -join ' UNION ALL '
$r = Invoke-WebRequest "$Base/h2-console" -UseBasicParsing -SessionVariable s
$jsid = [regex]::Match($r.Content, 'jsessionid=([0-9a-f]+)').Groups[1].Value
if (-not $jsid) { throw "H2 console not reachable at $Base/h2-console (is the app running and h2 console enabled?)" }
$login = @{ language = 'en'; setting = 'Generic H2 (Embedded)'; name = 'Generic H2 (Embedded)'; driver = 'org.h2.Driver'; url = 'jdbc:h2:file:./data/agenticdb'; user = 'sa'; password = '' }
Invoke-WebRequest "$Base/h2-console/login.do?jsessionid=$jsid" -Method POST -Body $login -WebSession $s -UseBasicParsing | Out-Null
function Run-Sql($sql) { (Invoke-WebRequest "$Base/h2-console/query.do?jsessionid=$jsid" -Method POST -Body @{ sql = $sql } -WebSession $s -UseBasicParsing).Content }
function Counts { $h = @{}; [regex]::Matches((Run-Sql $q), '<td>([A-Z_]+)</td>\s*<td>(\d+)</td>') | ForEach-Object { $h[$_.Groups[1].Value] = [int]$_.Groups[2].Value }; $h }
$suffix = if ($Timestamp) { "_" + (Get-Date -Format "yyyyMMdd-HHmmss") } else { "" }
$full = "$OutDir/agentic-full-backup$suffix.sql"; $schema = "$OutDir/agentic-schema$suffix.sql"; $data = "$OutDir/agentic-data-only$suffix.sql"
for ($try = 1; $try -le 5; $try++) {   # retry if rows were written while the backup was being taken
    $before = Counts
    Run-Sql "SCRIPT NOPASSWORDS NOSETTINGS TO '$full'" | Out-Null
    Run-Sql "SCRIPT NODATA NOPASSWORDS NOSETTINGS TO '$schema'" | Out-Null
    $after = Counts
    if (($tables | ForEach-Object { $before[$_] }) -join ',' -eq (($tables | ForEach-Object { $after[$_] }) -join ',')) { break }
    Write-Host "Data changed during the backup, retrying ($try)..." -ForegroundColor Yellow; Start-Sleep 2
}
if ((Get-Command node -ErrorAction SilentlyContinue)) { node (Join-Path $PSScriptRoot "make-data-only.js") $full $data }
else { Write-Host "node not found: skipped $data (full backup and schema are complete)" -ForegroundColor Yellow }
Write-Host "`nBackup created (row counts at backup time):" -ForegroundColor Green
$tables | ForEach-Object { "  {0,-24} {1,7}" -f $_, $after[$_] }
"  TOTAL rows: " + (($after.Values | Measure-Object -Sum).Sum)
Get-ChildItem $OutDir -Filter "*$suffix.sql" | Select-Object Name, @{n = 'KB'; e = { [math]::Round($_.Length / 1KB) } } | Format-Table -AutoSize | Out-String | Write-Host