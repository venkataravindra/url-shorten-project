// Generates postman/Agentic-Platform.postman_collection.json and Local.postman_environment.json
// Run:  node postman/generate-collection.js
const fs = require('fs'), path = require('path');

const VARS = {
  baseUrl: 'http://localhost:8080',
  engUser: 'engineer', engPass: 'engineer123', aprUser: 'approver', aprPass: 'approver123', admUser: 'admin', admPass: 'admin123',
  reqGreenfield: 'Build a URL shortener that supports custom aliases, expiration, redirects, and click analytics.',
  reqBrownfield: 'Enhance the existing URL shortener to support URL expiration and automatic deactivation of expired links.',
  reqAmbiguous: 'Improve the URL shortener performance and make it more reliable.',
  reqInsecure: 'Build a URL shortener with redirects and disable authentication for the admin endpoints',
};

const AUTH = { eng: ['engUser', 'engPass'], apr: ['aprUser', 'aprPass'], adm: ['admUser', 'admPass'] };
const st = (n, label) => `pm.test("${label || 'HTTP status is ' + n}", function () { pm.response.to.have.status(${n}); });`;
const J = 'const j = pm.response.json();';
const check = (label, expr) => `pm.test(${JSON.stringify(label)}, function () { ${J} pm.expect(${expr}).to.be.true; });`;
const setVar = (name, expr) => `{ ${J} pm.collectionVariables.set("${name}", ${expr}); }`;
const savePending = `{ ${J} if (j.pendingApprovals && j.pendingApprovals.length) { pm.collectionVariables.set("approvalId", j.pendingApprovals[0].id); } }`;
const taskOf = (k) => `j.find(function (t) { return t.taskKey === "${k}"; })`;

function R(name, method, p, o = {}) {
  const [pathPart, query] = p.split('?');
  const item = { name, request: {
    method, header: [], description: o.desc || '',
    url: { raw: '{{baseUrl}}' + p, host: ['{{baseUrl}}'], path: pathPart.split('/').filter(Boolean),
           query: query ? query.split('&').map(kv => { const [key, value] = kv.split('='); return { key, value }; }) : undefined } },
    event: [], response: [] };
  const who = o.auth === undefined ? 'eng' : o.auth;
  item.request.auth = who ? { type: 'basic', basic: [{ key: 'username', value: `{{${AUTH[who][0]}}}`, type: 'string' }, { key: 'password', value: `{{${AUTH[who][1]}}}`, type: 'string' }] } : { type: 'noauth' };
  if (o.body !== undefined) {
    item.request.header.push({ key: 'Content-Type', value: 'application/json' });
    item.request.body = { mode: 'raw', raw: typeof o.body === 'string' ? o.body : JSON.stringify(o.body, null, 2), options: { raw: { language: 'json' } } };
  }
  if (o.pre) item.event.push({ listen: 'prerequest', script: { type: 'text/javascript', exec: o.pre } });
  if (o.tests) item.event.push({ listen: 'test', script: { type: 'text/javascript', exec: o.tests } });
  if (o.noFollow) item.protocolProfileBehavior = { followRedirects: false };
  return item;
}
const F = (name, description, items) => ({ name, description, item: items });
const wf = (v) => '{{' + v + '}}';

// ---- reusable steps ---------------------------------------------------------------------------------------------
const create = (n, v, reqVar, extra = []) => R(`${n} Create workflow`, 'POST', '/api/v1/workflows', { body: { requirement: `{{${reqVar}}}`, title: v }, tests: [st(201), setVar(v, 'j.id'), ...extra] });
const exec = (n, v, expect, auth, body, extra = [], label = 'Execute workflow') => R(`${n} ${label}`, 'POST', `/api/v1/workflows/${wf(v)}/execute`,
  { auth, body, tests: [st(200), check('status is ' + expect, `j.status === "${expect}"`), savePending, ...extra] });
