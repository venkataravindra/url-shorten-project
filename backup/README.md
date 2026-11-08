# Database backup (H2) - tables and data as SQL

| File | Contains | Use |
|---|---|---|
| `agentic-full-backup.sql` | all **11 tables, indexes, constraints and every row as INSERT statements** | run on an **empty** database -> tables are created and data inserted in one go |
| `agentic-schema.sql` | tables, indexes, constraints only (no data) | create an empty copy of the schema |
| `agentic-data-only.sql` | INSERT statements only (wrapped in `SET REFERENTIAL_INTEGRITY FALSE/TRUE`) | run **after** `agentic-schema.sql`, or on a database Hibernate already created |

Tables: `SHORT_URLS`, `URL_CLICKS`, `WORKFLOW_EXECUTIONS`, `WORKFLOW_TASKS`, `WORKFLOW_DEPENDENCIES`, `AGENT_EXECUTIONS`,
`APPROVAL_REQUESTS`, `AUDIT_LOGS`, `WORKFLOW_DECISIONS`, `GENERATED_ARTIFACTS`, `WORKFLOW_METRICS`.
Contains demo data from the authoring machine (workflows, artifacts, audit logs, test short URLs). No passwords are stored in it
(`NOPASSWORDS`); the `SA` user is recreated with an empty password.

## Restore with one command (never touches the live database)

```powershell
powershell -ExecutionPolicy Bypass -File scripts/restore-db.ps1
```
It creates `data/restored/agenticdb`, runs the full backup into it and prints the row count of every table. Run the app on it:

```bash
java -Xmx384m -XX:+UseSerialGC -jar target/agentic-engineering-platform-1.0.0.jar --spring.datasource.url=jdbc:h2:file:./data/restored/agenticdb
```

## Restore from the H2 console (browser)

1. Open http://localhost:8080/h2-console.
2. Use a **new, empty** database: JDBC URL `jdbc:h2:file:./data/restored/agenticdb`, user `sa`, empty password, **Connect**
   (H2 creates it on first connect).
3. In the SQL box run (path is relative to the folder the app was started from, or use a full path):

```sql
RUNSCRIPT FROM 'backup/agentic-full-backup.sql';
SELECT COUNT(*) FROM WORKFLOW_EXECUTIONS;
```
4. Running it on a database that already has these tables fails with "table already exists". Use a new database name, or
   start from scratch with `DROP ALL OBJECTS;` **(deletes everything in that database - double check the JDBC URL first)**.

## Take a new backup (while the app is running)

```powershell
powershell -ExecutionPolicy Bypass -File scripts/backup-db.ps1              # overwrites backup/agentic-*.sql
powershell -ExecutionPolicy Bypass -File scripts/backup-db.ps1 -Timestamp   # keeps older backups (name_20261108-153000.sql)
```
It runs H2's `SCRIPT` inside the app, so nothing has to be stopped, retries if rows change mid-backup, and then prints the counts.
The same can be done by hand in the H2 console: `SCRIPT NOPASSWORDS NOSETTINGS TO 'backup/my-backup.sql';`

## How it was verified

The full backup, and schema + data-only, were each restored into brand-new empty databases and **every table's row count matched the
source exactly (12,249 rows over 11 tables)**; a second application instance was then started on a restored database and ran a new
workflow end to end. The `make-data-only.js` splitter is quote-aware (artifact text contains quotes, semicolons and line breaks).

## Notes

* Backups are plain SQL and can contain workflow requirements and generated artifact text: treat them like the database.
* The files are 4-5 MB because the workflow audit and artifact text is large; they compress well (zip about 1 MB).
* PostgreSQL: the H2 dialect output is not guaranteed to run on PostgreSQL. For PostgreSQL use `pg_dump` (see docker-compose.yml).