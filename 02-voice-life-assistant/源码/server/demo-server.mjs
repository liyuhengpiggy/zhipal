import http from 'node:http';
import { appendFileSync, existsSync, mkdirSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';

// A local integration server, not the final AI/database service. No npm dependencies.
export function createDemoServer({ dataFile, token = '' }) {
  mkdirSync(dirname(dataFile), { recursive: true });
  const records = new Map();
  if (existsSync(dataFile)) {
    for (const line of readFileSync(dataFile, 'utf8').split('\n').filter(Boolean)) {
      const record = JSON.parse(line);
      records.set(record.record_id, record);
    }
  }
  const send = (res, status, value) => {
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(value));
  };
  return http.createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname === '/health' && req.method === 'GET') return send(res, 200, { ok: true });
    if (token && req.headers.authorization !== `Bearer ${token}`) return send(res, 401, { ok: false, error: 'Unauthorized' });
    if (url.pathname === '/' && req.method === 'GET') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end('<!doctype html><meta name="viewport" content="width=device-width"><title>联调记录</title><style>body{font:16px system-ui;margin:24px;background:#f5f7f7;color:#183b3a}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:white;padding:16px;border-radius:12px}</style><h1>手机上报联调记录</h1><p>这是本地联调页面，正式子女端由后端同学实现。每 3 秒刷新。</p><pre id="out">正在读取...</pre><script>async function refresh(){try{const r=await fetch("/v1/events");document.getElementById("out").textContent=JSON.stringify(await r.json(),null,2)}catch(e){document.getElementById("out").textContent=String(e)}}refresh();setInterval(refresh,3000)</script>');
      return;
    }
    if (url.pathname === '/v1/events' && req.method === 'GET') {
      const deviceId = url.searchParams.get('device_id');
      return send(res, 200, { ok: true, events: [...records.values()].filter(r => !deviceId || r.device_id === deviceId).slice(-50).reverse() });
    }
    if (url.pathname !== '/v1/events' || req.method !== 'POST') return send(res, 404, { ok: false, error: 'Not found' });
    if (!req.headers['content-type']?.toLowerCase().startsWith('application/json')) return send(res, 415, { ok: false, error: 'Use application/json' });
    const chunks = [];
    let size = 0;
    let tooLarge = false;
    req.on('data', chunk => {
      size += chunk.length;
      if (size > 65536) { tooLarge = true; chunks.length = 0; }
      if (!tooLarge) chunks.push(chunk);
    });
    req.on('end', () => {
      if (tooLarge) return send(res, 413, { ok: false, error: 'Event must be at most 64KB; upload media separately' });
      let event;
      try { event = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
      catch { return send(res, 400, { ok: false, error: 'Invalid JSON' }); }
      if (!event || event.v !== 1 || typeof event.record_id !== 'string' || !/^[A-Za-z0-9._:-]{1,96}$/.test(event.record_id) ||
          typeof event.event_id !== 'string' || !event.event_id || typeof event.device_id !== 'string' || !event.device_id ||
          typeof event.type !== 'string' || !event.type || typeof event.source !== 'string' || !event.source ||
          typeof event.text !== 'string' || typeof event.result !== 'string' ||
          typeof event.occurred_at !== 'string' || Number.isNaN(Date.parse(event.occurred_at))) {
        return send(res, 422, { ok: false, error: 'Missing or invalid fields; see docs/接口约定.md' });
      }
      const previous = records.get(event.record_id);
      if (previous) {
        if (JSON.stringify(previous) !== JSON.stringify(event)) return send(res, 409, { ok: false, error: 'record_id reused with different content' });
        return send(res, 200, { ok: true, record_id: event.record_id, duplicate: true });
      }
      try {
        appendFileSync(dataFile, JSON.stringify(event) + '\n', 'utf8');
        records.set(event.record_id, event);
        return send(res, 201, { ok: true, record_id: event.record_id });
      } catch {
        return send(res, 500, { ok: false, error: 'Could not save record' });
      }
    });
    req.on('error', () => { if (!res.headersSent) send(res, 400, { ok: false, error: 'Incomplete request' }); });
  });
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  const port = Number(process.env.DEMO_PORT || 8787);
  const dataFile = fileURLToPath(new URL('./data/events.jsonl', import.meta.url));
  const server = createDemoServer({ dataFile, token: process.env.DEMO_API_TOKEN || '' });
  server.listen(port, '0.0.0.0', () => {
    console.log(`本机查看记录：http://localhost:${port}`);
    console.log(`手机填写接口：http://电脑的局域网IPv4地址:${port}/v1/events`);
    console.log('手机和电脑需连接同一 Wi-Fi/热点；按 Ctrl+C 停止。');
  });
  server.on('error', error => { console.error(error.message); process.exitCode = 1; });
}
