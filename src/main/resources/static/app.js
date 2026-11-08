'use strict';
/* Agentic Engineering Console - vanilla JS, no build step. Talks to the same-origin REST API with HTTP Basic auth. */
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const CREDS = { engineer: 'engineer123', approver: 'approver123', admin: 'admin123' }; // local development defaults
const PRESETS = {
  greenfield: 'Build a URL shortener that supports custom aliases, expiration, redirects, and click analytics.',
  brownfield: 'Enhance the existing URL shortener to support URL expiration and automatic deactivation of expired links.',
  ambiguous: 'Improve the URL shortener performance and make it more reliable.',
  insecure: 'Build a URL shortener with redirects and disable authentication for the admin endpoints',
  custom: '',
};
const state = { user: null, pass: null, roles: [], tab: 'dashboard', wfId: null, wfTab: 'tasks', wf: null, tasks: [], approvals: [] };
window.__apiLog = []; window.__inflight = 0;
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const badge = (s) => `<span class="badge s-${esc(s)}">${esc(s)}</span>`;
const pct = (v) => (v == null ? 'n/a' : (v * 100).toFixed(1) + '%');
const ms = (v) => (v == null ? 'n/a' : Math.round(v).toLocaleString() + ' ms');
const sleep = (t) => new Promise((r) => setTimeout(r, t));

function toast(msg, kind = '') {
  const el = document.createElement('div'); el.className = 'toast ' + kind; el.textContent = msg;
  $('#toasts').appendChild(el); setTimeout(() => el.remove(), 6500);
}
window.__caption = (text) => { const c = $('#caption'); c.textContent = text || ''; c.style.display = text ? 'block' : 'none'; };
window.__resetLog = () => { window.__apiLog = []; };

function logCall(user, method, path, status, took) {
  const n = window.__apiLog.length + 1;
  window.__apiLog.push({ n, user: user || '-', method, path, status, ms: took });
  const cls = status >= 500 || status === 0 ? 'code-bad' : status >= 400 ? 'code-warn' : 'code-ok';
  const tr = document.createElement('tr');
  tr.innerHTML = `<td>${n}</td><td>${esc(user || '-')}</td><td class="m-${esc(method)}">${esc(method)}</td><td class="mono">${esc(path)}</td><td class="code2 ${cls}">${status}</td><td>${took}</td>`;
  const body = $('#apiLog'); body.prepend(tr);
  while (body.children.length > 120) body.lastChild.remove();
  $('#apiCount').textContent = String(window.__apiLog.length);
}

/** Calls the API. Never throws: returns {status, ok, data, text, ms}. `as` overrides the signed-in user (used by "Run all flows"). */
async function api(method, path, body, as) {
  const user = as || state.user; const pass = as ? CREDS[as] : state.pass;
  const headers = { 'X-Requested-With': 'XMLHttpRequest' };
  if (user) headers.Authorization = 'Basic ' + btoa(user + ':' + pass);
  let payload;
  if (body !== undefined && body !== null) { headers['Content-Type'] = 'application/json'; payload = typeof body === 'string' ? body : JSON.stringify(body); }
  const t0 = performance.now(); let status = 0, text = '', data = null;
  window.__inflight++;
  try {
    const res = await fetch(path, { method, headers, body: payload });
    status = res.status; text = await res.text();
    try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  } catch (e) { text = String(e); } finally { window.__inflight--; }
  const took = Math.round(performance.now() - t0);
  logCall(user, method, path, status, took);
  return { status, ok: status >= 200 && status < 300, data, text, ms: took };
}
const errText = (r) => (r.data && r.data.message) || r.text || ('HTTP ' + r.status);
window.api = api; window.state = state;

