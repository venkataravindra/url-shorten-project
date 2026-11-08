# UI walkthrough: video, screenshots and document

Everything here was produced by driving the real web console (http://localhost:8080/) in a headless browser against a fresh database.

| File | What it is |
|---|---|
| `flow-recording.webm` | **Execution video**, about 3 minutes, with on-screen captions. Open `play.html` (double-click) or drag the file into Edge, Chrome or VLC. |
| `play.html` | A tiny player page for the video. |
| `Agentic-Platform-UI-Walkthrough.pdf` | **Document / PDF**: cover with results, one page per step (screenshot + the API calls the page made: user, method, path, HTTP status, ms), the full list of checks, and API coverage against the OpenAPI spec (29 of 29 endpoints). |
| `report.html` | The same document as a web page. |
| `shots/` | The 46 screenshots (PNG), numbered in execution order. |
| `steps.json` | Machine-readable steps: captions, screenshots, API calls. |

## What the video and PDF cover

Sign-in as engineer, approver and admin; URL shortener (create, redirect, analytics, deactivate, reactivate); scenario 1 greenfield
(parallel DAG, 403 for the engineer, approval, artifacts, decisions, audit, metrics, summary); scenario 2 brownfield (change-impact analysis
from a scan of the real code); scenario 3 ambiguous (three approvals); rejection and rollback; safe-stop on a security finding and gated resume;
fault injection with retry and backoff; dynamic replanning; the metrics dashboard; and the **Run all flows** page that executes every API flow
from the browser (72 checks, 0 failures).

All agent output in these recordings is produced by the deterministic mock LLM (a simulation).

## Record it again

Needs the app running on a fresh database and Edge or Chrome installed. No npm packages.

```powershell
# 1. fresh database so the run is clean (stop any running copy of the app first)
java -Xmx384m -XX:+UseSerialGC -jar target/agentic-engineering-platform-1.0.0.jar --spring.datasource.url=jdbc:h2:file:./data/walkthrough --agentic.workspace.root=./data/walkthrough-workspace
# 2. in another window, from the project folder
node tools/ui-capture/capture.js      # drives the console: screenshots + screencast frames (about 3 minutes)
node tools/ui-capture/encode-video.js # encodes flow-recording.webm (real time, about 3 minutes)
node tools/ui-capture/make-report.js  # report.html and the PDF
```
The intermediate `frames/` folder (about 70 MB) is deleted after encoding; `capture.js` recreates it.