const approve = (n, v, expect, extra = [], withId = false, label = 'Approve (approver)') => R(`${n} ${label}`, 'POST', `/api/v1/workflows/${wf(v)}/approve`,
  { auth: 'apr', body: withId ? '{ "approvalId": {{approvalId}}, "comment": "approved in Postman" }' : { comment: 'approved in Postman' },
    tests: [st(200), check('status is ' + expect, `j.status === "${expect}"`), savePending, ...extra] });
const tasks = (n, v, label, tests, out = true) => R(`${n} ${label}`, 'GET', `/api/v1/workflows/${wf(v)}/tasks${out ? '?includeOutput=true' : ''}`, { tests: [st(200), ...tests] });
const inject = (n, op, mode, times, fb) => R(`${n} Inject fault ${mode} x${times} on ${op}`, 'POST', '/api/v1/admin/fault-injection',
  { auth: 'adm', body: { operation: op, mode, times, includeFallback: fb }, tests: [st(200)] });
const clearFaults = (n) => R(`${n} Clear injected faults (admin)`, 'DELETE', '/api/v1/admin/fault-injection', { auth: 'adm', tests: [st(200)] });

// ---- folders (the order is the execution sequence) ---------------------------------------------------------------
const folders = [];

folders.push(F('00 Setup and identity', 'Run first. Confirms the app is up and shows what each user may do.', [
  R('0.1 Health (public)', 'GET', '/api/v1/health', { auth: null, tests: [st(200), check('status UP', 'j.status === "UP"')] }),
  R('0.2 Who am I - engineer', 'GET', '/api/v1/whoami', { tests: [st(200), check('engineer cannot approve', 'j.user === "engineer" && j.canApprove === false')] }),
  R('0.3 Who am I - approver', 'GET', '/api/v1/whoami', { auth: 'apr', tests: [st(200), check('approver can approve', 'j.canApprove === true && j.canInjectFaults === false')] }),
  R('0.4 Who am I - admin', 'GET', '/api/v1/whoami', { auth: 'adm', tests: [st(200), check('admin can inject faults', 'j.canInjectFaults === true')] }),
]));

