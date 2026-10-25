import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createServer, MODEL } from './server.mjs';

const providerKey = 'test-provider-secret-not-for-apk';
const proxyToken = 'test-proxy-access-token-01234567890123456789';
// Optional local real JPEG fixture; it is never sent outside this process/local HTTP server.
const jpeg = process.env.B_TEST_JPEG ? readFileSync(process.env.B_TEST_JPEG) : Buffer.from([255,216,255,217]);
const payload = (count = 1) => ({model: MODEL, stream: false, max_tokens: 4096, messages: [{role: 'user', content: [
  ...Array.from({length: count}, () => ({type:'image_url', image_url:{url:`data:image/jpeg;base64,${jpeg.toString('base64')}`}})),
  {type:'text',text:'Analyze this screenshot; never bypass confirmation.'}
]}]});

async function fixture(fn, transport) {
  const server = createServer({providerKey, proxyToken, transport});
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}/v1/chat/completions`;
  try { await fn(async (body, token = proxyToken) => fetch(url, {method:'POST', headers:{'Content-Type':'application/json', Authorization:`Bearer ${token}`}, body:JSON.stringify(body)})); }
  finally { await new Promise(resolve => server.close(resolve)); }
}
test('one/two JPEG frames round trip, provider authorization injected only at fixed endpoint', async () => {
  let called = 0;
  await fixture(async post => {
    for (const count of [1,2]) {
      const r = await post(payload(count)); assert.equal(r.status,200);
      assert.equal((await r.json()).choices[0].message.content, 'fixture response');
    }
  }, async (url, options) => {
    called++;
    assert.equal(url,'https://api.siliconflow.cn/v1/chat/completions');
    assert.equal(options.headers.Authorization,`Bearer ${providerKey}`);
    assert.equal(options.redirect,'error');
    const p = JSON.parse(options.body);
    const frames = p.messages[0].content.filter(x => x.type === 'image_url');
    assert.equal(frames.length, called);
    for (const f of frames) assert.deepEqual(Buffer.from(f.image_url.url.split(',')[1],'base64'),jpeg);
    return Response.json({choices:[{finish_reason:'stop',message:{role:'assistant',content:'fixture response'}}]});
  });
  assert.equal(called,2);
});
test('reject missing/wrong token, model overrides, tools, streaming and invalid/external images before forwarding', async () => {
  await fixture(async post => {
    assert.equal((await post(payload(),'wrong')).status,401);
    assert.equal((await post(payload(),providerKey)).status,401);
    for (const p of [ {...payload(),model:'other'}, {...payload(),tools:[]}, {...payload(),stream:true}, payload(0), payload(3)]) assert.equal((await post(p)).status,400);
    for (const url of ['https://example.com/private.jpg', 'data:image/jpeg;base64,AAAA', 'data:image/jpeg;base64,%%%']) {
      const p=payload();p.messages[0].content[0].image_url.url=url;
      assert.equal((await post(p)).status,400);
    }
  }, () => { throw new Error('must not call upstream'); });
});
test('provider errors never expose provider key or error body', async () => {
  await fixture(async post => {
    const r=await post(payload());assert.equal(r.status,502);
    const body=await r.text();assert.ok(!body.includes(providerKey));assert.ok(!body.includes('private upstream detail'));
  }, async () => new Response(`${providerKey}: private upstream detail`,{status:401}));
});
test('oversized request rejected before forwarding', async () => {
  await fixture(async post => { assert.equal((await post({...payload(),padding:'x'.repeat(12*1024*1024)})).status,413); }, () => {throw new Error('must not call');});
});
