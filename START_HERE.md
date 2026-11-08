# START HERE (the only file you need)

## A. Run the project (3 steps)

1. Open **PowerShell** in this folder.
2. Build once:  `mvn -DskipTests package`
3. Start it:    `java -Xmx384m -XX:+UseSerialGC -jar target/agentic-engineering-platform-1.0.0.jar`
   Wait ~15 seconds until you see `Started AgenticApplication`. Leave this window open.

Check: open http://localhost:8080/api/v1/health in a browser. You should see `"status":"UP"`.

**Web console (the frontend):** open **http://localhost:8080/** - sign in as `engineer` / `approver` / `admin` (the password box fills itself),
then use the tabs: Dashboard, URL Shortener, Workflows (DAG diagram, approve/reject buttons, replan), Admin / Faults, and **Run all flows**
(executes every API flow from the browser). The panel at the bottom lists every API call the page makes.

**Finished execution video + screenshots + PDF:** `docs/ui-walkthrough/` (open `play.html` for the video, `Agentic-Platform-UI-Walkthrough.pdf` for the document).

## B. Make the video (2 steps)

1. Open a **second** PowerShell window in this folder.
2. Run:  `powershell -ExecutionPolicy Bypass -File scripts/record-demo.ps1`

   It starts the app if it is not running, opens Swagger in your browser, and walks through the demo one step at a time.
   Start the Windows recorder first: press **Windows key + Alt + R** (records the window in front), press it again to stop.
   The video is saved in `C:\Users\<you>\Videos\Captures`. Read each yellow **SAY:** line aloud, press **Enter** for the next step.
   The whole thing takes about 5 minutes.

(No Windows recorder? Install OBS Studio, add "Display Capture", click Start Recording.)

## C. Log in to the database (H2 console)

Open http://localhost:8080/h2-console and **change the JDBC URL** (the default is wrong):

| Field | Value |
|---|---|
| Saved Settings | Generic H2 (Embedded) |
| Driver Class | `org.h2.Driver` |
| **JDBC URL** | `jdbc:h2:file:./data/agenticdb`  *(not the default `jdbc:h2:~/test`)* |
| User Name | `sa` |
| Password | *(leave empty)* |

Then run: `SELECT node_key, status, revision, attempts FROM workflow_tasks;`
Start the app from this folder, otherwise `./data` points somewhere else. Use the console only while the app is running (the file is locked by the app; other tools like IntelliJ will say "database already in use").

Backup / restore (SQL with tables + data): see `backup/README.md`. Quick restore into a new database: `powershell -ExecutionPolicy Bypass -File scripts/restore-db.ps1`; new backup while the app runs: `scripts/backup-db.ps1`.

## D. Logins for the API / Swagger

| User | Password | Use it for |
|---|---|---|
| engineer | engineer123 | create and run workflows, URLs |
| **approver** | approver123 | **approve / reject** (a 403 means you used engineer) |
| admin | admin123 | fault injection |

In Swagger click **Authorize**; to switch user click **Authorize -> Logout** first. Check the current user: `GET /api/v1/whoami`.

## E. If something goes wrong

| Problem | Fix |
|---|---|
| "403 Forbidden" | wrong user: approve/reject/resume need **approver** |
| "connection refused" | the app is not running (step A3) |
| "port 8080 in use" | close the old window, or add `--server.port=8081` to the java command |
| build error about `target` / rename | OneDrive is locking files: delete the `target` folder and build again |

Postman: import the two files in `postman/` and follow `POSTMAN_GUIDE.md` (121 requests, one-click run). More detail: `API_EXECUTION_GUIDE.md`. Test everything automatically: `powershell -ExecutionPolicy Bypass -File scripts/all-flows.ps1`.