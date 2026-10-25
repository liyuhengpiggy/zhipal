# NextAction 单步预测后端

这个服务只产生一次结构化决策；没有手机执行工具，不操作设备，不保存截图。它与旧的图片 `/analyze` 和日常记录接口是独立模块，不能把旧图片接口填入 NextAction 地址。

## 目录与职责

| 文件 | 作用 |
| --- | --- |
| `app/schemas/observation.py` | 严格的 snake_case 请求、节点、候选和 JPEG 边界校验 |
| `app/schemas/prediction.py` | 严格响应协议 |
| `app/services/context_builder.py` | 不可信 UI 数据隔离提示与本次候选专用 JSON Schema |
| `app/providers/base.py` | 可替换视觉模型接口 |
| `app/providers/openai_responses.py` | 一次 OpenAI Responses HTTPS 请求 |
| `app/providers/mock.py` | 只选择本次真实 NO_OP 候选的模拟服务 |
| `app/services/predictor.py` | 超时、取消、结构校验、失败降级和元数据日志 |
| `app/services/response_validator.py` | ID、动作和候选对应关系检查 |
| `app/services/replay_guard.py` | 进程内有界请求重放拒绝 |
| `app/api/body_limit.py` | JSON 解析前拦截过大请求 |
| `app/api/routes.py` | 认证、健康检查与预测 HTTP 接口 |
| `app/main.py` | FastAPI 组装入口 |

## 安装与本地启动

需要 Python 3.10 或更新版本。先切到此 `agent_backend` 目录，再运行 PowerShell 命令：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
$env:VISION_PROVIDER='mock'
$env:REQUEST_TIMEOUT_SECONDS='15'
$env:ENABLE_DEBUG_LOGGING='false'
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8788 --no-access-log
```

启动后 `GET http://127.0.0.1:8788/health` 返回基本状态。`POST /api/v1/next-action/predict` 接收 JSON，完整结构见上述 Pydantic 模型与 `tests/fixtures.py`。服务端 Mock 总是返回 NO_OP；App 本地 Mock 仅允许安全练习页的一次 TAP 或 NO_OP。返回和滚动由 JVM 伪执行器测试覆盖，真实 Android 操作需后续真机云端模式验证。

`.env.example` 只是示例。服务不会自动读取 `.env`，请使用进程环境变量，避免误以为改文件已经生效。

## 连接真实视觉模型

在启动服务的同一终端设置以下变量后重启服务。模型必须支持图片与 Responses 结构化输出；具体可用模型由服务部署者选择。

```powershell
$env:VISION_PROVIDER='openai'
$env:VISION_MODEL='填写你账号可用且支持视觉与结构化输出的模型名'
$env:OPENAI_API_KEY='仅在服务端设置的真实密钥'
$env:REQUEST_TIMEOUT_SECONDS='15'
$env:ENABLE_DEBUG_LOGGING='false'
$env:AGENT_API_TOKEN='自行生成的足够长随机共享口令'
```

API Key 不得放在 Android APK、截图、提交的代码或诊断日志中。可选 `AGENT_API_TOKEN` 是 App 到本服务的访问口令，Android 面板填相同值，由请求头 `Authorization: Bearer ...` 携带。公网部署应设置它，并在代理层增加访问限制。

OpenAI 请求使用固定的 `https://api.openai.com/v1/responses`、`input_image` 和 `text.format.type=json_schema`，`store=false`，没有 `tools`。每次模型调用只有一次 HTTP POST，`max_retries=0`，禁止自动跟随重定向。返回的结构化 Schema 动态枚举本次候选 ID、动作以及 request/capture ID；响应还会经过本地严格对应关系校验。页面内容和历史都明确作为不可信数据，不能修改系统规则。

## HTTPS 部署和手机配置

Android 云端模式只连接 HTTPS。`http://127.0.0.1:8788` 用于服务器本机自测，不能直接作为手机云端地址。

一种部署方式是在有域名与证书的服务器上，将 uvicorn 保持绑定 `127.0.0.1:8788`，用 Caddy 代理。把 `deploy/Caddyfile.example` 的域名改成自己的实际域名，按 Caddy 的正常流程安装与启动。反向代理终止 HTTPS，代理到本机 HTTP；不得直接暴露没有访问口令的端口。手机 NextAction 面板填写：

```text
https://自己的域名/api/v1/next-action/predict
```

健康检查地址为 `https://自己的域名/health`。手机需要信任该 HTTPS 证书；不要关闭 TLS 校验或使用自签名证书绕过校验。本示例未在真实域名与证书上部署验证。

## 限制与失败行为

- 请求体最大 2 MiB，JPEG 解码后字节最大 1 MiB，图片宽高各不超过 4096。检查 JPEG 头与尺寸，不尝试绕过 Android 安全截图限制。
- 节点 1–160 个，候选 1–40 个，历史 0–20 条，标签最多 200 字符。未知字段、错误类型、重复 JSON 键、重复节点/候选、没有真实 NO_OP 的请求返回明确 422。
- BACK/NO_OP 的 `node_key/class_name/view_id/bounds` 必须为 null。其他候选必须精确对应本次可见、启用的真实节点；TAP 需要可点击，SCROLL 需要可滚动。
- 网络异常、拒绝、超时、非法响应、未知候选都会变为本次真实 NO_OP，保持请求与采集 ID。取消请求不会采用迟到结果。禁止自动重试和第二轮模型调用。
- 进程内重放缓存最多 2048 个 request_id，保留 10 分钟；重复或满载返回 409。它不会跨重启或跨多进程共享。Demo 使用单个 uvicorn worker；手机执行闸门仍是防重复执行的最终保证。
- 单进程最多同时运行 4 个预测；满载返回明确 503，客户端不能自动重试。
- 默认和调试日志都只记录 request_id、结果代码、耗时；调试模式另加节点/候选/历史数量。不记录截图、包名、标签、历史正文、模型 reason、密钥、上游错误正文或异常堆栈。
- 置信度不能代替手机端安全策略；后端不能证明目标仍在当前页面。Android 必须再次检查页面、目标、有效期与执行次数。

## 测试与本次验证状态

在本目录运行：

```powershell
python -m unittest discover -s tests -v
```

不需要 pytest 或真实密钥。核心测试只需要 Pydantic；安装 FastAPI 后，同一命令会自动启用 `tests/test_api.py` 的 ASGI 路由测试。

本次环境已实际运行核心协议、候选绑定、Responses 请求构造、拒绝、超时迟到结果丢弃、取消、日志脱敏、请求体中间件等单元测试。FastAPI/uvicorn 没有安装，定向检查也未找到离线轮子；因此 HTTP 路由集成测试明确跳过，服务启动尚未验证。自动审批服务连接失败阻止了联网依赖安装和官方文档核对；没有绕过审批。

真实 OpenAI API、真实模型能力、真实 HTTPS 部署和手机端到端请求均未验证。当前 Responses 协议代码以已知接口实现并经 Mock 传输测试；拿到依赖、真实密钥和可用模型后，先启动 Mock 验证 HTTP，再在 App 中显式切换云端模式验证一次请求。不得将这些步骤描述成已经成功。