folders.push(F('01 URL shortener', 'Create, redirect, analytics, expiry, deactivation, validation.', [
  R('1.1 Create short URL (generated code)', 'POST', '/api/v1/urls', { body: { originalUrl: 'https://example.com/articles/spring-boot' },
    tests: [st(201), check('7-char code', 'j.shortCode.length === 7'), setVar('shortCode', 'j.shortCode')] }),
  R('1.2 Create with custom alias + expiration', 'POST', '/api/v1/urls', {
    pre: ['pm.collectionVariables.set("alias", "pm-" + Math.floor(Math.random() * 1e6));', 'pm.collectionVariables.set("expFuture", new Date(Date.now() + 30 * 86400000).toISOString().slice(0, 19));'],
    body: '{ "originalUrl": "https://example.com/a", "customAlias": "{{alias}}", "expirationDate": "{{expFuture}}" }',
    tests: [st(201), check('alias used', 'j.shortCode === pm.collectionVariables.get("alias") && j.customAlias === true')] }),
  R('1.3 Duplicate alias -> 409', 'POST', '/api/v1/urls', { body: '{ "originalUrl": "https://example.com/b", "customAlias": "{{alias}}" }', tests: [st(409), check('code', 'j.code === "ALIAS_ALREADY_EXISTS"')] }),
  R('1.4 javascript: URL -> 400', 'POST', '/api/v1/urls', { body: { originalUrl: 'javascript:alert(1)' }, tests: [st(400)] }),
  R('1.5 Private host -> 400', 'POST', '/api/v1/urls', { body: { originalUrl: 'http://192.168.1.5/x' }, tests: [st(400)] }),
  R('1.6 Past expiration -> 400', 'POST', '/api/v1/urls', { body: { originalUrl: 'https://example.com', expirationDate: '2001-01-01T00:00:00' }, tests: [st(400)] }),
  R('1.7 Create without login -> 401', 'POST', '/api/v1/urls', { auth: null, body: { originalUrl: 'https://example.com' }, tests: [st(401)] }),
  R('1.8 Redirect (public) -> 302 + click', 'GET', '/api/v1/urls/{{alias}}', { auth: null, noFollow: true,
    pre: ['pm.request.headers.add({ key: "Referer", value: "https://news.example.org/post" });'],
    tests: [st(302), 'pm.test("Location header", function () { pm.expect(pm.response.headers.get("Location")).to.eql("https://example.com/a"); });'] }),
  R('1.9 Redirect again (direct)', 'GET', '/api/v1/urls/{{alias}}', { auth: null, noFollow: true, tests: [st(302)] }),
  R('1.10 Analytics', 'GET', '/api/v1/urls/{{alias}}/analytics', { tests: [st(200), check('2 clicks, 2 referrers, 1 day', 'j.totalClicks === 2 && j.referrers.length === 2 && j.dailyClicks.length === 1')] }),
  R('1.11 Details', 'GET', '/api/v1/urls/{{alias}}/details', { tests: [st(200), check('click count', 'j.clickCount === 2')] }),
  R('1.12 List (paged)', 'GET', '/api/v1/urls?page=0&size=5', { tests: [st(200), check('has content', 'j.content.length > 0')] }),
  R('1.13 Deactivate -> 204', 'DELETE', '/api/v1/urls/{{alias}}', { tests: [st(204)] }),
  R('1.14 Redirect deactivated -> 410', 'GET', '/api/v1/urls/{{alias}}', { auth: null, noFollow: true, tests: [st(410), check('code', 'j.code === "SHORT_URL_INACTIVE"')] }),
  R('1.15 Reactivate -> 200', 'PUT', '/api/v1/urls/{{alias}}/status', { body: { active: true }, tests: [st(200), check('active', 'j.active === true')] }),
  R('1.16 Redirect after reactivation -> 302', 'GET', '/api/v1/urls/{{alias}}', { auth: null, noFollow: true, tests: [st(302)] }),
  R('1.17 Create link that expires in 5 s', 'POST', '/api/v1/urls', {
    pre: ['pm.collectionVariables.set("shortLived", "soon-" + Math.floor(Math.random() * 1e6));', 'pm.collectionVariables.set("expSoon", new Date(Date.now() + 5000).toISOString().slice(0, 19));'],
    body: '{ "originalUrl": "https://example.com/soon", "customAlias": "{{shortLived}}", "expirationDate": "{{expSoon}}" }', tests: [st(201)] }),
  R('1.18 Redirect after expiry -> 410 (waits ~7 s)', 'GET', '/api/v1/urls/{{shortLived}}', { auth: null, noFollow: true,
    pre: ['const end = Date.now() + 7000; while (Date.now() < end) { }'], tests: [st(410), check('code', 'j.code === "SHORT_URL_EXPIRED"')] }),
  R('1.19 Unknown code -> 404', 'GET', '/api/v1/urls/does-not-exist', { auth: null, noFollow: true, tests: [st(404)] }),
]));

