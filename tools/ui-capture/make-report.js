'use strict';
/*
 * Builds the walkthrough document (HTML + PDF) from docs/ui-walkthrough/steps.json and the screenshots.
 * The PDF is printed by headless Edge (no extra tools). Includes an API coverage table computed against the live OpenAPI spec.
 * Usage: node tools/ui-capture/make-report.js [baseUrl]
 */
const fs = require('fs'), path = require('path');
const { launch, sleep } = require('./cdp.js');
const BASE = process.argv[2] || 'http://localhost:8080';
const ROOT = path.resolve(__dirname, '..', '..'); const DIR = path.join(ROOT, 'docs', 'ui-walkthrough');
const data = JSON.parse(fs.readFileSync(path.join(DIR, 'steps.json'), 'utf8'));
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

function surefire() {
  const dir = path.join(ROOT, 'target', 'surefire-reports'); let tests = 0, bad = 0;
  if (!fs.existsSync(dir)) return null;
  for (const f of fs.readdirSync(dir).filter((x) => x.endsWith('.txt'))) {
    const m = /Tests run: (\d+), Failures: (\d+), Errors: (\d+)/.exec(fs.readFileSync(path.join(dir, f), 'utf8')); if (m) { tests += +m[1]; bad += +m[2] + +m[3]; }
  }
  return { tests, bad };
}
const norm = (p) => p.split('?')[0].replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/g, '{id}');

