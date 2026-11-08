'use strict';
// Minimal Chrome DevTools Protocol driver (no npm packages): launches headless Edge/Chrome and exposes a small Page API.
const { spawn } = require('child_process');
const fs = require('fs'), os = require('os'), path = require('path');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const BROWSER = ['C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe', 'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'].find(fs.existsSync);

class Page {
  constructor(ws) {
    this.ws = ws; this.id = 0; this.pending = new Map(); this.handlers = {};
    ws.addEventListener('message', (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id) { const p = this.pending.get(m.id); if (p) { this.pending.delete(m.id); m.error ? p.reject(new Error(m.error.message)) : p.resolve(m.result); } }
      else if (m.method && this.handlers[m.method]) this.handlers[m.method].forEach((h) => h(m.params));
    });
  }
  static async connect(wsUrl) {
    const ws = new WebSocket(wsUrl);
    await new Promise((res, rej) => { ws.addEventListener('open', res); ws.addEventListener('error', rej); });
    return new Page(ws);
  }
  send(method, params = {}) { const id = ++this.id; this.ws.send(JSON.stringify({ id, method, params })); return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject })); }
  on(ev, h) { (this.handlers[ev] = this.handlers[ev] || []).push(h); }
  async eval(expr) {
    const r = await this.send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
    if (r.exceptionDetails) throw new Error('eval failed: ' + ((r.exceptionDetails.exception && r.exceptionDetails.exception.description) || r.exceptionDetails.text));
    return r.result.value;
  }
  async goto(url) { await this.send('Page.navigate', { url }); await sleep(400); await this.waitFor('document.readyState === "complete"', 20000); }
  async waitFor(expr, timeout = 20000, step = 120) {
    const t0 = Date.now(); let last;
    for (;;) {
      try { if (await this.eval(expr)) return true; } catch (e) { last = e; }
      if (Date.now() - t0 > timeout) throw new Error('timeout waiting for: ' + expr + (last ? ' (' + last.message + ')' : ''));
      await sleep(step);
    }
  }
  async screenshot(file, { full = false } = {}) {
    let params = { format: 'png' };
    if (full) { const m = await this.send('Page.getLayoutMetrics'); const cs = m.cssContentSize || m.contentSize;
      params = { format: 'png', captureBeyondViewport: true, clip: { x: 0, y: 0, width: cs.width, height: cs.height, scale: 1 } }; }
    const r = await this.send('Page.captureScreenshot', params);
    fs.writeFileSync(file, Buffer.from(r.data, 'base64'));
  }
  close() { try { this.ws.close(); } catch { /* ignore */ } }
}

async function launch({ port = 9333, width = 1366, height = 850, headless = true } = {}) {
  if (!BROWSER) throw new Error('Edge or Chrome not found');
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'ui-capture-'));
  const args = [`--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, `--window-size=${width},${height}`, '--no-first-run', '--no-default-browser-check',
    '--disable-gpu', '--hide-scrollbars', '--mute-audio', '--disable-extensions', '--disable-background-timer-throttling', '--disable-renderer-backgrounding',
    '--disable-backgrounding-occluded-windows', 'about:blank'];
  if (headless) args.unshift('--headless=new');
  const child = spawn(BROWSER, args, { stdio: 'ignore' });
  for (let i = 0; i < 60; i++) { try { await fetch(`http://127.0.0.1:${port}/json/version`); break; } catch { await sleep(300); } }
  const open = async (url = 'about:blank') => {
    const t = await (await fetch(`http://127.0.0.1:${port}/json/new?${url}`, { method: 'PUT' })).json();
    return Page.connect(t.webSocketDebuggerUrl);
  };
  const first = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  const pageTarget = first.find((t) => t.type === 'page');
  return { port, child, profile, open, firstPage: () => Page.connect(pageTarget.webSocketDebuggerUrl), close: () => { try { child.kill(); } catch { /* ignore */ } } };
}
module.exports = { launch, Page, sleep, BROWSER };
