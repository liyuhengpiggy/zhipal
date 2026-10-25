import http from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import {
  COMMAND_BODY_LIMIT,
  COMMAND_PATH,
  COMMAND_TIMEOUT_MS,
  buildCommandPayload,
  parseCommandProviderResponse,
  preflightCommand,
  validateCommandRequest
} from './command.mjs';

export const MODEL = 'Qwen/Qwen3-VL-8B-Instruct';
const UPSTREAM = 'https://api.siliconflow.cn/v1/chat/completions';
const LIMIT = 12 * 1024 * 1024;
const MAX_REPLY = 262144;
const fail = (status) => Object.assign(new Error('request_rejected'), { status });

export function validate(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw fail(400);
  if (Object.keys(input).some(k => !['model', 'messages', 'max_tokens', 'stream'].includes(k))) throw fail(400);
  if (input.model !== MODEL || input.stream !== false || input.max_tokens !== 4096) throw fail(400);
  if (!Array.isArray(input.messages) || input.messages.length < 1 || input.messages.length > 2) throw fail(400);
  let count = 0;
  input.messages.forEach((m, i) => {
    if (!m || Object.keys(m).some(k => !['role', 'content'].includes(k))) throw fail(400);
    if (m.role === 'system' && i === 0 && input.messages.length === 2 && typeof m.content === 'string' && m.content.length <= 50000) return;
    if (m.role !== 'user' || i !== input.messages.length - 1 || !Array.isArray(m.content)) throw fail(400);
    let texts = 0;
    for (const p of m.content) {
      if (p?.type === 'text' && Object.keys(p).sort().join() === 'text,type' && typeof p.text === 'string' && p.text.trim() && p.text.length <= 100000) { texts++; continue; }
      if (p?.type !== 'image_url' || Object.keys(p).sort().join() !== 'image_url,type' || !p.image_url || Object.keys(p.image_url).join() !== 'url') throw fail(400);
      const u = p.image_url.url;
      if (typeof u !== 'string' || !/^data:image\/jpeg;base64,[A-Za-z0-9+/]+={0,2}$/.test(u)) throw fail(400);
      const encoded = u.slice('data:image/jpeg;base64,'.length);
      const bytes = Buffer.from(encoded, 'base64');
      if (bytes.length < 4 || bytes.length > 4 * 1024 * 1024 || bytes.toString('base64') !== encoded || bytes[0] !== 255 || bytes[1] !== 216 || bytes.at(-2) !== 255 || bytes.at(-1) !== 217) throw fail(400);
      count++;
    }
    if (texts !== 1) throw fail(400);
  });
  if (count < 1 || count > 2) throw fail(400);
  return input;
}

function authorized(header, token) {
  const a = Buffer.from(header || '');
  const b = Buffer.from(`Bearer ${token}`);
  return a.length === b.length && timingSafeEqual(a, b);
}
async function boundedBody(stream, max) {
  const chunks = []; let length = 0;
  for await (const chunk of stream) {
    const bytes = Buffer.from(chunk); length += bytes.length;
    if (length > max) throw fail(413);
    chunks.push(bytes);
  }
  return Buffer.concat(chunks).toString('utf8');
}

export function createServer({ providerKey, proxyToken, transport = fetch }) {
  if (!providerKey?.trim() || /[\r\n]/.test(providerKey) || !proxyToken || proxyToken.length < 32 || /[\r\n]/.test(proxyToken) || providerKey === proxyToken) throw new Error('Configure distinct SILICONFLOW_API_KEY and B_PROXY_TOKEN (32+ characters)');
  let active = 0;
  const server = http.createServer(async (req, res) => {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('Cache-Control', 'no-store');
    const send = (status, value) => { res.writeHead(status); res.end(JSON.stringify(value)); };
    if (req.method === 'GET' && req.url === '/healthz') {
      send(200, { status: 'ok', model: MODEL, capabilities: { b_agent_chat: true, a_command_interpreter: { version: 1, path: COMMAND_PATH } } }); return;
    }
    const isBChat = req.method === 'POST' && req.url === '/v1/chat/completions';
    const isACommand = req.method === 'POST' && req.url === COMMAND_PATH;
    if (!isBChat && !isACommand) { send(404, { error: 'not_found' }); return; }
    if (!authorized(req.headers.authorization, proxyToken)) { send(401, { error: 'unauthorized' }); return; }
    if (!String(req.headers['content-type']).startsWith('application/json')) { send(415, { error: 'json_required' }); return; }
    if (active >= 4) { send(429, { error: 'busy' }); return; }
    active++;
    const abort = new AbortController();
    const timer = setTimeout(() => abort.abort(), isACommand ? COMMAND_TIMEOUT_MS : 110000);
    res.on('close', () => abort.abort());
    try {
      const bodyLimit = isACommand ? COMMAND_BODY_LIMIT : LIMIT;
      if (Number(req.headers['content-length']) > bodyLimit) throw fail(413);
      let input;
      try { input = JSON.parse(await boundedBody(req, bodyLimit)); } catch (e) { throw fail(e.status || 400); }
      let commandInput = null;
      let payload;
      if (isACommand) {
        commandInput = validateCommandRequest(input);
        const earlyIntent = preflightCommand(commandInput);
        if (earlyIntent) { send(200, { version: 1, task_id: commandInput.task_id, intent: earlyIntent }); return; }
        payload = buildCommandPayload(commandInput);
      } else {
        payload = validate(input);
      }
      const upstream = await transport(UPSTREAM, {
        method: 'POST', redirect: 'error', signal: abort.signal,
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${providerKey}` },
        body: JSON.stringify(payload)
      });
      if (!upstream.ok) {
        await upstream.body?.cancel();
        send(502, { error: 'provider_error', provider_status: upstream.status }); return;
      }
      const body = await boundedBody(upstream.body, MAX_REPLY);
      let result;
      try { result = JSON.parse(body); } catch { throw fail(502); }
      if (isACommand) {
        const intent = parseCommandProviderResponse(result, commandInput);
        send(200, { version: 1, task_id: commandInput.task_id, intent });
      } else {
        // App validates finish_reason, sections and action. Proxy cannot execute any phone action.
        if (!Array.isArray(result.choices) || result.choices.length !== 1) throw fail(502);
        send(200, result);
      }
    } catch (e) {
      if (!res.destroyed && !res.headersSent) send(e.status || 502, { error: 'request_failed' });
    } finally { clearTimeout(timer); active--; }
  });
  server.requestTimeout = 30000;
  server.headersTimeout = 15000;
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const server = createServer({ providerKey: process.env.SILICONFLOW_API_KEY, proxyToken: process.env.B_PROXY_TOKEN });
  server.listen(Number(process.env.PORT || 10000), '0.0.0.0', () => console.log('A/B proxy listening; request bodies and credentials are not logged'));
}
