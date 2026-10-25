# A/B SiliconFlow proxy

This small Node service is the only component that holds the SiliconFlow provider key. It exposes two separately validated capabilities while keeping the existing B API compatible:

- `POST /v1/chat/completions`: B Agent's OpenAI-compatible screenshot request. It validates one or two **JPEG data URLs** and preserves the existing response shape.
- `POST /v1/commands/interpret`: A's version 1, text-only, single-intent interpreter. It can select only IDs and actions supplied by the phone in that request.

Both routes use `Authorization: Bearer <B_PROXY_TOKEN>`. The provider key is added only on the server. The model never executes phone actions. In particular, a returned `screen_action` is only a proposal: Android must still obtain fresh user confirmation, verify that the page/task has not expired, execute at most one allowed action, verify the result, and stop. `exit_app` means returning from the supplied current package to Home; it does not kill a process. The command parser never changes A/B ownership based on recognized text.

## A command API

Request fields are strict; unknown fields, duplicate IDs, unsupported control actions, oversized catalogs and invalid strings are rejected:

```json
{
  "version": 1,
  "task_id": "a-001",
  "utterance": "打开微信",
  "apps": [{ "id": "com.tencent.mm", "label": "微信" }],
  "contacts": [{ "id": "contact-1", "name": "张三" }],
  "screen": {
    "package_name": "com.example.current",
    "controls": [{ "id": "next", "label": "下一步", "actions": ["click"] }]
  }
}
```

`screen` may be `null`. A successful response echoes only the version and task ID plus one validated intent:

```json
{
  "version": 1,
  "task_id": "a-001",
  "intent": { "type": "open_app", "target": "com.tencent.mm" }
}
```

Supported intents are `open_app`, `call_contact`, `navigate` (`back`, `home`, `recents`), `exit_app`, `screen_action`, `vision` (`OCR`, `OBJECT`, `COMBINED`), `clarify`, and `unsupported`. The proxy rejects invented IDs/actions, arbitrary URI/package/code execution, multi-step requests and payment, transfer, purchase, deletion, credential or other consequential screen operations. Closing a named app requires its unique catalog ID to equal the current `screen.package_name`; an unknown, ambiguous, or different app yields `clarify`, never arbitrary Home navigation. Other natural-language Home and exit phrasing is interpreted by the model as a proposal requiring phone-side confirmation; a clear app-launch request cannot become an exit. It sends only text to Qwen for this route, requests one JSON object, then validates the result again against the phone-provided catalogs. Catalog limits match Android: 500 apps, 100 contacts, 80 controls; the utterance is at most 2000 characters without control or format characters. IDs are limited to 160 characters, app packages to 255, and labels to 200. The command body limit is 128 KiB and its upstream deadline is approximately 18 seconds.

## Render deployment

Create a Web Service from this directory. No additional npm dependencies are required:

- Build command: `npm install --ignore-scripts`
- Start command: `npm start`
- Environment: `SILICONFLOW_API_KEY` = the SiliconFlow key; `B_PROXY_TOKEN` = a different random string of at least 32 characters; `PORT` is supplied by Render.
- Health check path: `/healthz` (its `capabilities` object advertises both routes)

Do not put `SILICONFLOW_API_KEY` in the APK or in a client-visible setting. In the APK settings, use the deployed service URL and enter only `B_PROXY_TOKEN` as the proxy token. The model is pinned to `Qwen/Qwen3-VL-8B-Instruct` by the proxy. HTTPS is required by the release APK.

The proxy does not log request bodies, screenshots, utterances, prompts, provider keys, or proxy tokens. It rejects external image URLs, non-JPEG data, model overrides, streaming, tools, oversized input, and provider error bodies. Rotate both server secrets if they are exposed.

## Local verification and handoff

```powershell
cd b_agent_proxy
npm test
$env:SILICONFLOW_API_KEY = "server-only-provider-key"
$env:B_PROXY_TOKEN = "a-distinct-random-token-at-least-32-characters"
npm start
```

For a source handoff, package `.env.example`, `package.json`, `server.mjs`, `command.mjs`, both test files and this README. Do not include a populated `.env`, provider key, proxy token, request/response captures, or `node_modules`. Deploy by updating the existing Render service from this directory and running `npm test` before release; this repository does not deploy itself.

One local PowerShell packaging example is:

```powershell
Compress-Archive -LiteralPath .env.example,package.json,server.mjs,command.mjs,test.mjs,command.test.mjs,README.md -DestinationPath ..\A_B_SiliconFlow_proxy.zip
```