folders.push(F('02 Scenario 1 - Greenfield', 'DAG with parallel branches, final human approval, summary, artifacts, lineage.', [
  create('2.1', 'wfGreen', 'reqGreenfield'),
  exec('2.2', 'wfGreen', 'WAITING_FOR_APPROVAL', 'eng', undefined, [check('RELEASE_EXECUTION pending', 'j.pendingApprovals[0].type === "RELEASE_EXECUTION" && j.scenario === "GREENFIELD"')]),
  tasks('2.3', 'wfGreen', 'DAG state: gates skipped, branches done', [check('conditional gates skipped, final gate waiting',
    `${taskOf('assumption-approval')}.status === "SKIPPED" && ${taskOf('architecture-approval')}.status === "SKIPPED" && ${taskOf('final-approval')}.status === "WAITING_APPROVAL" && ${taskOf('implementation')}.status === "COMPLETED" && ${taskOf('test-planning')}.status === "COMPLETED"`)]),
  R('2.4 Approve as engineer -> 403', 'POST', `/api/v1/workflows/${wf('wfGreen')}/approve`, { tests: [st(403), check('explains the role', 'j.code === "ACCESS_DENIED" && j.message.indexOf("APPROVER") >= 0')] }),
  approve('2.5', 'wfGreen', 'COMPLETED'),
  R('2.6 Engineering summary', 'GET', `/api/v1/workflows/${wf('wfGreen')}/summary`, { tests: [st(200), check('5 sections', 'j.summary.sections.length === 5')] }),
  R('2.7 Artifacts (all ACCEPTED)', 'GET', `/api/v1/workflows/${wf('wfGreen')}/artifacts`, { tests: [st(200), check('accepted', 'j.length >= 10 && j.every(function (a) { return a.status === "ACCEPTED"; })')] }),
  R('2.8 Decision lineage', 'GET', `/api/v1/workflows/${wf('wfGreen')}/decisions`, { tests: [st(200), check('has parents', 'j.some(function (d) { return d.parentDecisionIds.length > 0; })')] }),
  R('2.9 Approvals', 'GET', `/api/v1/workflows/${wf('wfGreen')}/approvals`, { tests: [st(200), check('1 approved', 'j.length === 1 && j[0].status === "APPROVED"')] }),
  R('2.10 Audit trail', 'GET', `/api/v1/audit/workflows/${wf('wfGreen')}`, { tests: [st(200), check('many events', 'j.length > 20')] }),
  R('2.11 Workflow metrics', 'GET', `/api/v1/metrics/workflows/${wf('wfGreen')}`, { tests: [st(200), check('10 agent executions', 'j.status === "COMPLETED" && j.agentExecutions === 10')] }),
]));

folders.push(F('03 Scenario 2 - Brownfield', 'Reflection scan of the real URL module; change impact analysis.', [
  create('3.1', 'wfBrown', 'reqBrownfield'),
  exec('3.2', 'wfBrown', 'WAITING_FOR_APPROVAL', 'eng', undefined, [check('BROWNFIELD', 'j.scenario === "BROWNFIELD"')]),
  tasks('3.3', 'wfBrown', 'Change impact analysis', [check('impact lists APIs, entities, services, repositories, risks',
    `${taskOf('architecture')}.output.changeImpact.impactedApis.length > 0 && ${taskOf('architecture')}.output.changeImpact.impactedEntities.length > 0 && ${taskOf('architecture')}.output.changeImpact.impactedServices.length > 0 && ${taskOf('architecture')}.output.changeImpact.impactedRepositories.length > 0 && ${taskOf('architecture')}.output.changeImpact.regressionRisks.length > 0 && ${taskOf('architecture')}.output.changeImpact.backwardCompatible === true`)]),
  approve('3.4', 'wfBrown', 'COMPLETED'),
]));

folders.push(F('04 Scenario 3 - Ambiguous requirement', 'Three human approvals; no plan before assumptions are approved.', [
  create('4.1', 'wfAmb', 'reqAmbiguous'),
  exec('4.2', 'wfAmb', 'WAITING_FOR_APPROVAL', 'eng', undefined, [check('assumptions gate', 'j.pendingApprovals[0].type === "MATERIAL_ASSUMPTIONS" && j.scenario === "AMBIGUOUS"')]),
  tasks('4.3', 'wfAmb', 'Questions and assumptions; planning not started', [check('questions >= 3 and planning PENDING',
    `${taskOf('requirements')}.output.clarificationQuestions.length >= 3 && ${taskOf('planning')}.status === "PENDING"`)]),
  approve('4.4', 'wfAmb', 'WAITING_FOR_APPROVAL', [check('next gate is the design', 'j.pendingApprovals[0].type === "HIGH_IMPACT_ARCHITECTURE_CHANGE"')], false, 'Approve assumptions'),
  approve('4.5', 'wfAmb', 'WAITING_FOR_APPROVAL', [check('next gate is release', 'j.pendingApprovals[0].type === "RELEASE_EXECUTION"')], false, 'Approve cache design'),
  approve('4.6', 'wfAmb', 'COMPLETED', [], false, 'Approve release'),
  tasks('4.7', 'wfAmb', 'Release readiness', [check('READY_WITH_CONDITIONS', `${taskOf('release-readiness')}.output.readiness === "READY_WITH_CONDITIONS"`)]),
]));