(async () => {
  let spec = null;
  try { spec = await (await fetch(BASE + '/v3/api-docs')).json(); } catch { /* app not running: coverage table skipped */ }
  const calls = data.steps.flatMap((s) => s.calls);
  let coverage = [];
  if (spec) {
    for (const [p, ops] of Object.entries(spec.paths)) for (const method of Object.keys(ops)) {
      const re = new RegExp('^' + p.replace(/\{[^}]+\}/g, '[^/]+') + '$'); const m = method.toUpperCase();
      const hit = calls.filter((c) => c.method === m && re.test(norm(c.path).replace(/\{id\}/g, 'x')));
      coverage.push({ method: m, path: p, count: hit.length, statuses: [...new Set(hit.map((c) => c.status))].sort().join(', ') });
    }
    coverage.sort((a, b) => a.path.localeCompare(b.path) || a.method.localeCompare(b.method));
  }
  const covered = coverage.filter((c) => c.count > 0).length;
  const sf = surefire(); const ra = data.runAll.summary;
  const sections = [...new Set(data.steps.map((s) => s.section))];

  const stepHtml = data.steps.map((s) => {
    const rows = s.calls.slice(0, 16).map((c) => `<tr><td>${esc(c.user)}</td><td class="m ${c.method}">${c.method}</td><td class="mono">${esc(c.path)}</td><td class="st s${String(c.status)[0]}">${c.status}</td><td>${c.ms}</td></tr>`).join('');
    return `<section class="step"><div class="tag">${esc(s.section)}</div><h2>Step ${s.n}. ${esc(s.title)}</h2><p>${esc(s.note)}</p>
      <figure><img src="${esc(s.file)}" alt="${esc(s.title)}"></figure>
      ${s.calls.length ? `<table class="calls"><thead><tr><th>User</th><th>Method</th><th>API call</th><th>HTTP</th><th>ms</th></tr></thead><tbody>${rows}</tbody></table>${s.calls.length > 16 ? `<div class="more">... and ${s.calls.length - 16} more calls in this step</div>` : ''}` : ''}</section>`;
  }).join('\n');

  const html = `<!doctype html><html><head><meta charset="utf-8"><title>Agentic Engineering Platform - UI Walkthrough</title><style>
  @page { size: A4 landscape; margin: 11mm 12mm 14mm; }
  * { box-sizing: border-box; } body { font: 11.5px/1.45 "Segoe UI", Arial, sans-serif; color: #1b2437; margin: 0; }
  .cover { height: 175mm; display: flex; flex-direction: column; justify-content: center; page-break-after: always; }
  .cover h1 { font-size: 34px; margin: 0 0 6px; color: #3b5bdb; } .cover .sub { font-size: 16px; color: #66728a; margin-bottom: 26px; }
  .kv { display: grid; grid-template-columns: 210px 1fr; gap: 5px 14px; max-width: 760px; font-size: 13px; } .kv b { color: #66728a; font-weight: 600; }
  h2 { font-size: 17px; margin: 4px 0 2px; } h3 { font-size: 14px; margin: 14px 0 6px; }
  .tag { display: inline-block; background: #e7ecff; color: #3b5bdb; font-weight: 700; font-size: 10px; padding: 2px 9px; border-radius: 9px; text-transform: uppercase; letter-spacing: .04em; }
  .step { page-break-before: always; } .step p { margin: 2px 0 8px; color: #343c50; max-width: 255mm; }
  figure { margin: 0 0 8px; } figure img { width: 100%; border: 1px solid #d5dbea; border-radius: 6px; display: block; max-height: 142mm; object-fit: contain; object-position: top left; }
  table { width: 100%; border-collapse: collapse; font-size: 10px; } th, td { padding: 3px 7px; border-bottom: 1px solid #e1e6f0; text-align: left; } th { color: #66728a; font-size: 9px; text-transform: uppercase; }
  .mono { font-family: Consolas, monospace; } .m.GET { color: #1c7ed6; font-weight: 700; } .m.POST { color: #2f9e44; font-weight: 700; } .m.PUT { color: #e8890c; font-weight: 700; } .m.DELETE { color: #d6336c; font-weight: 700; }
  .st { font-weight: 700; } .s2 { color: #2f9e44; } .s4 { color: #e8890c; } .s5, .s0 { color: #d6336c; } .more { font-size: 10px; color: #66728a; padding: 3px 7px; }
  .toc { page-break-after: always; } .toc li { margin: 3px 0; } .ok { color: #2f9e44; font-weight: 700; } .no { color: #d6336c; font-weight: 700; }
  .ra td { padding: 2px 7px; font-size: 10px; } .ra .sec td { font-weight: 700; color: #3b5bdb; padding-top: 8px; } .note { color: #66728a; font-size: 10.5px; }
  </style></head><body>
  <div class="cover"><h1>Agentic Software Engineering System</h1><div class="sub">URL Shortener + DAG-orchestrated agent platform: web console walkthrough and API execution report</div>
    <div class="kv"><b>Generated</b><span>${esc(data.generatedAt.replace('T', ' ').slice(0, 19))} UTC</span>
    <b>Application</b><span>Spring Boot 3.4.1, Java 21 target, H2 (file) database, HTTP Basic security</span>
    <b>LLM</b><span>MockLLMProvider (deterministic simulation, no external API)</span>
    <b>Screens captured</b><span>${data.steps.length} screenshots, one per step, driven through the real web console</span>
    <b>API calls in this report</b><span>${calls.length} calls made by the browser</span>
    <b>API endpoints covered</b><span>${spec ? `${covered} of ${coverage.length} endpoints in the OpenAPI specification` : 'n/a'}</span>
    <b>Run-all-flows result</b><span>${ra ? `<span class="${ra.fail ? 'no' : 'ok'}">${ra.pass} passed, ${ra.fail} failed</span> (${ra.done} checks)` : 'n/a'}</span>
    <b>Automated test suite</b><span>${sf ? `<span class="${sf.bad ? 'no' : 'ok'}">${sf.tests} tests, ${sf.bad} failures</span> (mvn verify, last run in the project)` : 'n/a'}</span></div>
    <p class="note" style="margin-top:26px">All content generated by the platform's agents is a deterministic simulation produced by the mock LLM; it is not real model output.</p></div>
  <div class="toc"><h2>Contents</h2><ol>${sections.map((sec) => { const st = data.steps.filter((s) => s.section === sec); return `<li><b>${esc(sec)}</b> <span class="note">(steps ${st[0].n}-${st[st.length - 1].n})</span></li>`; }).join('')}
    <li><b>Appendix A</b> - Complete list of checks executed by "Run all flows"</li><li><b>Appendix B</b> - API coverage against the OpenAPI specification</li></ol>
    <h3>How the flows were executed</h3><p>A headless browser drove the console exactly as a user would: signing in as <i>engineer</i>, <i>approver</i> and <i>admin</i>, creating URLs and workflows,
    executing them, approving or rejecting at the human gates, injecting faults, replanning and reading metrics. Each step below shows the screen and the REST calls the page made
    (user, method, path, HTTP status, latency). The final steps run every API flow from the browser in one go.</p></div>
  ${stepHtml}
  <section class="step"><div class="tag">Appendix A</div><h2>Checks executed by "Run all flows"</h2><p class="note">${ra ? `${ra.pass} passed, ${ra.fail} failed, ${ra.done} checks.` : ''}</p>
    <table class="ra"><tbody>${(data.runAll.rows || []).map((r) => r.sec ? `<tr class="sec"><td colspan="2">${esc(r.text)}</td></tr>` : `<tr><td class="${r.pass ? 'ok' : r.fail ? 'no' : ''}" style="width:46px">${r.pass ? 'PASS' : r.fail ? 'FAIL' : ''}</td><td>${esc(r.text.replace(/^(PASS|FAIL)\s*/, ''))}</td></tr>`).join('')}</tbody></table></section>
  <section class="step"><div class="tag">Appendix B</div><h2>API coverage (${spec ? `${covered}/${coverage.length}` : 'n/a'} endpoints)</h2><p class="note">Endpoints are read from the application's own OpenAPI document and matched against the calls the browser made.</p>
    <table><thead><tr><th>Method</th><th>Endpoint</th><th>Calls</th><th>HTTP statuses seen</th><th>Covered</th></tr></thead><tbody>${coverage.map((c) => `<tr><td class="m ${c.method}">${c.method}</td><td class="mono">${esc(c.path)}</td><td>${c.count}</td><td>${esc(c.statuses)}</td><td class="${c.count ? 'ok' : 'no'}">${c.count ? 'yes' : 'NO'}</td></tr>`).join('')}</tbody></table></section>
  </body></html>`;
  fs.writeFileSync(path.join(DIR, 'report.html'), html);
  console.log(`report.html written; API coverage ${covered}/${coverage.length}`);
  coverage.filter((c) => !c.count).forEach((c) => console.log('  not covered:', c.method, c.path));

  const b = await launch({ port: 9335, width: 1200, height: 800 });
  try {
    const page = await b.firstPage(); await page.send('Page.enable');
    await page.goto('file:///' + path.join(DIR, 'report.html').replace(/\\/g, '/'));
    await page.waitFor('[...document.images].every((i) => i.complete)', 60000); await sleep(500);
    const pdf = await page.send('Page.printToPDF', { printBackground: true, preferCSSPageSize: true, displayHeaderFooter: true, headerTemplate: '<span></span>',
      footerTemplate: '<div style="font-size:8px;width:100%;text-align:center;color:#66728a">Agentic Engineering Platform - UI walkthrough - page <span class="pageNumber"></span> of <span class="totalPages"></span></div>' });
    const out = path.join(DIR, 'Agentic-Platform-UI-Walkthrough.pdf'); fs.writeFileSync(out, Buffer.from(pdf.data, 'base64'));
    console.log(`PDF written: ${out} (${(fs.statSync(out).size / 1048576).toFixed(1)} MB)`);
  } finally { b.close(); }
  process.exit(0);
})().catch((e) => { console.error('REPORT FAILED:', e); process.exit(1); });
