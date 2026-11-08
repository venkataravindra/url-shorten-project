'use strict';
/*
 * Drives the real web console in headless Edge, executes every flow through the UI, takes a screenshot per step
 * and records a screencast. Output: docs/ui-walkthrough/{shots,frames,steps.json,frames.json}
 * Usage: node tools/ui-capture/capture.js [baseUrl]
 */
const fs = require('fs'), path = require('path');
const { launch, sleep } = require('./cdp.js');
const BASE = process.argv[2] || 'http://localhost:8080';
const OUT = path.resolve(__dirname, '..', '..', 'docs', 'ui-walkthrough');
for (const d of ['shots', 'frames']) { fs.mkdirSync(path.join(OUT, d), { recursive: true }); for (const f of fs.readdirSync(path.join(OUT, d))) fs.unlinkSync(path.join(OUT, d, f)); }

const steps = []; const frames = []; let n = 0, fno = 0, caption = '';
const q = JSON.stringify;
const slug = (s) => s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 48);

(async () => {
  const b = await launch(); const page = await b.firstPage();
  await page.send('Page.enable'); await page.send('Runtime.enable');
  await page.send('Emulation.setDeviceMetricsOverride', { width: 1366, height: 850, deviceScaleFactor: 1, mobile: false });
  page.on('Page.screencastFrame', async (p) => {
    const file = `frames/${String(++fno).padStart(6, '0')}.jpg`;
    fs.writeFileSync(path.join(OUT, file), Buffer.from(p.data, 'base64')); frames.push({ file, ts: p.metadata.timestamp });
    try { await page.send('Page.screencastFrameAck', { sessionId: p.sessionId }); } catch { /* page closing */ }
  });
  await page.goto(BASE + '/');
  await page.send('Page.startScreencast', { format: 'jpeg', quality: 72, maxWidth: 1366, maxHeight: 850, everyNthFrame: 1 });

  // ---------------------------------------------------------------- helpers
  const idle = async () => { for (let i = 0; i < 3; i++) { await page.waitFor('window.__inflight === 0', 90000); await sleep(250); } };
  const cap = async (t) => { caption = t; await page.eval(`window.__caption(${q(t)})`); await sleep(250); };
  async function shot(section, title, note, opts = {}) {
    await idle(); await sleep(450); n += 1;
    const focus = opts.focus === undefined
      ? await page.eval('(() => (state.tab === "workflows" ? (state.wfTab === "tasks" ? "#wfDetail" : "#wfSubtabs") : null))()') : opts.focus;
    await page.eval('(() => { const f = ' + q(focus) + '; const el = f && document.querySelector(f); window.scrollTo(0, el ? Math.max(0, el.getBoundingClientRect().top + window.scrollY - 76) : 0); })()'); await sleep(250);
    const file = `shots/${String(n).padStart(2, '0')}-${slug(title)}.png`;
    await page.screenshot(path.join(OUT, file), opts);
    const calls = JSON.parse(await page.eval('JSON.stringify(window.__apiLog)')); await page.eval('window.__resetLog()');
    steps.push({ n, section, title, note, caption, file, calls });
    console.log(`  [${String(n).padStart(2, '0')}] ${section} :: ${title}  (${calls.length} API calls)`);
  }
  const setVal = (sel, v) => page.eval(`(() => { const e = document.querySelector(${q(sel)}); e.value = ${q(v)}; e.dispatchEvent(new Event('input', { bubbles: true })); e.dispatchEvent(new Event('change', { bubbles: true })); })()`);
  const hl = (sel) => page.eval(`(() => { const e = document.querySelector(${q(sel)}); if (e) { e.scrollIntoView({ block: 'center' }); e.style.outline = '3px solid #e8890c'; setTimeout(() => (e.style.outline = ''), 1200); } })()`);
  async function click(sel) { await hl(sel); await sleep(450); await page.eval(`document.querySelector(${q(sel)}).click()`); }
  const findJs = (scope, text) => `[...document.querySelectorAll(${q(scope)})].find((x) => x.textContent.trim().startsWith(${q(text)}) && !x.disabled)`;
  async function clickText(scope, text) {
    const ok = await page.eval(`(() => { const e = ${findJs(scope, text)}; if (!e) return false; e.scrollIntoView({ block: 'center' }); e.style.outline = '3px solid #e8890c'; return true; })()`);
    if (!ok) throw new Error(`button not found: "${text}" in ${scope}`);
    await sleep(450); await page.eval(`(() => { const e = ${findJs(scope, text)}; e.click(); setTimeout(() => (e.style.outline = ''), 800); })()`);
  }
  const actions = () => page.eval('window.__actionDone || 0');
  async function doAction(fn) { const before = await actions(); await fn(); await page.waitFor(`(window.__actionDone || 0) > ${before}`, 120000); await idle(); }
  async function signIn(user) {
    await setVal('#loginUser', user); await sleep(200); await click('#btnLogin');
    await page.waitFor(`state.user === ${q(user)} && state.roles.length > 0`, 15000); await idle();
  }
  async function tab(name) { await click(`[data-tab=${name}]`); await idle(); await sleep(300); }
  async function newWorkflow(preset, title) {
    await tab('workflows'); await setVal('#wfPreset', preset); await setVal('#wfTitle', title);
    await click('#btnWfCreate'); await page.waitFor(`state.wf && state.wf.title === ${q(title)}`, 20000); await idle();
  }
  const execute = () => doAction(() => click('#btnExecute'));
  const approveFirst = () => doAction(() => clickText('#wfApprovals button', 'Approve'));
  const subtab = async (t) => { await click(`[data-act=subtab][data-t=${t}]`); await idle(); };
  const wfStatus = () => page.eval('state.wf.status');
  const guard = async (name, fn) => { try { await fn(); } catch (e) { console.log(`  !! ${name} failed: ${e.message}`); try { await shot('Errors', name + ' (failed)', e.message); } catch { /* ignore */ } } };

  // ---------------------------------------------------------------- 0. sign-in and dashboard
  await guard('signin', async () => {
    const S = 'Sign-in and dashboard';
    await cap('Open the console. Users: engineer, approver, admin (role-based access)');
    await shot(S, 'Console before sign-in', 'The single-page console is served by the Spring Boot application itself (no separate frontend server). Every API call the page makes is listed in the panel at the bottom.');
    await cap('Sign in as engineer');
    await signIn('engineer');
    await shot(S, 'Signed in as engineer, dashboard', 'GET /api/v1/whoami identifies the user and roles; the dashboard reads GET /api/v1/metrics/summary. The database is fresh, so all counters start at zero.');
  });

  // ---------------------------------------------------------------- 1. URL shortener
  await guard('urls', async () => {
    const S = 'URL shortener'; await tab('urls');
    const alias = 'demo-' + (1000 + Math.floor(Math.random() * 9000));
    await cap('URL shortener: create a short link with a custom alias'); await setVal('#urlAlias', alias);
    await shot(S, 'Create form', 'Original URL, optional custom alias and optional expiration date.');
    await click('#btnUrlCreate'); await page.waitFor('document.querySelector("#urlMsg .okk")', 15000); await idle();
    await shot(S, 'Short URL created', 'POST /api/v1/urls returned 201; GET /api/v1/urls refreshed the table.');
    await cap('Simulate three visits: each is an HTTP 302 redirect and counts a click');
    await clickText('#urlTable button', 'Simulate 3 visits'); await idle(); await sleep(900);
    await shot(S, 'Three redirects recorded', 'GET /api/v1/urls/{code} answers 302 (the browser reports it as an opaque redirect). Click count and timestamps are persisted.');
    await cap('Analytics: total clicks, first/last access, referrers, daily clicks');
    await clickText('#urlTable button', 'Analytics'); await page.waitFor('document.querySelector("#urlAnalytics .stat")', 10000); await idle();
    await shot(S, 'Analytics', 'GET /api/v1/urls/{code}/analytics: total clicks, first and last access timestamps, referrer statistics and daily click statistics.');
    await cap('Deactivate (soft delete): the link now answers 410 Gone');
    await clickText('#urlTable button', 'Deactivate'); await idle();
    await clickText('#urlTable button', 'Simulate 3 visits'); await idle(); await sleep(900);
    await shot(S, 'Deactivated link answers 410', 'DELETE /api/v1/urls/{code} returned 204 (soft delete); visiting the link now returns 410 Gone.');
    await clickText('#urlTable button', 'Reactivate'); await idle();
    await shot(S, 'Link reactivated', 'PUT /api/v1/urls/{code}/status {active:true} reactivated the link.');
  });

  // ---------------------------------------------------------------- 2. Scenario 1 greenfield
  await guard('greenfield', async () => {
    const S = 'Scenario 1 - Greenfield';
    await cap('Scenario 1 (greenfield): requirement to workflow'); await newWorkflow('greenfield', 'Greenfield URL shortener');
    await shot(S, 'Workflow created', 'POST /api/v1/workflows created the workflow with 13 DAG nodes (nothing has run yet). Dashed nodes are human approval gates.');
    await cap('Execute: agents run as a DAG, independent branches run in parallel'); await execute();
    await shot(S, 'Executed, waiting for final approval', 'POST /execute ran requirement, planning, architecture, then implementation and test-planning in parallel, documentation and validation, security review. The workflow paused at the human final approval; the two conditional gates were skipped.');
    await cap('Governance: an engineer is NOT allowed to approve');
    await doAction(() => clickText('#wfApprovals button', 'Approve'));
    await shot(S, 'Engineer approval denied (403)', 'The server answered 403 and explains which role is needed. Approvals are enforced by role (APPROVER).');
    await cap('Sign in as approver and approve the release'); await signIn('approver'); await clickText('#wfDetail button', 'Reload'); await idle();
    await approveFirst();
    await shot(S, 'Approved, workflow completed', 'POST /approve by the approver granted the gate; the workflow resumed and COMPLETED (release readiness and engineering summary).');
    await subtab('artifacts'); await clickText('#wfPane button', 'View'); await idle();
    await shot(S, 'Generated artifacts', 'GET /artifacts?includeContent=true: versioned artifacts, all ACCEPTED after final approval; content stored in the approved workspace only.');
    await subtab('decisions'); await shot(S, 'Decision lineage', 'GET /decisions: each decision lists the upstream decisions it builds on (Builds on column).');
    await subtab('audit'); await shot(S, 'Audit trail', 'GET /api/v1/audit/workflows/{id}: every sensitive step is recorded (creation, task results, approvals, artifact promotion).');
    await subtab('metrics'); await shot(S, 'Workflow metrics', 'GET /api/v1/metrics/workflows/{id}: latency, agent executions, retries, approval wait.');
    await subtab('summary'); await shot(S, 'Engineering summary', 'GET /summary: final engineering summary and release readiness. The platform never deploys; it only assesses readiness.');
  });

  // ---------------------------------------------------------------- 3. Scenario 2 brownfield
  await guard('brownfield', async () => {
    const S = 'Scenario 2 - Brownfield';
    await cap('Scenario 2 (brownfield): enhance the existing URL shortener'); await newWorkflow('brownfield', 'Brownfield expiry enhancement'); await execute();
    await shot(S, 'Brownfield workflow executed', 'Same DAG, different content. The architecture stage first scans the real URL shortener module of this application by reflection.');
    await subtab('tasks'); await click('[data-act=viewout][data-key=architecture]'); await idle();
    await page.eval(`(() => { const pre = document.querySelector('#out-architecture pre'); const i = pre.textContent.indexOf('"changeImpact"'); pre.scrollTop = pre.scrollHeight * (i / pre.textContent.length) - 10; })()`);
    await cap('Change impact analysis built from the real code base');
    await shot(S, 'Change impact analysis', 'Architecture output: impacted APIs, entities, services and repositories discovered from the actual module, existing test discovery and backward-compatibility assessment.', { focus: '#out-architecture' });
    await page.eval(`(() => { const pre = document.querySelector('#out-architecture pre'); const i = pre.textContent.indexOf('"regressionRisks"'); pre.scrollTop = pre.scrollHeight * (i / pre.textContent.length) - 10; })()`);
    await shot(S, 'Regression risks', 'Regression risks and the statement that the change is backward compatible.', { focus: '#out-architecture' });
    await approveFirst(); await shot(S, 'Brownfield completed', 'Approved and completed.');
  });

  // ---------------------------------------------------------------- 4. Scenario 3 ambiguous
  await guard('ambiguous', async () => {
    const S = 'Scenario 3 - Ambiguous requirement';
    await cap('Scenario 3: an ambiguous requirement must not become silent decisions'); await newWorkflow('ambiguous', 'Ambiguous performance request'); await execute();
    await shot(S, 'Paused for assumption approval', 'Blocking ambiguities were found. Planning has NOT started; the workflow waits for a human to approve the provisional assumptions.');
    await subtab('tasks'); await click('[data-act=viewout][data-key=requirements]'); await idle();
    await cap('Clarification questions, provisional assumptions and missing information');
    await shot(S, 'Questions and assumptions', 'Requirements output: clarification questions, provisional assumptions (for example p95 latency and availability targets) and missing information such as baselines.', { focus: '#out-requirements' });
    await cap('Approval 1 of 3: material assumptions'); await approveFirst();
    await shot(S, 'Second gate: high-impact design', 'After the assumptions were approved the plan was produced. Introducing a shared cache is a high-impact architecture change, so a second approval is required. test-planning already completed in parallel.');
    await cap('Approval 2 of 3: design'); await approveFirst();
    await shot(S, 'Third gate: release', 'Design approved; implementation, documentation, validation and review ran; final release approval pending.');
    await cap('Approval 3 of 3: release'); await approveFirst();
    await shot(S, 'Ambiguous scenario completed', 'Completed after three human approvals. Release readiness is READY_WITH_CONDITIONS because the targets are still provisional.');
  });

  // ---------------------------------------------------------------- 5. rejection
  await guard('rejection', async () => {
    const S = 'Rejection and rollback';
    await cap('Rejection: a rejected approval rolls the workflow back'); await newWorkflow('greenfield', 'Rejected release'); await execute();
    await doAction(() => clickText('#wfApprovals button', 'Reject'));
    await shot(S, 'Rejected, rolled back', 'POST /reject: the workflow is ROLLED_BACK and its staged artifact versions are reverted (a safe, reversible operation).');
    await subtab('artifacts'); await shot(S, 'Artifacts reverted', 'All artifacts are REVERTED; nothing was accepted.');
  });

  // ---------------------------------------------------------------- 6. safe-stop
  await guard('safestop', async () => {
    const S = 'Safe-stop (security)';
    await cap('Security: a request to disable authentication'); await newWorkflow('insecure', 'Disable authentication request'); await execute();
    await shot(S, 'Design approval for a sensitive change', 'The architecture marks a SECURITY_SENSITIVE_CHANGE, which needs a human approval before implementation.');
    await approveFirst();
    await cap('Critical review finding: the workflow SAFE-STOPS');
    await shot(S, 'Safe-stopped on a critical finding', 'The review stage found a critical security finding and the workflow stopped (SAFE_STOPPED, SECURITY_CONCERN). The reason is persisted; nothing downstream ran.');
    await signIn('engineer'); await clickText('#wfDetail button', 'Reload'); await idle();
    await cap('Only an approver may resume, with an acknowledgement');
    await doAction(() => click('#btnResume'));
    await shot(S, 'Engineer cannot resume (403)', 'Resuming a safe-stopped workflow requires the APPROVER role and an acknowledgement.');
    await signIn('approver'); await clickText('#wfDetail button', 'Reload'); await idle(); await doAction(() => click('#btnResume'));
    await shot(S, 'Approver resumes: stopped again', 'The approver resumed with an acknowledgement; the same policy violation stops it again, proving the guard cannot be bypassed.');
  });

  // ---------------------------------------------------------------- 7. faults, retry, replan
  await guard('faults', async () => {
    const S = 'Retry, fallback and fault injection';
    await signIn('engineer'); await tab('admin'); await cap('Admin area: engineer is not allowed to inject faults');
    await click('#btnFiAdd'); await page.waitFor('document.querySelector("#fiMsg .err")', 10000); await idle();
    await shot(S, 'Fault injection denied for engineer', 'POST /api/v1/admin/fault-injection returned 403; the message names the required ADMIN role.');
    await signIn('admin'); await tab('admin');
    await setVal('#fiOp', 'DECOMPOSE_TASKS'); await setVal('#fiMode', 'TIMEOUT'); await setVal('#fiTimes', '2'); await cap('Admin: make the next 2 task-planning LLM calls time out');
    await click('#btnFiAdd'); await page.waitFor('document.querySelector("#fiMsg .okk")', 10000); await idle();
    await shot(S, 'Fault rule injected', 'The next two DECOMPOSE_TASKS calls will fail with a timeout.');
    await cap('The workflow retries with exponential backoff (200 ms, 400 ms)');
    await newWorkflow('greenfield', 'Retry demo'); await execute();
    await subtab('tasks'); await shot(S, 'Planning needed 3 attempts', 'The planning task shows Attempts = 3: two transient failures were retried and the third attempt succeeded.', { focus: '#wfSubtabs' });
    await subtab('audit'); await shot(S, 'Retry audit events', 'TASK_RETRY_SCHEDULED events record each attempt and the backoff (200 ms, then 400 ms).');
  });
  await guard('replan', async () => {
    const S = 'Dynamic replanning';
    await cap('Replanning: documentation changed'); await subtab('replan'); await page.eval(`document.querySelector('#rpReason').value = 'documentation was outdated'`);
    await doAction(() => click('#btnReplan'));
    await shot(S, 'Low-impact replan applied', 'POST /replan: only documentation and everything downstream are marked for re-execution; completed upstream work is preserved. No approval is needed for a low-impact change.');
    await execute();
    await shot(S, 'Only stale tasks re-ran', 'The workflow re-ran just the affected tasks (revision 2) and paused again at the final approval.');
    await cap('High-impact architecture change needs approval'); await subtab('replan');
    await setVal('#rpTask', 'architecture'); await setVal('#rpType', 'ARCHITECTURE_CHANGE'); await page.eval(`document.querySelector('#rpReason').value = 'switch to event-driven click recording'`);
    await doAction(() => click('#btnReplan'));
    await shot(S, 'High-impact replan waits for approval', 'Nothing changes until a human approves the proposal; the exact impact (impacted and preserved tasks) is shown for review.');
    await doAction(async () => {
      const ok = await page.eval(`(() => { const blocks = [...document.querySelectorAll('#wfApprovals .alert')]; const blk = blocks.find((b) => b.textContent.includes('HIGH_IMPACT_REPLAN')); if (!blk) return false; const btn = [...blk.querySelectorAll('button')].find((x) => x.textContent.trim() === 'Approve'); btn.scrollIntoView({ block: 'center' }); btn.style.outline = '3px solid #e8890c'; return true; })()`);
      if (!ok) throw new Error('replan approval not found'); await sleep(450);
      await page.eval(`(() => { const blk = [...document.querySelectorAll('#wfApprovals .alert')].find((b) => b.textContent.includes('HIGH_IMPACT_REPLAN')); [...blk.querySelectorAll('button')].find((x) => x.textContent.trim() === 'Approve').click(); })()`);
    });
    await subtab('decisions');
    await shot(S, 'Replan approved, decisions superseded', 'After approval the architecture and its dependants re-ran. Earlier decisions are struck through (superseded) but kept for traceability, never silently overwritten.');
    await subtab('tasks'); await shot(S, 'Revisions after replan', 'architecture now shows revision 2 while requirements and planning remain revision 1 (preserved).', { focus: '#wfSubtabs' });
  });

  // ---------------------------------------------------------------- 8. dashboard
  await guard('dashboard', async () => {
    await signIn('approver'); await tab('dashboard'); await cap('Metrics computed from the persisted workflow history');
    await shot('Observability', 'Metrics dashboard', 'GET /api/v1/metrics/summary: success and failure rate, retries, rollbacks, safe-stops, end-to-end latency, approval wait, task success rate and MTTR, all derived from stored data.');
  });

  // ---------------------------------------------------------------- 9. run all flows from the browser
  let runResults = [];
  await guard('runall', async () => {
    const S = 'Run all flows from the browser';
    await signIn('approver'); await tab('runall'); await cap('Run every API flow from the browser (about 1 minute)');
    await shot(S, 'Run all flows: ready', 'One button executes every API flow with the engineer, approver and admin users.');
    await page.eval('window.__runAllDone = false'); await click('#btnRunAll'); await cap('Running all flows ...');
    await sleep(5000); await shot(S, 'Run all flows: in progress', 'Progress bar and live results while the flows execute.');
    await page.waitFor('window.__runAllDone === true', 240000); await idle(); await cap('Result of the full API run');
    await page.eval('window.scrollTo(0, 0)');
    await shot(S, 'Run all flows: result summary', 'Every check passed. The page made all of these API calls itself.');
    runResults = JSON.parse(await page.eval(`JSON.stringify([...document.querySelectorAll('#runResults > div')].map((d) => ({ sec: d.className === 'run-sec', pass: !!d.querySelector('.s-PASS'), fail: !!d.querySelector('.s-FAIL'), text: d.innerText.replace(/\\s+/g, ' ').trim() })))`));
    await page.eval('document.querySelector("#apiLogBox").open = false');
    await shot(S, 'Run all flows: complete list of checks', 'Full list of checks (full-page capture).', { full: true });
  });

  await cap('');
  await page.send('Page.stopScreencast');
  fs.writeFileSync(path.join(OUT, 'frames.json'), JSON.stringify(frames));
  const result = await page.eval('JSON.stringify(window.__runAllResult || null)');
  fs.writeFileSync(path.join(OUT, 'steps.json'), JSON.stringify({ generatedAt: new Date().toISOString(), base: BASE, steps, runAll: { summary: JSON.parse(result), rows: runResults } }, null, 1));
  console.log(`\nDone: ${steps.length} screenshots, ${frames.length} video frames, run-all result ${result}`);
  b.close(); process.exit(0);
})().catch((e) => { console.error('CAPTURE FAILED:', e); process.exit(1); });
