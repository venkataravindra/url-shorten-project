'use strict';
/* "Run all flows": executes every API flow from the browser and reports PASS/FAIL per check. */
(function () {
  const E = 'engineer', P = 'approver', D = 'admin';
  const A = (u, m, p, b) => api(m, p, b, u);
  const G = PRESETS.greenfield, B = PRESETS.brownfield, AM = PRESETS.ambiguous, INS = PRESETS.insecure;
  const EXPECTED_CHECKS = 73;
  let pass = 0, fail = 0, done = 0, current = null;

  const row = (cls, html) => { const d = document.createElement('div'); d.className = cls; d.innerHTML = html; $('#runResults').appendChild(d); return d; };
  const section = (title) => { current = title; row('run-sec', esc(title)); };
  function check(name, cond, detail = '') {
    cond ? pass++ : fail++; done++;
    row('run-row', `${badge(cond ? 'PASS' : 'FAIL')}<span>${esc(name)}</span><span class="d">${esc(detail)}</span>`);
    $('#runBar').style.width = Math.min(100, Math.round((100 * done) / EXPECTED_CHECKS)) + '%';
    $('#runSummary').innerHTML = `<b>${pass}</b> passed, <b style="color:var(--bad)">${fail}</b> failed, ${done} checks so far`;
  }
  const newWf = async (req) => (await A(E, 'POST', '/api/v1/workflows', { requirement: req })).data.id;
  const run = async (id) => (await A(E, 'POST', `/api/v1/workflows/${id}/execute`)).data;
  const approve = async (id, aid) => (await A(P, 'POST', `/api/v1/workflows/${id}/approve`, aid ? { approvalId: aid, comment: 'approved by run-all' } : { comment: 'approved by run-all' })).data;
  const tasks = async (id) => (await A(E, 'GET', `/api/v1/workflows/${id}/tasks?includeOutput=true`)).data;
  const task = async (id, k) => (await tasks(id)).find((t) => t.taskKey === k);
  const inject = (op, mode, times, fb) => A(D, 'POST', '/api/v1/admin/fault-injection', { operation: op, mode, times, includeFallback: fb });
  const clearFaults = () => A(D, 'DELETE', '/api/v1/admin/fault-injection');
  const redirectCode = async (code) => {
    const t0 = performance.now(); window.__inflight++;
    try { const res = await fetch('/api/v1/urls/' + code, { redirect: 'manual' }); const c = res.type === 'opaqueredirect' ? 302 : res.status; logCall('public', 'GET', '/api/v1/urls/' + code, c, Math.round(performance.now() - t0)); return c; }
    catch (e) { return 0; } finally { window.__inflight--; }
  };
  const statusOf = async (id) => (await A(E, 'GET', `/api/v1/workflows/${id}`)).data;

  async function runAllFlows() {
    pass = fail = done = 0; $('#runResults').innerHTML = ''; $('#runBar').style.width = '0'; $('#btnRunAll').disabled = true; window.__runAllDone = false;
    await clearFaults();
    try {
      section('0. Health and identity');
      check('health is UP', (await A(E, 'GET', '/api/v1/health')).data.status === 'UP');
      const who = await A(P, 'GET', '/api/v1/whoami'); check('approver can approve, cannot inject faults', who.data.canApprove === true && who.data.canInjectFaults === false);

      section('1. URL shortener');
      const alias = 'ui-' + Math.floor(Math.random() * 1e6);
      const u1 = await A(E, 'POST', '/api/v1/urls', { originalUrl: 'https://example.com/articles/spring-boot' });
      check('create -> 201 with 7-char code', u1.status === 201 && u1.data.shortCode.length === 7, u1.data && u1.data.shortCode);
      const u2 = await A(E, 'POST', '/api/v1/urls', { originalUrl: 'https://example.com/a', customAlias: alias, expirationDate: new Date(Date.now() + 30 * 864e5).toISOString().slice(0, 19) });
      check('custom alias + expiration -> 201', u2.status === 201 && u2.data.shortCode === alias && u2.data.customAlias);
      check('duplicate alias -> 409', (await A(E, 'POST', '/api/v1/urls', { originalUrl: 'https://example.com/b', customAlias: alias })).status === 409);
      check('javascript: URL -> 400', (await A(E, 'POST', '/api/v1/urls', { originalUrl: 'javascript:alert(1)' })).status === 400);
      check('private host -> 400', (await A(E, 'POST', '/api/v1/urls', { originalUrl: 'http://192.168.1.5/x' })).status === 400);
      check('past expiration -> 400', (await A(E, 'POST', '/api/v1/urls', { originalUrl: 'https://example.com', expirationDate: '2001-01-01T00:00:00' })).status === 400);
      const anon = await fetch('/api/v1/urls', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest' }, body: JSON.stringify({ originalUrl: 'https://example.com' }) });
      logCall('anonymous', 'POST', '/api/v1/urls', anon.status, 0);
      check('create without login -> 401 (no browser login popup)', anon.status === 401 && anon.headers.get('WWW-Authenticate') === null);
      check('redirect -> 302 (twice)', (await redirectCode(alias)) === 302 && (await redirectCode(alias)) === 302);
      const an = (await A(E, 'GET', `/api/v1/urls/${alias}/analytics`)).data;
      check('analytics: 2 clicks, referrer, daily bucket, timestamps', an.totalClicks === 2 && an.referrers.length >= 1 && an.dailyClicks.length === 1 && !!an.firstAccessedAt && !!an.lastAccessedAt, `${an.totalClicks} clicks, referrer ${an.referrers[0] && an.referrers[0].referrer}`);
      check('list (paged)', (await A(E, 'GET', '/api/v1/urls?page=0&size=5')).data.content.length >= 1);
      const det = (await A(E, 'GET', `/api/v1/urls/${alias}/details`)).data;
      check('details: click count and status', det.shortCode === alias && det.clickCount === 2 && det.active === true, `${det.clickCount} clicks`);
      check('unknown code -> 404', (await redirectCode('nope-nope-x')) === 404);
      check('deactivate -> 204', (await A(E, 'DELETE', '/api/v1/urls/' + alias)).status === 204);
      check('deactivated redirect -> 410', (await redirectCode(alias)) === 410);
      check('reactivate -> 200, redirect 302 again', (await A(E, 'PUT', `/api/v1/urls/${alias}/status`, { active: true })).status === 200 && (await redirectCode(alias)) === 302);
      const soon = 'soon-' + Math.floor(Math.random() * 1e6);
      await A(E, 'POST', '/api/v1/urls', { originalUrl: 'https://example.com/soon', customAlias: soon, expirationDate: new Date(Date.now() + 5000).toISOString().slice(0, 19) });
      row('run-row', '<span class="muted">waiting 7 s for the 5-second link to expire...</span>'); await sleep(7000);
      check('expired link -> 410', (await redirectCode(soon)) === 410);

      section('2. Scenario 1 - Greenfield (parallel branches, final approval)');
      const g = await newWf(G); let r = await run(g);
      check('execute -> WAITING_FOR_APPROVAL (RELEASE_EXECUTION pending)', r.status === 'WAITING_FOR_APPROVAL' && r.pendingApprovals[0].type === 'RELEASE_EXECUTION' && r.scenario === 'GREENFIELD');
      check('conditional gates skipped', (await task(g, 'assumption-approval')).status === 'SKIPPED' && (await task(g, 'architecture-approval')).status === 'SKIPPED');
      check('engineer cannot approve -> 403', (await A(E, 'POST', `/api/v1/workflows/${g}/approve`)).status === 403);
      check('approver approves -> COMPLETED', (await approve(g)).status === 'COMPLETED');
      const s = (await A(E, 'GET', `/api/v1/workflows/${g}/summary`)).data;
      check('summary + release readiness', s.summary.sections.length === 5 && !!s.releaseReadiness.readiness, s.releaseReadiness.readiness);
      check('artifacts all ACCEPTED', (await A(E, 'GET', `/api/v1/workflows/${g}/artifacts`)).data.every((a) => a.status === 'ACCEPTED'));
      check('decision lineage has parents', (await A(E, 'GET', `/api/v1/workflows/${g}/decisions`)).data.some((d) => d.parentDecisionIds.length > 0));
      check('approvals listed', (await A(E, 'GET', `/api/v1/workflows/${g}/approvals`)).data.length === 1);
      check('audit trail has events', (await A(E, 'GET', `/api/v1/audit/workflows/${g}`)).data.length > 20);
      const wm = (await A(E, 'GET', `/api/v1/metrics/workflows/${g}`)).data; check('per-workflow metrics', wm.status === 'COMPLETED' && wm.agentExecutions === 10, `${wm.agentExecutions} agent executions`);

      section('3. Scenario 2 - Brownfield');
      const b = await newWf(B); r = await run(b);
      check('BROWNFIELD waits for final approval', r.status === 'WAITING_FOR_APPROVAL' && r.scenario === 'BROWNFIELD');
      const ci = (await task(b, 'architecture')).output.changeImpact;
      check('change impact: APIs, entities, services, repositories, risks', ci.impactedApis.length > 0 && ci.impactedEntities.length > 0 && ci.impactedServices.length > 0 && ci.impactedRepositories.length > 0 && ci.regressionRisks.length > 0 && ci.backwardCompatible === true);
      check('completes after approval', (await approve(b)).status === 'COMPLETED');

      section('4. Scenario 3 - Ambiguous requirement (three approvals)');
      const am = await newWf(AM); r = await run(am);
      check('pauses on MATERIAL_ASSUMPTIONS, no plan yet', r.pendingApprovals[0].type === 'MATERIAL_ASSUMPTIONS' && (await task(am, 'planning')).status === 'PENDING');
      const rq = (await task(am, 'requirements')).output;
      check('clarification questions + missing information', rq.clarificationQuestions.length >= 3 && rq.missingInformation.length >= 3, `${rq.clarificationQuestions.length} questions`);
      r = await approve(am);
      check('2nd gate = design; test planning already done', r.pendingApprovals[0].type === 'HIGH_IMPACT_ARCHITECTURE_CHANGE' && (await task(am, 'test-planning')).status === 'COMPLETED' && (await task(am, 'implementation')).status === 'PENDING');
      r = await approve(am); check('3rd gate = RELEASE_EXECUTION', r.pendingApprovals[0].type === 'RELEASE_EXECUTION');
      check('completes; READY_WITH_CONDITIONS', (await approve(am)).status === 'COMPLETED' && (await task(am, 'release-readiness')).output.readiness === 'READY_WITH_CONDITIONS');

      section('5. Rejection -> rollback');
      const x = await newWf(G); await run(x);
      check('engineer cannot reject -> 403', (await A(E, 'POST', `/api/v1/workflows/${x}/reject`)).status === 403);
      r = (await A(P, 'POST', `/api/v1/workflows/${x}/reject`, { comment: 'not safe to release' })).data;
      check('ROLLED_BACK, artifacts REVERTED', r.status === 'ROLLED_BACK' && (await A(E, 'GET', `/api/v1/workflows/${x}/artifacts`)).data.every((a) => a.status === 'REVERTED'));
      check('rolled-back workflow cannot execute -> 409', (await A(E, 'POST', `/api/v1/workflows/${x}/execute`)).status === 409);

      section('6. Safe-stop on a security concern + gated resume');
      const sx = await newWf(INS); await run(sx); r = await approve(sx);
      check('SAFE_STOPPED / SECURITY_CONCERN', r.status === 'SAFE_STOPPED' && r.safeStopCategory === 'SECURITY_CONCERN', r.safeStopReason);
      check('engineer cannot resume -> 403', (await A(E, 'POST', `/api/v1/workflows/${sx}/execute`)).status === 403);
      check('approver without acknowledgement -> 400', (await A(P, 'POST', `/api/v1/workflows/${sx}/execute`)).status === 400);
      check('approver with acknowledgement resumes; violation stops it again', (await A(P, 'POST', `/api/v1/workflows/${sx}/execute`, { acknowledgement: 'reviewed the finding' })).data.status === 'SAFE_STOPPED');

      section('7. Retry with exponential backoff');
      await inject('DECOMPOSE_TASKS', 'TIMEOUT', 2, false);
      const rt = await newWf(G); await run(rt);
      check('planning needed 3 attempts', (await task(rt, 'planning')).attempts === 3);
      const bo = (await A(E, 'GET', `/api/v1/audit/workflows/${rt}`)).data.filter((e) => e.eventType === 'TASK_RETRY_SCHEDULED');
      check('2 retries audited with backoff 200 then 400 ms', bo.length === 2 && bo[0].detail.backoffMs === 200 && bo[1].detail.backoffMs === 400);

      section('8. Fallback provider');
      await inject('PLAN_CODE_GENERATION', 'PROVIDER_UNAVAILABLE', 10, false);
      const fb = await newWf(G); await run(fb); await clearFaults();
      const ex = (await task(fb, 'implementation')).output._execution;
      check('fallback used, output degraded', ex.fallbackUsed && ex.degraded && ex.provider === 'mock-llm-fallback', ex.provider);

      section('9. Retries exhausted -> safe-stop -> human resume');
      await inject('DECOMPOSE_TASKS', 'TIMEOUT', 20, true);
      const ex2 = await newWf(G); r = await run(ex2);
      check('SAFE_STOPPED / MAX_RETRIES_EXCEEDED', r.status === 'SAFE_STOPPED' && r.safeStopCategory === 'MAX_RETRIES_EXCEEDED');
      await clearFaults();
      r = (await A(P, 'POST', `/api/v1/workflows/${ex2}/execute`, { acknowledgement: 'provider recovered' })).data;
      check('resumed -> WAITING, then COMPLETED', r.status === 'WAITING_FOR_APPROVAL' && (await approve(ex2)).status === 'COMPLETED');

      section('10. Validation failure (no retry) and secret leak');
      await inject('ANALYZE_REQUIREMENT', 'MALFORMED_OUTPUT', 1, false);
      const vf = await newWf(G); r = await run(vf); await clearFaults(); const vt = await task(vf, 'requirements');
      check('FAILED, 1 attempt, VALIDATION', r.status === 'FAILED' && vt.attempts === 1 && vt.failureKind === 'VALIDATION');
      await inject('GENERATE_DOCUMENTATION', 'SECRET_LEAK', 1, true);
      const sl = await newWf(G); r = await run(sl); await clearFaults();
      const arts = await A(E, 'GET', `/api/v1/workflows/${sl}/artifacts?includeContent=true`);
      check('credential in output -> SAFE_STOPPED, never stored', r.status === 'SAFE_STOPPED' && r.safeStopCategory === 'SECURITY_CONCERN' && !arts.text.includes('AKIA'));

      section('11. Operator stop / resume / cancel');
      const st = await newWf(G); r = (await A(E, 'POST', `/api/v1/workflows/${st}/stop`, { reason: 'maintenance window' })).data;
      check('stop -> SAFE_STOPPED / OPERATOR_REQUEST', r.status === 'SAFE_STOPPED' && r.safeStopCategory === 'OPERATOR_REQUEST');
      r = (await A(P, 'POST', `/api/v1/workflows/${st}/execute`, { acknowledgement: 'window over' })).data;
      check('approver resumes -> WAITING; approve -> COMPLETED', r.status === 'WAITING_FOR_APPROVAL' && (await approve(st)).status === 'COMPLETED');
      const cn = await newWf(G);
      check('cancel -> CANCELLED, then execute 409', (await A(E, 'POST', `/api/v1/workflows/${cn}/cancel`, { reason: 'not needed' })).data.status === 'CANCELLED' && (await A(E, 'POST', `/api/v1/workflows/${cn}/execute`)).status === 409);

      section('12. Dynamic replanning');
      const rp = await newWf(G); await run(rp); const rpath = `/api/v1/workflows/${rp}/replan`;
      check('unknown task -> 400', (await A(E, 'POST', rpath, { changedTask: 'nope', changeType: 'DOCUMENTATION_CHANGE', reason: 'x' })).status === 400);
      check('cyclic dependency -> 422', (await A(E, 'POST', rpath, { changedTask: 'documentation', changeType: 'DOCUMENTATION_CHANGE', reason: 'cycle', addDependencies: [{ from: 'final-approval', to: 'requirements' }] })).status === 422);
      const low = (await A(E, 'POST', rpath, { changedTask: 'documentation', changeType: 'DOCUMENTATION_CHANGE', reason: 'docs outdated' })).data;
      check('low impact applied immediately; unaffected work preserved', low.applied && low.impactedTasks.includes('security-review') && low.preservedTasks.includes('planning') && low.workflowStatus === 'CREATED');
      r = await run(rp);
      check('re-executes only stale tasks (documentation revision 2)', r.status === 'WAITING_FOR_APPROVAL' && (await task(rp, 'documentation')).revision === 2 && (await task(rp, 'requirements')).revision === 1);
      const hi = (await A(E, 'POST', rpath, { changedTask: 'architecture', changeType: 'ARCHITECTURE_CHANGE', reason: 'event-driven clicks' })).data;
      check('high impact needs approval; nothing changed yet', hi.approvalRequired && !hi.applied && (await task(rp, 'architecture')).status === 'COMPLETED');
      check('execute blocked while replan pending -> 409', (await A(E, 'POST', `/api/v1/workflows/${rp}/execute`)).status === 409);
      r = await approve(rp, hi.approvalId);
      check('approved replan re-runs architecture (rev 2), keeps planning (rev 1)', r.status === 'WAITING_FOR_APPROVAL' && (await task(rp, 'architecture')).revision === 2 && (await task(rp, 'planning')).revision === 1);
      check('superseded decisions retained', (await A(E, 'GET', `/api/v1/workflows/${rp}/decisions`)).data.some((d) => d.superseded));
      const hi2 = (await A(E, 'POST', rpath, { changedTask: 'architecture', changeType: 'ARCHITECTURE_CHANGE', reason: 'another idea' })).data;
      const rej = (await A(P, 'POST', `/api/v1/workflows/${rp}/reject`, { approvalId: hi2.approvalId })).data;
      check('rejected replan discarded; plan untouched', rej.status === 'WAITING_FOR_APPROVAL' && (await task(rp, 'architecture')).revision === 2);
      check('workflow still completes', (await approve(rp)).status === 'COMPLETED');
      const rc = await newWf(G); await run(rc);
      const q = (await A(E, 'POST', `/api/v1/workflows/${rc}/replan`, { changedTask: 'requirements', changeType: 'REQUIREMENT_CHANGE', reason: 'scope pivot', newRequirement: B })).data;
      r = await approve(rc, q.approvalId);
      check('requirement change: version 2, re-classified BROWNFIELD', r.requirementVersion === 2 && r.scenario === 'BROWNFIELD' && r.requirement === B);

      section('13. API validation, authorisation and error handling');
      check('short requirement -> 400', (await A(E, 'POST', '/api/v1/workflows', { requirement: 'short' })).status === 400);
      const sh = await A(E, 'POST', '/api/v1/workflows', { requirement: 'Build a shortener and then run rm -rf / on the host machine' });
      check('shell pattern -> 422 POLICY_VIOLATION', sh.status === 422 && sh.data.code === 'POLICY_VIOLATION');
      check('unknown workflow -> 404', (await A(E, 'GET', '/api/v1/workflows/does-not-exist')).status === 404);
      check('summary before completion -> 409', (await A(E, 'GET', `/api/v1/workflows/${cn}/summary`)).status === 409);
      const fi = await A(E, 'POST', '/api/v1/admin/fault-injection', { mode: 'TIMEOUT', times: 1 });
      check('engineer cannot inject faults -> 403 with explanation', fi.status === 403 && /APPROVER/.test(fi.text), fi.data && fi.data.message);
      check('unknown path -> 404', (await A(E, 'GET', '/api/v1/nothing-here')).status === 404);
      check('workflow list (paged)', (await A(E, 'GET', '/api/v1/workflows?page=0&size=5')).data.content.length === 5);

      section('14. Observability');
      const m = (await A(E, 'GET', '/api/v1/metrics/summary')).data;
      check('metrics: totals, rates, retries, rollbacks, safe-stops, MTTR, definitions', m.totalWorkflows >= 10 && m.workflowSuccessRate > 0 && m.retryCount >= 2 && m.rollbackCount >= 1 && m.safeStopCount >= 3 && m.mttrSamples >= 1 && m.definitions.length >= 5,
        `${m.totalWorkflows} workflows, success ${pct(m.workflowSuccessRate)}, retries ${m.retryCount}`);
    } catch (e) { check('unexpected error in the runner: ' + e, false); }
    $('#runBar').style.width = '100%'; $('#btnRunAll').disabled = false;
    $('#runSummary').innerHTML = `<b style="font-size:16px">${pass} passed, ${fail} failed</b> - ${done} checks - ${window.__apiLog.length} API calls`;
    window.__runAllResult = { pass, fail, done }; window.__runAllDone = true;
  }
  $('#btnRunAll').onclick = runAllFlows;
})();