folders.push(F('05 Rejection and rollback', 'Reject the final approval: artifacts revert, workflow ROLLED_BACK.', [
  create('5.1', 'wfReject', 'reqGreenfield'),
  exec('5.2', 'wfReject', 'WAITING_FOR_APPROVAL', 'eng'),
  R('5.3 Reject as engineer -> 403', 'POST', `/api/v1/workflows/${wf('wfReject')}/reject`, { tests: [st(403)] }),
  R('5.4 Reject as approver -> ROLLED_BACK', 'POST', `/api/v1/workflows/${wf('wfReject')}/reject`, { auth: 'apr', body: { comment: 'not safe to release' },
    tests: [st(200), check('rolled back', 'j.status === "ROLLED_BACK"')] }),
  R('5.5 Artifacts reverted', 'GET', `/api/v1/workflows/${wf('wfReject')}/artifacts`, { tests: [st(200), check('all REVERTED', 'j.every(function (a) { return a.status === "REVERTED"; })')] }),
  R('5.6 Execute rolled-back workflow -> 409', 'POST', `/api/v1/workflows/${wf('wfReject')}/execute`, { tests: [st(409)] }),
]));

folders.push(F('06 Safe-stop on security concern', 'Critical finding stops the workflow; only an approver with acknowledgement resumes.', [
  create('6.1', 'wfSec', 'reqInsecure'),
  exec('6.2', 'wfSec', 'WAITING_FOR_APPROVAL', 'eng', undefined, [check('design gate', 'j.pendingApprovals[0].type === "SECURITY_SENSITIVE_CHANGE"')]),
  approve('6.3', 'wfSec', 'SAFE_STOPPED', [check('SECURITY_CONCERN', 'j.safeStopCategory === "SECURITY_CONCERN" && j.safeStopReason.indexOf("Critical review finding") >= 0')]),
  R('6.4 Resume as engineer -> 403', 'POST', `/api/v1/workflows/${wf('wfSec')}/execute`, { tests: [st(403)] }),
  R('6.5 Resume as approver without acknowledgement -> 400', 'POST', `/api/v1/workflows/${wf('wfSec')}/execute`, { auth: 'apr', tests: [st(400)] }),
  exec('6.6', 'wfSec', 'SAFE_STOPPED', 'apr', { acknowledgement: 'reviewed the finding' }, [], 'Resume with acknowledgement (violation stops it again)'),
]));

