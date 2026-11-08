# Postman Guide - collection and execution sequence

Files (folder `postman/`):

| File | Purpose |
|---|---|
| `Agentic-Platform.postman_collection.json` | **121 requests in 12 folders**, every request has tests; IDs are captured automatically |
| `Local.postman_environment.json` | variables: `baseUrl`, the three users and passwords, the four requirement texts |
| `verify-collection.js` | runs the whole collection from the command line with no install: `node postman/verify-collection.js` |
| `generate-collection.js` | regenerates the two JSON files (`node postman/generate-collection.js`) |

## 1. Import (2 minutes)

1. Start the app (see START_HERE.md) - `http://localhost:8080/api/v1/health` must say UP.
2. Postman -> **Import** -> drag in **both** JSON files.
3. Top-right environment dropdown -> select **Agentic Local**.
4. Authentication is already set on every request (Basic auth using `{{engUser}}`, `{{aprUser}}`, `{{admUser}}`), so you never type a password.

## 2. Run everything in one click

Collection **...** menu -> **Run collection** -> keep the order -> **Run**. About 1.5 minutes. Every request shows green tests.
Re-running is safe (aliases are random; counters use `>=`).

Command line alternatives:

```bash
node postman/verify-collection.js            # no install; prints PASS/FAIL per assertion (202 assertions pass on the authoring machine)
npx newman run postman/Agentic-Platform.postman_collection.json -e postman/Local.postman_environment.json
```
The second command is the official Postman runner; it downloads the `newman` package on first use.

## 3. Execution sequence (run top to bottom; run a folder at a time to demo it)

| # | Folder | What it proves | Users involved |
|---|---|---|---|
| 00 | Setup and identity | app up; what each user may do (the who-am-i calls) | all |
| 01 | URL shortener | create, custom alias + expiry, duplicate 409, validation 400, **302 redirect**, analytics (referrer/daily), list, deactivate (410), reactivate, **expiry (410)**, 404, 401 | engineer, public |
| 02 | Scenario 1 - Greenfield | create -> execute -> **pauses at final approval** -> DAG state -> engineer approve **403** -> approver approve -> COMPLETED -> summary, artifacts, lineage, audit, metrics | engineer, approver |
| 03 | Scenario 2 - Brownfield | change-impact analysis from a scan of the real URL module | engineer, approver |
| 04 | Scenario 3 - Ambiguous | questions/assumptions, **no plan until approved**, three approvals (assumptions, design, release) | engineer, approver |
| 05 | Rejection and rollback | reject -> ROLLED_BACK, artifacts REVERTED | engineer (403), approver |
| 06 | Safe-stop on security concern | CRITICAL finding -> SAFE_STOPPED; resume needs approver **and** acknowledgement | engineer, approver |
| 07 | Failure handling | **retry with backoff**, **fallback provider**, **retries exhausted -> safe-stop -> human resume**, validation failure (no retry), **secret leak blocked** | admin (inject), engineer, approver |
| 08 | Stop, resume, cancel | operator controls | engineer, approver |
| 09 | Dynamic replanning | unknown task 400, cycle 422, low-impact replan, **high-impact replan needs approval**, preserved work, superseded decisions, rejected proposal, requirement change | engineer, approver |
| 10 | Validation and security checks | 400/401/404/405/409/422 handling | engineer |
| 11 | Observability | metrics summary (retries, rollbacks, safe-stops, MTTR), audit trail, per-workflow metrics | engineer |

## 4. Run one folder by hand (what to click)

Inside a folder run requests **top to bottom**. The scripts store ids in collection variables (`wfGreen`, `approvalId`, ...), so later requests
(`{{wfGreen}}` in the URL) work without copying anything. Example, folder 02: 2.1 create -> 2.2 execute (status `WAITING_FOR_APPROVAL`) ->
2.3 tasks -> 2.4 approve as engineer (**403 is the expected result**) -> 2.5 approve as approver (`COMPLETED`) -> 2.6 to 2.11 inspect.

Requests whose title says "-> 403 / 400 / 409 / 422 / 401 / 404 / 410" are **expected to return that code**; the test passes when they do.

## 5. Variables

Collection variables (also in the environment): `baseUrl`, `engUser/engPass`, `aprUser/aprPass`, `admUser/admPass`,
`reqGreenfield`, `reqBrownfield`, `reqAmbiguous`, `reqInsecure`. Captured at run time: `shortCode`, `alias`, `wfGreen`, `wfBrown`, `wfAmb`,
`wfReject`, `wfSec`, `wfRetry`, `wfFallback`, `wfExhaust`, `wfInvalid`, `wfLeak`, `wfStop`, `wfCancel`, `wfReplan`, `wfReq`, `approvalId`, `replanApprovalId`.
Other port or host: change `baseUrl` in the environment.

## 6. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Everything fails with "Could not get any response" | app is not running -> start it |
| **403** where a 200 is expected | that request used the wrong user. Approve/reject/resume need `approver`; fault injection needs `admin`. Run the 0.2 to 0.4 who-am-i requests |
| 401 | wrong password in the environment, or `AGENTIC_*_PASSWORD` environment variables changed the defaults |
| Folder 07 requests fail with 404 | fault injection disabled (`agentic.llm.mock.fault-injection-enabled=false`) |
| A later request says `{{wfX}}` unresolved | an earlier request in that folder failed; run the folder from its first request |
| 1.8 redirect shows 302 but Postman "follows" it | keep the per-request setting; the collection disables auto-follow for redirect requests |
| 11.1 metrics test fails on a brand-new database | run the earlier folders first (it needs prior retries, rollbacks and safe-stops) |
