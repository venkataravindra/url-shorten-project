<#
  Restores a SQL backup (tables + data) into a NEW H2 database file. It never touches the live database.
  Usage: powershell -ExecutionPolicy Bypass -File scripts/restore-db.ps1 [-Script backup/agentic-full-backup.sql] [-Target data/restored/agenticdb]
  Then run the app on it:
    java -Xmx384m -XX:+UseSerialGC -jar target/agentic-engineering-platform-1.0.0.jar --spring.datasource.url=jdbc:h2:file:./data/restored/agenticdb
  Needs the H2 jar from the local Maven repository (it is there after one `mvn package`).
#>
param([string]$Script = "backup/agentic-full-backup.sql", [string]$Target = "data/restored/agenticdb")
$ErrorActionPreference = "Stop"
if (-not (Test-Path $Script)) { throw "Backup file not found: $Script" }
if (Test-Path "$Target.mv.db") { throw "Target database already exists: $Target.mv.db. Choose another -Target (the restore needs an empty database)." }
$jar = Get-ChildItem "$env:USERPROFILE\.m2\repository\com\h2database\h2" -Recurse -Filter "h2-*.jar" -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch "sources|javadoc" } | Sort-Object Name -Descending | Select-Object -First 1
if (-not $jar) { throw "H2 jar not found in ~/.m2. Run 'mvn -DskipTests package' once, then retry." }
New-Item -ItemType Directory -Force (Split-Path $Target) | Out-Null
$url = "jdbc:h2:file:" + $Target.Replace("\", "/")
java -cp $jar.FullName org.h2.tools.RunScript -url $url -user sa -script $Script
if ($LASTEXITCODE -ne 0) { throw "Restore failed (exit $LASTEXITCODE)" }
$tables = 'AGENT_EXECUTIONS','APPROVAL_REQUESTS','AUDIT_LOGS','GENERATED_ARTIFACTS','SHORT_URLS','URL_CLICKS','WORKFLOW_DECISIONS','WORKFLOW_DEPENDENCIES','WORKFLOW_EXECUTIONS','WORKFLOW_METRICS','WORKFLOW_TASKS'
$q = ($tables | ForEach-Object { "SELECT '$_' T, COUNT(*) C FROM $_" }) -join ' UNION ALL '
Write-Host "`nRestored into $Target - row counts:" -ForegroundColor Green
java -cp $jar.FullName org.h2.tools.Shell -url $url -user sa -password '""' -sql $q
Write-Host "`nRun the app on it:`n  java -Xmx384m -XX:+UseSerialGC -jar target/agentic-engineering-platform-1.0.0.jar --spring.datasource.url=jdbc:h2:file:./$($Target.Replace('\','/'))" -ForegroundColor Cyan