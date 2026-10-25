// Explicit live probe. Credentials enter only through env or stdin, never files/CLI arguments.
import { readFileSync, writeFileSync, mkdirSync, readdirSync } from 'node:fs';
import { resolve, join, delimiter } from 'node:path';
import { spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { createServer, MODEL } from '../b_agent_proxy/server.mjs';

const root = resolve(import.meta.dirname, '..');
let key = process.env.SILICONFLOW_API_KEY;
if (!key) {
  console.log('Awaiting credentials on stdin (not logged).');
  let input = '';
  for await (const c of process.stdin) { input += c; if (input.includes('\n')) break; }
  key = JSON.parse(input).apiKey;
}
const java = join(root, 'build-tools/jdk/jdk-17.0.20.1+1/bin/java.exe');
const dependencies = ['app/build/tmp/kotlin-classes/debug', 'app/build/tmp/kotlin-classes/debugUnitTest'].map(p => join(root,p));
dependencies.push(...readdirSync(join(root,'app/libs/b-agent')).filter(p=>p.endsWith('.jar')).map(p=>join(root,'app/libs/b-agent',p)));
const kotlinCache = join(root,'build-tools/gradle-home/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.21');
for (const folder of readdirSync(kotlinCache)) for (const file of readdirSync(join(kotlinCache,folder))) if (file.endsWith('.jar')) dependencies.push(join(kotlinCache,folder,file));
const image = resolve(process.argv[2] || join(root,'tmp/b-agent-real-jpeg-test.jpg'));
const output = resolve(process.argv[3] || join(root,'tmp/b-agent-live-response'));
mkdirSync(output,{recursive:true});
const runParser = stage => {
  const r=spawnSync(java,['-Dfile.encoding=UTF-8','-cp',dependencies.join(delimiter),'cn.seniorvoice.demo.assist.BModelLiveProbe',stage,image,output],{encoding:'utf8',maxBuffer:12*1024*1024});
  if (r.status !== 0) throw new Error(`app_parser_failed_at_${stage}: ${r.stderr?.slice(0,1500) || r.error?.code || 'unknown'}`);
  return r.stdout;
};
const token=randomBytes(32).toString('hex');
const proxy=createServer({providerKey:key,proxyToken:token});
key=undefined;
await new Promise(resolve=>proxy.listen(0,'127.0.0.1',resolve));
try {
  for (const stage of ['plan','action','reflect']) {
    const body=runParser(stage);
    const response=await fetch(`http://127.0.0.1:${proxy.address().port}/v1/chat/completions`,{
      method:'POST',headers:{'Content-Type':'application/json',Authorization:`Bearer ${token}`},body,signal:AbortSignal.timeout(115000)
    });
    const text=await response.text();
    if (!response.ok) {
      const e=JSON.parse(text);
      console.log(JSON.stringify({stage,proxy_status:response.status,provider_status:e.provider_status,error:e.error}));
      process.exitCode=1;break;
    }
    const result=JSON.parse(text);
    writeFileSync(join(output,`${stage}.json`),text,{encoding:'utf8'});
    console.log(JSON.stringify({stage,model:result.model,finish_reason:result.choices?.[0]?.finish_reason,usage:result.usage}));
  }
  if (!process.exitCode) {
    console.log(runParser('verify').trim());
    console.log(JSON.stringify({result:'pass',requested_model:MODEL,proxy:'local real proxy -> SiliconFlow',phone_actions:0}));
  }
} catch (e) {
  // Never dump request options, environment, or authorization headers.
  console.log(String(e.message).slice(0,1800));process.exitCode=1;
} finally { await new Promise(resolve=>proxy.close(resolve)); }
if (process.exitCode) process.exit(process.exitCode);
