// Minimal Postman-compatible runner (no npm packages): executes every request of the collection in order, runs its
// pre-request and test scripts against a tiny `pm` shim, and prints PASS/FAIL. Usage:  node postman/verify-collection.js [baseUrl]
const fs = require('fs'), path = require('path');
const col = JSON.parse(fs.readFileSync(path.join(__dirname, 'Agentic-Platform.postman_collection.json'), 'utf8'));
const vars = {}; col.variable.forEach(v => { vars[v.key] = v.value; });
if (process.argv[2]) vars.baseUrl = process.argv[2];
const sub = s => (typeof s === 'string' ? s.replace(/\{\{(\w+)\}\}/g, (m, k) => (vars[k] !== undefined ? vars[k] : m)) : s);
let pass = 0, fail = 0; const failures = [];

function expect(v) {
  const o = {};
  o.to = {
    be: { get true() { if (v !== true) throw new Error('expected true but got ' + JSON.stringify(v)); return o; } },
    eql: e => { if (JSON.stringify(v) !== JSON.stringify(e)) throw new Error('expected ' + JSON.stringify(e) + ' but got ' + JSON.stringify(v)); },
    include: s => { if (!String(v).includes(s)) throw new Error('expected to include ' + s); },
    not: { include: s => { if (String(v).includes(s)) throw new Error('expected NOT to include ' + s); } },
  };
  return o;
}
function makePm(label, headers, res, text) {
  const cv = { set: (k, v) => { vars[k] = String(v); }, get: k => vars[k] };
  return {
    collectionVariables: cv, environment: cv, variables: cv, expect,
    request: { headers: { add: o => { headers[o.key] = o.value; } } },
    response: res && {
      code: res.status, text: () => text, json: () => JSON.parse(text), headers: { get: k => res.headers.get(k) },
      to: { have: { status: n => { if (res.status !== n) throw new Error('expected HTTP ' + n + ' but got ' + res.status + ' ' + text.slice(0, 160)); } } },
    },
    test: (name, fn) => {
      try { fn(); pass++; console.log('    PASS  ' + name); }
      catch (e) { fail++; failures.push(label + ' :: ' + name + ' :: ' + e.message); console.log('    FAIL  ' + name + '  -> ' + e.message); }
    },
  };
}
const script = (code, pm) => new Function('pm', code)(pm);

async function runItem(item, folder) {
  const label = folder + ' / ' + item.name;
  const ev = {}; (item.event || []).forEach(e => { ev[e.listen] = e.script.exec.join('\n'); });
  const headers = {}; (item.request.header || []).forEach(h => { headers[h.key] = h.value; });
  if (ev.prerequest) script(ev.prerequest, makePm(label, headers, null, ''));
  const a = item.request.auth;
  if (a && a.type === 'basic') {
    const u = sub(a.basic.find(b => b.key === 'username').value), p = sub(a.basic.find(b => b.key === 'password').value);
    headers.Authorization = 'Basic ' + Buffer.from(u + ':' + p).toString('base64');
  }
  const url = sub(item.request.url.raw);
  const manual = item.protocolProfileBehavior && item.protocolProfileBehavior.followRedirects === false;
  let res, text = '';
  try {
    res = await fetch(url, { method: item.request.method, headers: Object.fromEntries(Object.entries(headers).map(([k, v]) => [k, sub(v)])),
      body: item.request.body ? sub(item.request.body.raw) : undefined, redirect: manual ? 'manual' : 'follow' });
    text = await res.text();
  } catch (e) { fail++; failures.push(label + ' :: request failed :: ' + e.message); console.log('  ' + item.name + '\n    FAIL  request failed -> ' + e.message); return; }
  console.log('  ' + item.name + '  [' + res.status + ']');
  if (ev.test) {
    try { script(ev.test, makePm(label, headers, res, text)); }
    catch (e) { fail++; failures.push(label + ' :: script error :: ' + e.message); console.log('    FAIL  script error -> ' + e.message); }
  }
}
(async () => {
  for (const folder of col.item) {
    console.log('\n== ' + folder.name);
    for (const item of folder.item) await runItem(item, folder.name);
  }
  console.log('\n===== RESULT: ' + pass + ' assertions passed, ' + fail + ' failed =====');
  failures.forEach(f => console.log('  FAILED: ' + f));
  process.exit(fail ? 1 : 0);
})();