folders.push(F('07 Failure handling (admin fault injection)', 'Retry, fallback, exhaustion, validation failure, secret leak. Needs the admin user.', [
  inject('7.1', 'DECOMPOSE_TASKS', 'TIMEOUT', 2, false),
  create('7.2', 'wfRetry', 'reqGreenfield'),
  exec('7.3', 'wfRetry', 'WAITING_FOR_APPROVAL', 'eng'),
  tasks('7.4', 'wfRetry', 'Planning needed 3 attempts', [check('attempts = 3', `${taskOf('planning')}.attempts === 3`)], false),
  R('7.5 Retry audit events (backoff)', 'GET', `/api/v1/audit/workflows/${wf('wfRetry')}`, { tests: [st(200), check('2 retries scheduled',
    'j.filter(function (e) { return e.eventType === "TASK_RETRY_SCHEDULED"; }).length === 2')] }),
  inject('7.6', 'PLAN_CODE_GENERATION', 'PROVIDER_UNAVAILABLE', 10, false),
  create('7.7', 'wfFallback', 'reqGreenfield'),
  exec('7.8', 'wfFallback', 'WAITING_FOR_APPROVAL', 'eng'),
  tasks('7.9', 'wfFallback', 'Fallback provider used (degraded)', [check('fallback used', `${taskOf('implementation')}.output._execution.fallbackUsed === true && ${taskOf('implementation')}.output._execution.degraded === true`)]),
  clearFaults('7.10'),
  inject('7.11', 'DECOMPOSE_TASKS', 'TIMEOUT', 20, true),
  create('7.12', 'wfExhaust', 'reqGreenfield'),
  exec('7.13', 'wfExhaust', 'SAFE_STOPPED', 'eng', undefined, [check('MAX_RETRIES_EXCEEDED', 'j.safeStopCategory === "MAX_RETRIES_EXCEEDED"')], 'Execute -> retries exhausted -> safe-stop'),
  clearFaults('7.14'),
  exec('7.15', 'wfExhaust', 'WAITING_FOR_APPROVAL', 'apr', { acknowledgement: 'provider recovered' }, [], 'Human resumes with acknowledgement'),
  approve('7.16', 'wfExhaust', 'COMPLETED'),
  inject('7.17', 'ANALYZE_REQUIREMENT', 'MALFORMED_OUTPUT', 1, false),
  create('7.18', 'wfInvalid', 'reqGreenfield'),
  exec('7.19', 'wfInvalid', 'FAILED', 'eng', undefined, [check('not retried', 'j.failureReason.indexOf("missing required field") >= 0')], 'Execute -> validation failure (no retry)'),
  clearFaults('7.20'),
  inject('7.21', 'GENERATE_DOCUMENTATION', 'SECRET_LEAK', 1, true),
  create('7.22', 'wfLeak', 'reqGreenfield'),
  exec('7.23', 'wfLeak', 'SAFE_STOPPED', 'eng', undefined, [check('SECURITY_CONCERN', 'j.safeStopCategory === "SECURITY_CONCERN"')], 'Execute -> credential blocked -> safe-stop'),
  R('7.24 Artifacts contain no secret', 'GET', `/api/v1/workflows/${wf('wfLeak')}/artifacts?includeContent=true`, { tests: [st(200), 'pm.test("no AKIA key stored", function () { pm.expect(pm.response.text()).to.not.include("AKIA"); });'] }),
  clearFaults('7.25'),
  R('7.26 Engineer cannot inject faults -> 403', 'POST', '/api/v1/admin/fault-injection', { body: { mode: 'TIMEOUT', times: 1 }, tests: [st(403)] }),
]));

folders.push(F('08 Stop, resume, cancel', 'Operator controls.', [
  create('8.1', 'wfStop', 'reqGreenfield'),
  R('8.2 Stop (safe-stop by operator)', 'POST', `/api/v1/workflows/${wf('wfStop')}/stop`, { body: { reason: 'maintenance window' }, tests: [st(200), check('OPERATOR_REQUEST', 'j.status === "SAFE_STOPPED" && j.safeStopCategory === "OPERATOR_REQUEST"')] }),
  R('8.3 Resume as engineer -> 403', 'POST', `/api/v1/workflows/${wf('wfStop')}/execute`, { tests: [st(403)] }),
  exec('8.4', 'wfStop', 'WAITING_FOR_APPROVAL', 'apr', { acknowledgement: 'window over' }, [], 'Resume as approver'),
  approve('8.5', 'wfStop', 'COMPLETED'),
  create('8.6', 'wfCancel', 'reqGreenfield'),
  R('8.7 Cancel', 'POST', `/api/v1/workflows/${wf('wfCancel')}/cancel`, { body: { reason: 'not needed' }, tests: [st(200), check('CANCELLED', 'j.status === "CANCELLED"')] }),
  R('8.8 Execute cancelled -> 409', 'POST', `/api/v1/workflows/${wf('wfCancel')}/execute`, { tests: [st(409)] }),
]));

