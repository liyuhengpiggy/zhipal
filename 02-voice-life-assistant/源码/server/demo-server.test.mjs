import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import { createDemoServer } from './demo-server.mjs';

const record = () => ({ v: 1, record_id: crypto.randomUUID(), event_id: 'boot1:1', device_id: 'elder-demo-001',
  source: 'ble', type: 'action_result', text: '打开抖音', result: 'intent_requested', occurred_at: new Date().toISOString(), data: {} });

async function fixture(t, token = '') {
  const folder = mkdtempSync(join(tmpdir(), 'senior-demo-'));
  const dataFile = join(folder, 'events.jsonl');
  const server = createDemoServer({ dataFile, token });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(async () => { await new Promise(resolve => server.close(resolve)); rmSync(folder, { recursive: true, force: true }); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const post = (value, auth = token) => fetch(base + '/v1/events', { method: 'POST', headers: {
    'Content-Type': 'application/json', ...(auth ? { Authorization: `Bearer ${auth}` } : {})
  }, body: JSON.stringify(value) });
  return { server, dataFile, base, post };
}

test('phone record persists and repeated offline retry is idempotent', async t => {
  const f = await fixture(t);
  const event = record();
  const first = await f.post(event);
  assert.equal(first.status, 201);
  assert.deepEqual(await first.json(), { ok: true, record_id: event.record_id });
  const retry = await f.post(event);
  assert.equal(retry.status, 200);
  assert.equal((await retry.json()).duplicate, true);
  assert.equal(readFileSync(f.dataFile, 'utf8').trim().split('\n').length, 1);
  const list = await (await fetch(f.base + '/v1/events?device_id=elder-demo-001')).json();
  assert.deepEqual(list.events, [event]);
});

test('reusing record_id for a different operation is rejected', async t => {
  const f = await fixture(t);
  const event = record();
  await f.post(event);
  assert.equal((await f.post({ ...event, text: '给女儿打电话' })).status, 409);
});

test('backend token, malformed JSON, and required fields are checked', async t => {
  const f = await fixture(t, 'test-only-token');
  assert.equal((await f.post(record(), 'wrong-token')).status, 401);
  assert.equal((await f.post({ type: 'command' })).status, 422);
  const badJson = await fetch(f.base + '/v1/events', { method: 'POST', headers: {
    'Content-Type': 'application/json', Authorization: 'Bearer test-only-token'
  }, body: '{broken' });
  assert.equal(badJson.status, 400);
  assert.equal((await f.post(record())).status, 201);
});

test('records and deduplication survive backend restart', async t => {
  const f = await fixture(t);
  const event = record();
  await f.post(event);
  const restored = createDemoServer({ dataFile: f.dataFile });
  restored.listen(0, '127.0.0.1');
  await once(restored, 'listening');
  t.after(() => new Promise(resolve => restored.close(resolve)));
  const response = await fetch(`http://127.0.0.1:${restored.address().port}/v1/events`, { method: 'POST',
    headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(event) });
  assert.equal((await response.json()).duplicate, true);
});
