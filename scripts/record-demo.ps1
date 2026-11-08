<#
  ONE command for the video. Starts the app if needed, opens Swagger in your browser, then runs the demo
  step by step. Press Enter for each next step; yellow "SAY:" lines are what to say while recording.
  Usage:  powershell -ExecutionPolicy Bypass -File scripts/record-demo.ps1
#>
$Base = "http://localhost:8080"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
function Up { try { Invoke-RestMethod "$Base/api/v1/health" -TimeoutSec 2 | Out-Null; $true } catch { $false } }
if (-not (Up)) {
    $jar = Join-Path $root "target/agentic-engineering-platform-1.0.0.jar"
    if (-not (Test-Path $jar)) { Write-Host "Building the jar first (about 1 minute)..." -ForegroundColor Cyan; mvn -q -B -DskipTests package }
    Write-Host "Starting the application..." -ForegroundColor Cyan
    Start-Process java -ArgumentList "-Xmx384m", "-XX:+UseSerialGC", "-jar", $jar -WorkingDirectory $root -WindowStyle Minimized
    for ($i = 0; $i -lt 60 -and -not (Up); $i++) { Start-Sleep 2 }
}
if (-not (Up)) { Write-Host "The application did not start. Run: java -jar target/agentic-engineering-platform-1.0.0.jar" -ForegroundColor Red; exit 1 }
Start-Process "$Base/swagger-ui.html"
Clear-Host
Write-Host "RECORDING GUIDE" -ForegroundColor Green
Write-Host " 1. Start your screen recorder now (Windows key + Alt + R records the active window)."
Write-Host " 2. Say the yellow SAY: line for each step, then press Enter for the next step."
Write-Host " 3. When the demo ends, stop the recorder (Windows key + Alt + R). Video is saved in Videos\Captures.`n"
Read-Host "Press Enter to begin"
& (Join-Path $PSScriptRoot "demo.ps1") -Base $Base -Pause
Write-Host "`nDone. Stop the recorder now. (Swagger is open in your browser if you want to show it.)" -ForegroundColor Green