const replan = (n, body, tests, label) => R(`${n} ${label}`, 'POST', `/api/v1/workflows/${wf('wfReplan')}/replan`, { body, tests });
folders.push(F('09 Dynamic replanning', 'Impact analysis, approval for high-impact changes, preserved work, superseded decisions.', [
  create('9.1', 'wfReplan', 'reqGreenfield'),
  exec('9.2', 'wfReplan', 'WAITING_FOR_APPROVAL', 'eng'),
  replan('9.3', { changedTask: 'nope', changeType: 'DOCUMENTATION_CHANGE', reason: 'x' }, [st(400)], 'Unknown task -> 400'),
  replan('9.4', { changedTask: 'documentation', changeType: 'DOCUMENTATION_CHANGE', reason: 'cycle', addDependencies: [{ from: 'final-approval', to: 'requirements' }] }, [st(422)], 'Cyclic dependency -> 422'),
  replan('9.5', { changedTask: 'documentation', changeType: 'DOCUMENTATION_CHANGE', reason: 'docs outdated' },
    [st(200), check('applied, work preserved', 'j.applied === true && j.impactedTasks.indexOf("security-review") >= 0 && j.preservedTasks.indexOf("planning") >= 0 && j.workflowStatus === "CREATED"')], 'Low-impact replan (applied immediately)'),
  exec('9.6', 'wfReplan', 'WAITING_FOR_APPROVAL', 'eng', undefined, [], 'Execute (re-runs only stale tasks)'),
  replan('9.7', { changedTask: 'architecture', changeType: 'ARCHITECTURE_CHANGE', reason: 'event-driven clicks' },
    [st(200), check('approval required', 'j.approvalRequired === true && j.applied === false'), setVar('replanApprovalId', 'j.approvalId')], 'High-impact replan (needs approval)'),
  R('9.8 Execute while replan pending -> 409', 'POST', `/api/v1/workflows/${wf('wfReplan')}/execute`, { tests: [st(409)] }),
  R('9.9 Approve as engineer -> 403', 'POST', `/api/v1/workflows/${wf('wfReplan')}/approve`, { tests: [st(403)] }),
  R('9.10 Approve the replan (approvalId)', 'POST', `/api/v1/workflows/${wf('wfReplan')}/approve`, { auth: 'apr', body: '{ "approvalId": {{replanApprovalId}}, "comment": "replan approved" }',
    tests: [st(200), check('re-executed up to the final gate', 'j.status === "WAITING_FOR_APPROVAL"'), savePending] }),
  tasks('9.11', 'wfReplan', 'Architecture revision 2, planning still revision 1', [check('revisions', `${taskOf('architecture')}.revision === 2 && ${taskOf('planning')}.revision === 1`)], false),
  R('9.12 Decisions: old ones superseded, not deleted', 'GET', `/api/v1/workflows/${wf('wfReplan')}/decisions`, { tests: [st(200), check('superseded exist', 'j.some(function (d) { return d.superseded === true; })')] }),
  replan('9.13', { changedTask: 'architecture', changeType: 'ARCHITECTURE_CHANGE', reason: 'another idea' },
    [st(200), setVar('replanApprovalId', 'j.approvalId')], 'Second high-impact proposal'),
  R('9.14 Reject the proposal (discarded)', 'POST', `/api/v1/workflows/${wf('wfReplan')}/reject`, { auth: 'apr', body: '{ "approvalId": {{replanApprovalId}} }',
    tests: [st(200), check('plan untouched, back to waiting', 'j.status === "WAITING_FOR_APPROVAL"'), savePending] }),
  approve('9.15', 'wfReplan', 'COMPLETED', [], true, 'Approve final approval (approvalId) -> COMPLETED'),
  create('9.16', 'wfReq', 'reqGreenfield'),
  exec('9.17', 'wfReq', 'WAITING_FOR_APPROVAL', 'eng'),
  R('9.18 Requirement change proposal', 'POST', `/api/v1/workflows/${wf('wfReq')}/replan`, { body: '{ "changedTask": "requirements", "changeType": "REQUIREMENT_CHANGE", "reason": "scope pivot", "newRequirement": "{{reqBrownfield}}" }',
    tests: [st(200), check('approval needed, nothing preserved', 'j.approvalRequired === true && j.preservedTasks.length === 0'), setVar('approvalId', 'j.approvalId')] }),
  R('9.19 Approve requirement change', 'POST', `/api/v1/workflows/${wf('wfReq')}/approve`, { auth: 'apr', body: '{ "approvalId": {{approvalId}} }',
    tests: [st(200), check('v2, re-classified as BROWNFIELD', 'j.requirementVersion === 2 && j.scenario === "BROWNFIELD"')] }),
]));