/* ------------------------------------------------------------------ login and tabs */
function renderUser() {
  const chip = $('#userChip');
  if (!state.user) { chip.style.display = 'none'; return; }
  chip.style.display = ''; chip.className = 'badge s-COMPLETED';
  chip.textContent = `${state.user} (${state.roles.map((r) => r.replace('ROLE_', '')).join(', ')})`;
}
async function login(user, pass) {
  state.user = user; state.pass = pass;
  const r = await api('GET', '/api/v1/whoami');
  if (!r.ok) { state.user = null; state.roles = []; renderUser(); toast(`Sign-in failed (HTTP ${r.status}): check user and password`, 'err'); return false; }
  state.roles = r.data.roles; renderUser(); toast(`Signed in as ${user}`, 'ok'); refreshCurrent(); return true;
}
function showTab(name) {
  state.tab = name;
  $$('#tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
  $$('section.tab').forEach((s) => s.classList.toggle('active', s.id === 'tab-' + name));
  refreshCurrent();
}
function refreshCurrent() {
  if (!state.user) return;
  ({ dashboard: loadDashboard, urls: loadUrls, workflows: loadWorkflows, admin: loadFaults }[state.tab] || (() => {}))();
}

/* ------------------------------------------------------------------ dashboard */
async function loadDashboard() {
  const r = await api('GET', '/api/v1/metrics/summary');
  if (!r.ok) { $('#dashCards').innerHTML = `<div class="alert err">${esc(errText(r))}</div>`; return; }
  const m = r.data;
  const cards = [['Workflows', m.totalWorkflows], ['Success rate', pct(m.workflowSuccessRate)], ['Failure rate', pct(m.workflowFailureRate)], ['Retries', m.retryCount],
    ['Rollbacks', m.rollbackCount], ['Safe-stops', m.safeStopCount], ['Avg end-to-end', ms(m.avgEndToEndLatencyMs)], ['Avg approval wait', ms(m.avgApprovalWaitMs)],
    ['Task success rate', pct(m.taskSuccessRate)], ['MTTR (prototype)', ms(m.mttrMs)]];
  $('#dashCards').innerHTML = cards.map(([k, v]) => `<div class="stat"><b>${esc(v)}</b><span>${esc(k)}</span></div>`).join('');
  const total = Math.max(1, m.totalWorkflows);
  $('#dashStatus').innerHTML = Object.entries(m.workflowsByStatus).map(([k, v]) =>
    `<div style="display:flex;gap:8px;align-items:center;margin:5px 0"><div style="width:170px">${badge(k)}</div><div class="barbox" style="flex:1"><div class="bar" style="width:${(100 * v / total).toFixed(1)}%"></div></div><b style="width:40px;text-align:right">${v}</b></div>`).join('');
  $('#dashAgents').innerHTML = '<table><thead><tr><th>Agent</th><th>Executions</th><th>Avg duration</th></tr></thead><tbody>' +
    m.agentExecutions.map((a) => `<tr><td>${esc(a.agent)}</td><td>${a.executions}</td><td>${ms(a.avgDurationMs)}</td></tr>`).join('') + '</tbody></table>';
  $('#dashDefs').innerHTML = m.definitions.map((d) => `<li>${esc(d)}</li>`).join('');
  const w = await api('GET', '/api/v1/workflows?page=0&size=6');
  $('#dashRecent').innerHTML = w.ok ? '<table><thead><tr><th>Title</th><th>Scenario</th><th>Status</th><th>Created</th></tr></thead><tbody>' +
    w.data.content.map((x) => `<tr><td>${esc(x.title)}</td><td>${esc(x.scenario)}</td><td>${badge(x.status)}</td><td>${esc((x.createdAt || '').replace('T', ' ').slice(0, 19))}</td></tr>`).join('') + '</tbody></table>' : '';
}

/* ------------------------------------------------------------------ URL shortener */
async function createUrl() {
  const body = { originalUrl: $('#urlOriginal').value.trim() };
  if ($('#urlAlias').value.trim()) body.customAlias = $('#urlAlias').value.trim();
  let exp = $('#urlExpiry').value; if (exp) { if (exp.length === 16) exp += ':00'; body.expirationDate = exp; }
  const r = await api('POST', '/api/v1/urls', body);
  $('#urlMsg').innerHTML = r.ok ? `<div class="alert okk">Created <b class="mono">${esc(r.data.shortUrl)}</b></div>` : `<div class="alert err">HTTP ${r.status}: ${esc(errText(r))}</div>`;
  if (r.ok) { $('#urlAlias').value = ''; loadUrls(); }
}
async function loadUrls() {
  const r = await api('GET', '/api/v1/urls?page=0&size=15');
  if (!r.ok) { $('#urlTable tbody').innerHTML = `<tr><td colspan="6"><div class="alert err">${esc(errText(r))}</div></td></tr>`; return; }
  $('#urlTable tbody').innerHTML = r.data.content.map((u) => {
    const st = !u.active ? 'FAILED' : u.expired ? 'STALE' : 'COMPLETED';
    const label = !u.active ? 'deactivated' : u.expired ? 'expired' : 'active';
    return `<tr><td class="mono"><b>${esc(u.shortCode)}</b></td><td class="mono" style="max-width:340px;overflow:hidden;text-overflow:ellipsis">${esc(u.originalUrl)}</td>
      <td>${u.clickCount}</td><td><span class="badge s-${st}">${label}</span></td><td>${esc(u.expirationDate || '-')}</td>
      <td><button class="btn small" data-act="visit" data-code="${esc(u.shortCode)}">Simulate 3 visits</button>
      <button class="btn small" data-act="analytics" data-code="${esc(u.shortCode)}">Analytics</button>
      <button class="btn small ${u.active ? 'danger' : 'ok'}" data-act="${u.active ? 'deactivate' : 'activate'}" data-code="${esc(u.shortCode)}">${u.active ? 'Deactivate' : 'Reactivate'}</button></td></tr>`;
  }).join('') || '<tr><td colspan="6" class="muted">No short URLs yet.</td></tr>';
}
async function visit(code) {
  const codes = [];
  for (let i = 0; i < 3; i++) {
    const t0 = performance.now(); window.__inflight++;
    try { const res = await fetch('/api/v1/urls/' + encodeURIComponent(code), { redirect: 'manual' }); codes.push(res.type === 'opaqueredirect' ? 302 : res.status); logCall('public', 'GET', '/api/v1/urls/' + code, res.type === 'opaqueredirect' ? 302 : res.status, Math.round(performance.now() - t0)); }
    catch (e) { codes.push(0); } finally { window.__inflight--; }
  }
  toast(`3 visits -> HTTP ${codes.join(', ')} (302 = redirect issued)`, codes.every((c) => c === 302) ? 'ok' : 'err');
  loadUrls();
}
async function showAnalytics(code) {
  const r = await api('GET', `/api/v1/urls/${encodeURIComponent(code)}/analytics`);
  $('#urlAnalyticsCard').style.display = ''; $('#urlAnalyticsCode').textContent = code;
  if (!r.ok) { $('#urlAnalytics').innerHTML = `<div class="alert err">${esc(errText(r))}</div>`; return; }
  const a = r.data; const max = Math.max(1, ...a.referrers.map((x) => x.clicks));
  $('#urlAnalytics').innerHTML = `<div class="grid cols-4"><div class="stat"><b>${a.totalClicks}</b><span>Total clicks</span></div>
    <div class="stat"><b style="font-size:14px">${esc(a.firstAccessedAt || '-')}</b><span>First accessed</span></div><div class="stat"><b style="font-size:14px">${esc(a.lastAccessedAt || '-')}</b><span>Last accessed</span></div></div>
    <h3>Referrers</h3>${a.referrers.map((x) => `<div style="display:flex;gap:8px;align-items:center;margin:4px 0"><div style="width:180px" class="mono">${esc(x.referrer)}</div><div class="barbox" style="flex:1"><div class="bar" style="width:${100 * x.clicks / max}%"></div></div><b>${x.clicks}</b></div>`).join('') || '<span class="muted">none</span>'}
    <h3>Daily clicks</h3><table><thead><tr><th>Date</th><th>Clicks</th></tr></thead><tbody>${a.dailyClicks.map((d) => `<tr><td>${esc(d.date)}</td><td>${d.clicks}</td></tr>`).join('')}</tbody></table>`;
}

/* ------------------------------------------------------------------ workflows */
async function loadWorkflows() {
  const r = await api('GET', '/api/v1/workflows?page=0&size=20');
  if (!r.ok) { $('#wfList').innerHTML = `<div class="alert err">${esc(errText(r))}</div>`; return; }
  $('#wfList').innerHTML = r.data.content.map((w) => `<div class="wf-item ${w.id === state.wfId ? 'sel' : ''}" data-act="select" data-id="${esc(w.id)}">
    <div style="display:flex;justify-content:space-between;gap:6px"><b>${esc(w.title)}</b>${badge(w.status)}</div><small>${esc(w.scenario)} - ${esc((w.createdAt || '').replace('T', ' ').slice(0, 19))}</small></div>`).join('') || '<span class="muted">none yet</span>';
}
async function createWorkflow() {
  const requirement = $('#wfRequirement').value.trim(); const title = $('#wfTitle').value.trim();
  const r = await api('POST', '/api/v1/workflows', title ? { requirement, title } : { requirement });
  if (!r.ok) { toast(`Create failed (HTTP ${r.status}): ${errText(r)}`, 'err'); return; }
  toast('Workflow created', 'ok'); state.wfId = r.data.id; state.wfTab = 'tasks'; await loadWorkflows(); await refreshWorkflow();
}
async function refreshWorkflow() {
  if (!state.wfId) return;
  const id = state.wfId;
  const [w, t, a] = await Promise.all([api('GET', `/api/v1/workflows/${id}`), api('GET', `/api/v1/workflows/${id}/tasks?includeOutput=true`), api('GET', `/api/v1/workflows/${id}/approvals`)]);
  if (!w.ok) { $('#wfDetail').innerHTML = `<div class="card"><div class="alert err">${esc(errText(w))}</div></div>`; return; }
  state.wf = w.data; state.tasks = t.ok ? t.data : []; state.approvals = a.ok ? a.data : [];
  renderWorkflow(); loadWorkflows();
}
function dagSvg(tasks) {
  const byKey = Object.fromEntries(tasks.map((t) => [t.taskKey, t])); const level = {};
  const lv = (k) => (level[k] != null ? level[k] : (level[k] = byKey[k].dependsOn.length ? 1 + Math.max(...byKey[k].dependsOn.map(lv)) : 0));
  tasks.forEach((t) => lv(t.taskKey));
  const cols = {}; tasks.forEach((t) => (cols[level[t.taskKey]] = cols[level[t.taskKey]] || []).push(t));
  const W = 142, H = 44, GX = 28, GY = 16; const nCols = Object.keys(cols).length; const maxRows = Math.max(...Object.values(cols).map((c) => c.length));
  const pos = {}; Object.entries(cols).forEach(([c, list]) => { const off = ((maxRows - list.length) * (H + GY)) / 2; list.forEach((t, i) => (pos[t.taskKey] = { x: 10 + c * (W + GX), y: 10 + off + i * (H + GY) })); });
  const color = { COMPLETED: '#2f9e44', SKIPPED: '#adb5bd', WAITING_APPROVAL: '#e8890c', RUNNING: '#1c7ed6', RETRYING: '#e8890c', FAILED: '#d6336c', STALE: '#7048e8', PENDING: '#ced4da' };
  let edges = '', nodes = '';
  tasks.forEach((t) => t.dependsOn.forEach((d) => { const a = pos[d], b = pos[t.taskKey]; const x1 = a.x + W, y1 = a.y + H / 2, x2 = b.x, y2 = b.y + H / 2;
    edges += `<path d="M${x1},${y1} C${x1 + GX / 2},${y1} ${x2 - GX / 2},${y2} ${x2},${y2}" fill="none" stroke="#9aa5bd" stroke-width="1.4"/>`; }));
  tasks.forEach((t) => { const p = pos[t.taskKey]; const c = color[t.status] || '#ced4da'; const approval = t.nodeType === 'APPROVAL';
    nodes += `<g><rect x="${p.x}" y="${p.y}" width="${W}" height="${H}" rx="${approval ? 22 : 8}" fill="#fff" stroke="${c}" stroke-width="2.5" ${approval ? 'stroke-dasharray="5 3"' : ''}/>
      <rect x="${p.x}" y="${p.y}" width="7" height="${H}" rx="3" fill="${c}"/><text x="${p.x + 16}" y="${p.y + 18}" font-weight="600">${esc(t.taskKey)}</text>
      <text class="n-st" x="${p.x + 16}" y="${p.y + 33}">${approval ? 'human gate - ' : ''}${esc(t.status)}${t.revision > 1 ? ' - rev ' + t.revision : ''}</text></g>`; });
  return `<svg id="dagSvg" viewBox="0 0 ${nCols * (W + GX) + 10} ${maxRows * (H + GY) + 14}" style="width:100%;max-width:${nCols * (W + GX) + 10}px;height:auto" xmlns="http://www.w3.org/2000/svg">${edges}${nodes}</svg>`;
}
function renderWorkflow() {
  const w = state.wf; if (!w) return;
  const pending = w.pendingApprovals || [];
  const busy = ['RUNNING', 'RETRYING'].includes(w.status);
  $('#wfDetail').innerHTML = `
  <div class="card">
    <div class="row"><div style="flex:4"><h2 style="margin:0">${esc(w.title)}</h2><div class="mono muted">${esc(w.id)}</div>
      <div class="muted req" style="margin-top:6px">${esc(w.requirement)}</div></div><div class="auto">${badge(w.status)}</div><div class="auto"><span class="badge">${esc(w.scenario)}</span></div></div>
    ${w.safeStopReason ? `<div class="alert err"><b>Safe-stopped (${esc(w.safeStopCategory)}):</b> ${esc(w.safeStopReason)}</div>` : ''}
    ${w.failureReason ? `<div class="alert err"><b>Failure:</b> ${esc(w.failureReason)}</div>` : ''}
    <h3>Task DAG (dashed = human gate)</h3><div class="dag" id="wfDag">${dagSvg(state.tasks)}</div>
    <div class="row" style="margin-top:10px">
      <div class="auto"><button class="btn primary" id="btnExecute" data-act="execute" ${busy ? 'disabled' : ''}>Execute</button></div>
      ${w.status === 'SAFE_STOPPED' ? `<div><label for="wfAck">Acknowledgement (to resume)</label><input id="wfAck" value="reviewed the stop reason"></div><div class="auto"><button class="btn warn" id="btnResume" data-act="resume">Resume (APPROVER)</button></div>` : ''}
      <div><label for="wfStopReason">Reason (stop / cancel)</label><input id="wfStopReason" value="operator request"></div>
      <div class="auto"><button class="btn" id="btnStop" data-act="stop">Safe-stop</button></div><div class="auto"><button class="btn" id="btnCancel" data-act="cancel">Cancel</button></div>
      <div class="auto"><button class="btn" data-act="reload">Reload</button></div>
    </div>
    ${pending.length ? `<h3>Pending approvals (human-in-the-loop)</h3><div id="wfApprovals">${pending.map((p) => `<div class="alert warnn"><b>${esc(p.type)}</b> (approval #${p.id}, gate: ${esc(p.taskKey || 'replan')})<br>${esc(p.description)}
      <div class="row" style="margin-top:8px"><div><label for="wfComment${p.id}">Comment</label><input id="wfComment${p.id}" value="reviewed and approved" ></div>
      <div class="auto"><button class="btn ok" data-act="approve" data-id="${p.id}">Approve</button></div><div class="auto"><button class="btn danger" data-act="reject" data-id="${p.id}">Reject</button></div></div></div>`).join('')}</div>` : ''}
    <div class="subtabs" id="wfSubtabs">${['tasks', 'artifacts', 'decisions', 'approvals', 'audit', 'metrics', 'summary', 'replan'].map((t) => `<button data-act="subtab" data-t="${t}" class="${state.wfTab === t ? 'active' : ''}">${t}</button>`).join('')}</div>
    <div id="wfPane"></div>
  </div>`;
  renderPane();
}
async function renderPane() {
  const id = state.wfId; const pane = $('#wfPane'); if (!pane) return; const t = state.wfTab;
  if (t === 'tasks') {
    pane.innerHTML = '<table><thead><tr><th>Task</th><th>Agent / type</th><th>Status</th><th>Rev</th><th>Attempts</th><th>Depends on</th><th>Failure</th><th>Output</th></tr></thead><tbody>' +
      state.tasks.map((x, i) => `<tr><td><b>${esc(x.taskKey)}</b></td><td>${esc(x.agentId || x.nodeType)}</td><td>${badge(x.status)}</td><td>${x.revision}</td><td>${x.attempts}</td><td class="mono">${esc(x.dependsOn.join(', '))}</td><td>${esc(x.failureKind ? x.failureKind + ': ' + (x.failureReason || '') : '')}</td>
        <td>${x.output ? `<button class="btn small" data-act="viewout" data-key="${esc(x.taskKey)}">View</button>` : ''}</td></tr><tr id="out-${esc(x.taskKey)}" style="display:none"><td colspan="8"><pre>${esc(JSON.stringify(x.output, null, 2))}</pre></td></tr>`).join('') + '</tbody></table>';
  } else if (t === 'approvals') {
    pane.innerHTML = '<table><thead><tr><th>#</th><th>Type</th><th>Gate</th><th>Status</th><th>Decided by</th><th>Comment</th></tr></thead><tbody>' +
      state.approvals.map((a) => `<tr><td>${a.id}</td><td>${esc(a.type)}</td><td>${esc(a.taskKey || 'replan')}</td><td>${badge(a.status)}</td><td>${esc(a.decidedBy || '-')}</td><td>${esc(a.comment || '')}</td></tr>`).join('') + '</tbody></table>';
  } else if (t === 'replan') {
    pane.innerHTML = `<p class="muted">Replan after an upstream change: impacted tasks are re-run, unaffected completed work is preserved. REQUIREMENT_CHANGE and ARCHITECTURE_CHANGE need an approval.</p>
      <div class="row"><div><label for="rpTask">Changed task</label><select id="rpTask">${state.tasks.map((x) => `<option ${x.taskKey === 'documentation' ? 'selected' : ''}>${esc(x.taskKey)}</option>`).join('')}</select></div>
      <div><label for="rpType">Change type</label><select id="rpType"><option>DOCUMENTATION_CHANGE</option><option>TEST_STRATEGY_CHANGE</option><option>DESIGN_DETAIL_CHANGE</option><option>ARCHITECTURE_CHANGE</option><option>REQUIREMENT_CHANGE</option></select></div>
      <div style="flex:2"><label for="rpReason">Reason</label><input id="rpReason" value="docs were outdated"></div><div class="auto"><button class="btn warn" id="btnReplan" data-act="replan">Replan</button></div></div>
      <div id="rpReqBox" style="display:none"><label for="rpReq">New requirement</label><textarea id="rpReq">${esc(PRESETS.brownfield)}</textarea></div><div id="rpResult"></div>`;
    $('#rpType').onchange = () => { $('#rpReqBox').style.display = $('#rpType').value === 'REQUIREMENT_CHANGE' ? '' : 'none'; };
  } else {
    pane.innerHTML = '<span class="muted">Loading...</span>';
    const ep = { artifacts: `/artifacts?includeContent=true`, decisions: '/decisions', audit: `/api/v1/audit/workflows/${id}`, metrics: `/api/v1/metrics/workflows/${id}`, summary: '/summary' }[t];
    const r = await api('GET', ep.startsWith('/api') ? ep : `/api/v1/workflows/${id}${ep}`);
    if (!r.ok) { pane.innerHTML = `<div class="alert warnn">${esc(errText(r))}</div>`; return; }
    pane.innerHTML = ({ artifacts: paneArtifacts, decisions: paneDecisions, audit: paneAudit, metrics: paneMetrics, summary: paneSummary }[t])(r.data);
  }
}
const paneArtifacts = (list) => '<table><thead><tr><th>Name</th><th>Version</th><th>Status</th><th>Task</th><th>SHA-256</th><th></th></tr></thead><tbody>' +
  list.map((a, i) => `<tr><td><b>${esc(a.name)}</b></td><td>${a.version}</td><td>${badge(a.status)}</td><td>${esc(a.taskKey)}</td><td class="mono">${esc((a.sha256 || '').slice(0, 12))}...</td><td><button class="btn small" data-act="viewart" data-i="${i}">View</button></td></tr><tr id="art${i}" style="display:none"><td colspan="6"><pre>${esc(a.content)}</pre></td></tr>`).join('') + '</tbody></table>';
const paneDecisions = (list) => '<table><thead><tr><th>#</th><th>Task (rev)</th><th>Decision</th><th>Builds on</th><th>By</th></tr></thead><tbody>' +
  list.map((d) => `<tr style="${d.superseded ? 'opacity:.55;text-decoration:line-through' : ''}"><td>${d.id}</td><td>${esc(d.taskKey)} (r${d.taskRevision})</td><td>${esc(d.title)}${d.superseded ? ` <span class="badge s-SUPERSEDED">superseded</span>` : ''}</td><td class="mono">${esc(d.parentDecisionIds.join(', '))}</td><td>${esc(d.decidedBy)}</td></tr>`).join('') + '</tbody></table>';
const paneAudit = (list) => '<table><thead><tr><th>Time</th><th>Event</th><th>Actor</th><th>Detail</th></tr></thead><tbody>' +
  list.map((e) => `<tr><td class="mono">${esc((e.occurredAt || '').replace('T', ' ').slice(11, 23))}</td><td><b>${esc(e.eventType)}</b></td><td>${esc(e.actor)}</td><td class="mono" style="max-width:520px">${esc(JSON.stringify(e.detail)).slice(0, 220)}</td></tr>`).join('') + '</tbody></table>';
const paneMetrics = (m) => `<div class="grid cols-4">${[['End-to-end', ms(m.endToEndLatencyMs)], ['Active agent time', ms(m.activeAgentTimeMs)], ['Agent executions', m.agentExecutions], ['Retry attempts', m.retryAttempts],
  ['Approval wait', ms(m.approvalWaitMs)], ['Approvals decided', m.approvalsDecided], ['Safe-stops', m.safeStops], ['Rollbacks', m.rollbacks]].map(([k, v]) => `<div class="stat"><b>${esc(v)}</b><span>${k}</span></div>`).join('')}</div>`;
const paneSummary = (s) => `<h3>${esc(s.summary.headline)}</h3>${s.summary.sections.map((x) => `<p><b>${esc(x.title)}.</b> ${esc(x.body)}</p>`).join('')}
  <div class="alert okk">Release readiness: <b>${esc(s.releaseReadiness.readiness)}</b>. ${esc((s.releaseReadiness.conditions || []).join(' | '))}</div>
  <p class="muted">${esc(s.summary.openItems.join(' - '))}</p>`;

async function wfAction(act, el) {
  const id = state.wfId; const base = `/api/v1/workflows/${id}`; let r;
  const comment = el && el.dataset.id ? ($('#wfComment' + el.dataset.id) || {}).value : undefined;
  if (act === 'execute') { el.disabled = true; el.textContent = 'Running...'; r = await api('POST', base + '/execute'); }
  else if (act === 'resume') r = await api('POST', base + '/execute', { acknowledgement: $('#wfAck').value });
  else if (act === 'stop') r = await api('POST', base + '/stop', { reason: $('#wfStopReason').value });
  else if (act === 'cancel') r = await api('POST', base + '/cancel', { reason: $('#wfStopReason').value });
  else if (act === 'approve') r = await api('POST', base + '/approve', { approvalId: Number(el.dataset.id), comment });
  else if (act === 'reject') r = await api('POST', base + '/reject', { approvalId: Number(el.dataset.id), comment });
  else if (act === 'replan') {
    const body = { changedTask: $('#rpTask').value, changeType: $('#rpType').value, reason: $('#rpReason').value };
    if (body.changeType === 'REQUIREMENT_CHANGE') body.newRequirement = $('#rpReq').value;
    r = await api('POST', base + '/replan', body);
    if (r.ok) { const d = r.data; await refreshWorkflow(); state.wfTab = 'replan'; renderWorkflow();
      $('#rpResult').innerHTML = `<div class="alert ${d.approvalRequired ? 'warnn' : 'okk'}">${d.applied ? 'Replan applied immediately.' : 'High-impact change: waiting for approval #' + d.approvalId + '.'}
        <br><b>Impacted (re-run):</b> ${esc(d.impactedTasks.join(', '))}<br><b>Preserved:</b> ${esc(d.preservedTasks.join(', ') || 'none')}<br><b>Status:</b> ${esc(d.workflowStatus)}</div>`; window.__actionDone = (window.__actionDone || 0) + 1; return; }
  }
  if (r && !r.ok) toast(`${act} -> HTTP ${r.status}: ${errText(r)}`, 'err');
  else if (r) toast(`${act} -> ${r.data && r.data.status ? r.data.status : 'ok'}`, 'ok');
  await refreshWorkflow();
  window.__actionDone = (window.__actionDone || 0) + 1;
}

/* ------------------------------------------------------------------ admin */
async function loadFaults() {
  const r = await api('GET', '/api/v1/admin/fault-injection');
  $('#fiRules').innerHTML = !r.ok ? `<div class="alert err">HTTP ${r.status}: ${esc(errText(r))}</div>` :
    r.data.length ? '<table><thead><tr><th>Operation</th><th>Mode</th><th>Remaining</th><th>Also fallback</th></tr></thead><tbody>' + r.data.map((x) => `<tr><td>${esc(x.operation)}</td><td>${esc(x.mode)}</td><td>${x.remaining}</td><td>${x.includeFallback}</td></tr>`).join('') + '</tbody></table>' : 'none';
}
async function addFault() {
  const body = { mode: $('#fiMode').value, times: Number($('#fiTimes').value), includeFallback: $('#fiFallback').value === 'true' };
  if ($('#fiOp').value) body.operation = $('#fiOp').value;
  const r = await api('POST', '/api/v1/admin/fault-injection', body);
  $('#fiMsg').innerHTML = r.ok ? '<div class="alert okk">Fault rule added.</div>' : `<div class="alert err">HTTP ${r.status}: ${esc(errText(r))}</div>`;
  loadFaults();
}
async function clearFaults() { const r = await api('DELETE', '/api/v1/admin/fault-injection'); $('#fiMsg').innerHTML = r.ok ? '<div class="alert okk">All fault rules cleared.</div>' : `<div class="alert err">HTTP ${r.status}: ${esc(errText(r))}</div>`; loadFaults(); }

/* ------------------------------------------------------------------ wiring */
document.addEventListener('click', (ev) => {
  const el = ev.target.closest('[data-act],[data-tab]'); if (!el) return;
  if (el.dataset.tab) return showTab(el.dataset.tab);
  const a = el.dataset.act;
  if (a === 'select') { state.wfId = el.dataset.id; state.wfTab = 'tasks'; refreshWorkflow(); }
  else if (a === 'subtab') { state.wfTab = el.dataset.t; $$('#wfSubtabs button').forEach((b) => b.classList.toggle('active', b.dataset.t === state.wfTab)); renderPane(); }
  else if (a === 'reload') refreshWorkflow();
  else if (a === 'viewout') { const row = $('#out-' + el.dataset.key); row.style.display = row.style.display === 'none' ? '' : 'none'; }
  else if (a === 'viewart') { const row = $('#art' + el.dataset.i); row.style.display = row.style.display === 'none' ? '' : 'none'; }
  else if (['execute', 'resume', 'stop', 'cancel', 'approve', 'reject', 'replan'].includes(a)) wfAction(a, el);
  else if (a === 'visit') visit(el.dataset.code);
  else if (a === 'analytics') showAnalytics(el.dataset.code);
  else if (a === 'deactivate') api('DELETE', '/api/v1/urls/' + el.dataset.code).then(loadUrls);
  else if (a === 'activate') api('PUT', `/api/v1/urls/${el.dataset.code}/status`, { active: true }).then((r) => { if (!r.ok) toast(errText(r), 'err'); loadUrls(); });
});
$('#btnLogin').onclick = () => login($('#loginUser').value, $('#loginPass').value);
$('#loginUser').onchange = () => { $('#loginPass').value = CREDS[$('#loginUser').value] || ''; };
$('#loginPass').onkeydown = (e) => { if (e.key === 'Enter') $('#btnLogin').click(); };
$('#btnDashRefresh').onclick = loadDashboard; $('#btnUrlCreate').onclick = createUrl; $('#btnUrlRefresh').onclick = loadUrls;
$('#btnWfCreate').onclick = createWorkflow; $('#btnWfRefresh').onclick = loadWorkflows; $('#btnFiAdd').onclick = addFault; $('#btnFiClear').onclick = clearFaults;
$('#wfPreset').onchange = () => { $('#wfRequirement').value = PRESETS[$('#wfPreset').value]; if (!$('#wfTitle').value) $('#wfTitle').placeholder = $('#wfPreset').selectedOptions[0].text; };
$('#wfRequirement').value = PRESETS.greenfield;
