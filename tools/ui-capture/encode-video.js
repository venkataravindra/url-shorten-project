'use strict';
/*
 * Turns the recorded screencast frames into a WebM video without ffmpeg: a headless browser replays the frames onto a canvas
 * with their original timing (long idle gaps are shortened) while MediaRecorder encodes the canvas stream.
 * Usage: node tools/ui-capture/encode-video.js   (reads docs/ui-walkthrough/frames.json + frames/)
 */
const http = require('http'), fs = require('fs'), path = require('path');
const { launch, sleep } = require('./cdp.js');
const DIR = path.resolve(__dirname, '..', '..', 'docs', 'ui-walkthrough');
const OUT = path.join(DIR, 'flow-recording.webm');
const MIN_HOLD = 70, MAX_HOLD = 650, LAST_HOLD = 2500;

const frames = JSON.parse(fs.readFileSync(path.join(DIR, 'frames.json'), 'utf8'));
if (!frames.length) { console.error('no frames recorded'); process.exit(1); }
const manifest = frames.map((f, i) => {
  const next = frames[i + 1]; const gap = next ? (next.ts - f.ts) * 1000 : LAST_HOLD;
  return { url: '/' + f.file, hold: Math.round(Math.min(MAX_HOLD, Math.max(MIN_HOLD, gap))) };
});
const totalMs = manifest.reduce((a, f) => a + f.hold, 0);
console.log(`frames: ${frames.length}, video length about ${(totalMs / 1000).toFixed(0)} s (real-time encode)`);

const PAGE = `<!doctype html><meta charset="utf-8"><body style="margin:0;background:#000"><canvas id="c" width="1366" height="850"></canvas><script>
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const manifest = await (await fetch('/manifest.json')).json();
  const canvas = document.getElementById('c'); const ctx = canvas.getContext('2d');
  const mime = ['video/webm;codecs=vp9', 'video/webm;codecs=vp8', 'video/webm'].find((m) => MediaRecorder.isTypeSupported(m));
  const rec = new MediaRecorder(canvas.captureStream(30), { mimeType: mime, videoBitsPerSecond: 3000000 });
  const chunks = []; rec.ondataavailable = (e) => { if (e.data.size) chunks.push(e.data); };
  const stopped = new Promise((r) => (rec.onstop = r));
  const load = async (i) => createImageBitmap(await (await fetch(manifest[i].url)).blob());
  let img = await load(0); ctx.drawImage(img, 0, 0); rec.start(250);
  for (let i = 0; i < manifest.length; i++) {
    ctx.drawImage(img, 0, 0);
    const nextP = i + 1 < manifest.length ? load(i + 1) : null;
    await sleep(manifest[i].hold);
    if (nextP) img = await nextP;
    window.__progress = i + 1;
  }
  await sleep(300); rec.stop(); await stopped;
  const blob = new Blob(chunks, { type: mime });
  await fetch('/upload', { method: 'POST', body: blob });
  window.__done = blob.size;
})().catch((e) => { window.__error = String(e); });
</script>`;

const server = http.createServer((req, res) => {
  if (req.method === 'POST' && req.url === '/upload') {
    const out = fs.createWriteStream(OUT); req.pipe(out); out.on('finish', () => { res.end('ok'); server.uploaded = true; }); return;
  }
  if (req.url === '/') { res.setHeader('Content-Type', 'text/html'); return res.end(PAGE); }
  if (req.url === '/manifest.json') { res.setHeader('Content-Type', 'application/json'); return res.end(JSON.stringify(manifest)); }
  const file = path.join(DIR, decodeURIComponent(req.url.slice(1)));
  if (file.startsWith(DIR) && fs.existsSync(file)) { res.setHeader('Content-Type', 'image/jpeg'); return fs.createReadStream(file).pipe(res); }
  res.statusCode = 404; res.end();
});

server.listen(9444, async () => {
  const b = await launch({ port: 9334, width: 1366, height: 850 });
  try {
    const page = await b.firstPage(); await page.send('Page.enable'); await page.send('Runtime.enable');
    await page.send('Emulation.setFocusEmulationEnabled', { enabled: true }); await page.send('Page.bringToFront');
    await page.send('Page.navigate', { url: 'http://127.0.0.1:9444/' });
    const t0 = Date.now(); let last = -1;
    while (!server.uploaded) {
      await sleep(2000);
      const err = await page.eval('window.__error || ""').catch(() => ''); if (err) throw new Error(err);
      const p = await page.eval('window.__progress || 0').catch(() => 0);
      if (p !== last && p % 40 === 0) { console.log(`  encoded ${p}/${manifest.length}`); last = p; }
      if (Date.now() - t0 > totalMs + 120000) throw new Error('encode timeout');
    }
    await sleep(500);
    console.log(`video written: ${OUT} (${(fs.statSync(OUT).size / 1048576).toFixed(1)} MB)`);
  } catch (e) { console.error('ENCODE FAILED:', e.message); process.exitCode = 1; }
  b.close(); server.close(); setTimeout(() => process.exit(), 300);
});