folders.push(F('10 Validation and security checks', 'Input validation, auth, error handling.', [
  R('10.1 Requirement too short -> 400', 'POST', '/api/v1/workflows', { body: { requirement: 'short' }, tests: [st(400), check('code', 'j.code === "VALIDATION_FAILED"')] }),
  R('10.2 Shell command in requirement -> 422', 'POST', '/api/v1/workflows', { body: { requirement: 'Build a shortener and then run rm -rf / on the host machine' }, tests: [st(422), check('code', 'j.code === "POLICY_VIOLATION"')] }),
  R('10.3 No credentials -> 401', 'POST', '/api/v1/workflows', { auth: null, body: { requirement: 'Build a URL shortener please' }, tests: [st(401)] }),
  R('10.4 Unknown workflow -> 404', 'GET', '/api/v1/workflows/does-not-exist', { tests: [st(404)] }),
  R('10.5 Summary before completion -> 409', 'GET', `/api/v1/workflows/${wf('wfCancel')}/summary`, { tests: [st(409)] }),
  R('10.6 Unknown path -> 404', 'GET', '/api/v1/nothing-here', { tests: [st(404)] }),
  R('10.7 Wrong method -> 405', 'PUT', '/api/v1/workflows', { tests: [st(405)] }),
  R('10.8 List workflows', 'GET', '/api/v1/workflows?page=0&size=5', { tests: [st(200), check('5 items', 'j.content.length === 5')] }),
]));

folders.push(F('11 Observability', 'Metrics and audit, computed from persisted data. Run last.', [
  R('11.1 Metrics summary', 'GET', '/api/v1/metrics/summary', { tests: [st(200), check('totals, retries, rollbacks, safe-stops, MTTR, definitions',
    'j.totalWorkflows >= 10 && j.retryCount >= 2 && j.rollbackCount >= 1 && j.safeStopCount >= 3 && j.mttrSamples >= 1 && j.definitions.length >= 5')] }),
  R('11.2 Audit trail of the safe-stopped security workflow', 'GET', `/api/v1/audit/workflows/${wf('wfSec')}`, { tests: [st(200), check('SAFE_STOPPED and resume audited',
    'j.some(function (e) { return e.eventType === "SAFE_STOPPED"; }) && j.some(function (e) { return e.eventType === "SAFE_STOP_RESUMED"; })')] }),
  R('11.3 Per-workflow metrics (greenfield)', 'GET', `/api/v1/metrics/workflows/${wf('wfGreen')}`, { tests: [st(200)] }),
]));

const collection = {
  info: { name: 'Agentic Engineering Platform + URL Shortener', schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json',
    description: 'Run the folders in order (Collection Runner). Each request has tests; IDs are captured automatically. Users: engineer / approver / admin (see variables).' },
  item: folders,
  variable: Object.entries(VARS).map(([key, value]) => ({ key, value, type: 'string' })),
};
const environment = { id: 'agentic-local', name: 'Agentic Local', values: Object.entries(VARS).map(([key, value]) => ({ key, value, type: 'default', enabled: true })),
  _postman_variable_scope: 'environment' };
const out = __dirname;
fs.writeFileSync(path.join(out, 'Agentic-Platform.postman_collection.json'), JSON.stringify(collection, null, 2));
fs.writeFileSync(path.join(out, 'Local.postman_environment.json'), JSON.stringify(environment, null, 2));
let n = 0; (function count(items) { items.forEach(i => i.item ? count(i.item) : n++); })(folders);
console.log(`Generated ${folders.length} folders, ${n} requests`);