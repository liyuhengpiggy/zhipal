# 智能眼镜 APP 服务端接口交接文档

版本：1.0  
适用服务：`medicine-ocr` 统一视觉服务器  
交接对象：Android APP、ESP32 联调同学

## 1. 服务职责

APP 和 ESP32 只需要访问这一台服务器，不需要在手机上安装 Python、PaddleOCR 或 Qwen 模型。

| 能力 | 服务端实现 | APP 调用入口 |
| --- | --- | --- |
| 药品说明、生产日期等文字识别 | 本机 PaddleOCR，多帧任务 | `/api/v1/vision/tasks` |
| 开放式物体识别、物体问答 | 硅基流动云端 `Qwen/Qwen3-VL-8B-Instruct` | `/api/v1/object-recognition` |
| MobileAgentB 屏幕理解 | 统一代理到同一个 Qwen 兼容接口 | `/v1/chat/completions` |
| 服务状态 | 数据库、OCR worker 和配置状态 | `/health` |

服务器只负责推理和返回建议，不会远程点击手机，也不会把模型 API Key 下发给 APP。

## 2. 地址、认证和环境

### 2.1 服务地址

本机测试：

```text
http://127.0.0.1:8000
```

手机或 ESP32 与电脑连接同一个 Wi-Fi 时，使用电脑的局域网 IPv4：

```text
http://<电脑局域网IP>:8000
```

手机端不能使用 `127.0.0.1`，因为它指向手机本身。电脑防火墙需要允许 TCP 8000 入站。

### 2.2 HTTP 认证

除 `GET /health` 外，所有接口都需要：

```http
Authorization: Bearer <VISION_API_TOKEN>
```

APP 只保存服务器访问令牌。硅基流动的 `VISION_QWEN_API_KEY` 只保存在服务器环境变量中，禁止写入 APK、日志或 Git。

### 2.3 服务端启动

```powershell
cd D:\Codex\AI创作大赛\medicine-ocr
$env:VISION_API_TOKEN = "由服务负责人分配的服务器令牌"
$env:VISION_QWEN_BASE_URL = "https://api.siliconflow.cn/v1"
$env:VISION_QWEN_MODEL = "Qwen/Qwen3-VL-8B-Instruct"
$env:VISION_QWEN_API_KEY = "硅基流动控制台创建的真实 API Key"
.\start_api.cmd --host 0.0.0.0 --port 8000
```

启动后先等待 OCR 模型加载完成，再检查 `/health` 的 `ready=true`。当前 SQLite 是本地集成测试数据库，不是生产云数据库；服务重启会保留已写入的任务和图片。

## 3. 健康检查

```http
GET /health
```

不需要认证。正常响应的关键字段：

```json
{
  "status": "ok",
  "ready": true,
  "database": "ok",
  "model": { "state": "ready" },
  "capabilities": {
    "ocr": true,
    "object_recognition": true,
    "mobileagentb_proxy": true,
    "qwen_endpoint": "https://api.siliconflow.cn/v1",
    "qwen_model": "Qwen/Qwen3-VL-8B-Instruct"
  }
}
```

`ready=false` 时 APP 应提示服务初始化中并稍后重试；`status=degraded` 或 HTTP 503 时不要反复创建任务。

## 4. OCR 任务接口

OCR 是异步任务，标准流程是：

1. APP 创建任务。
2. APP 上传一张或多张 JPEG frame。
3. 如果问题在图片之后才确定，使用 PATCH 补充问题。
4. APP 轮询任务，直到 `succeeded`、`failed` 或 `timed_out`。

### 4.1 创建任务

```http
POST /api/v1/vision/tasks
Content-Type: application/json
Authorization: Bearer <token>
```

请求：

```json
{
  "task_id": "glasses-20261024-0001",
  "record_id": "event-0001",
  "source_button": "A",
  "task_type": "medicine_ocr",
  "question": "这个药盒的生产日期是什么？",
  "expected_frames": 1
}
```

`task_id` 可由 APP 生成，也可以省略让服务端生成。编号只能包含英文字母、数字、下划线和短横线，长度 1 到 64。`expected_frames` 为 1 到 4。相同参数重复创建是幂等的，参数冲突返回 409。

### 4.2 上传 JPEG

```http
PUT /api/v1/vision/tasks/{task_id}/frames/{frame_id}
Content-Type: image/jpeg
X-Task-Id: {task_id}
X-Frame-Id: {frame_id}
Authorization: Bearer <token>
```

请求体必须是原始 JPEG 字节，不能使用 multipart、JSON 或 Base64。单张最大 8 MiB，最大 1600 万像素。`frame_id` 例如 `f0`、`front`、`back`。

图片可以先到，但仍需先创建一个没有问题的任务；问题稍后用 PATCH 补上。服务器不会用“数据库中最新的一张图片”替代当前任务图片。

### 4.3 补充或修改问题

```http
PATCH /api/v1/vision/tasks/{task_id}
Content-Type: application/json
Authorization: Bearer <token>
```

```json
{ "question": "请读取药品使用说明中的用法用量" }
```

任务已经进入排队、运行或终态后不能更换问题；请创建新任务。

### 4.4 查询任务

```http
GET /api/v1/vision/tasks/{task_id}
Authorization: Bearer <token>
```

状态含义：

| 状态 | APP 行为 |
| --- | --- |
| `awaiting_question` | 等待补充问题 |
| `awaiting_frame` | 继续上传缺少的 frame |
| `queued` / `running` | 继续轮询 |
| `succeeded` | 读取 `answer` 和 `evidence_text` |
| `failed` / `timed_out` | 展示失败原因并允许创建新任务 |

成功结果示例：

```json
{
  "task_id": "glasses-20261024-0001",
  "status": "succeeded",
  "answer": "生产日期识别候选：2026/08/12，请对照原图确认。",
  "evidence_text": "生产日期：2026/08/12",
  "result": {
    "answer_type": "field_candidate",
    "intent": "生产日期",
    "review_required": true
  }
}
```

`review_required=true` 表示需要结合原图或人工确认，尤其是药品名称、日期、剂量和用法。模型不会从模糊外观推断具体药名或服药决策。

## 5. 开放式物体识别接口

适合“这是什么”“画面中有什么”“这个遥控器有什么作用”等问题。该接口直接调用云端 Qwen，返回真实模型答案，不使用固定答案。

```http
POST /api/v1/object-recognition
Content-Type: application/json
Authorization: Bearer <token>
```

请求中的 `images` 支持 `data:image/jpeg;base64,...` 或服务端可访问的 HTTP(S) 图片 URL，最多 4 张。推荐 APP 将拍摄的 JPEG 转为 Base64 data URL；单个 JSON 请求最大约 48 MiB。

```json
{
  "task_id": "object-0001",
  "question": "请识别画面中能确认的主要物体，不要猜测看不清的品牌或型号。",
  "images": ["data:image/jpeg;base64,<base64>"]
}
```

响应：

```json
{
  "task_id": "object-0001",
  "status": "succeeded",
  "model": "Qwen/Qwen3-VL-8B-Instruct",
  "answer": "图片中可以确认有一个药盒。具体药品名称和生产日期无法仅凭当前画面确认。",
  "evidence_text": "由图片视觉模型生成；请以原图核对。",
  "review_required": true
}
```

如果云端 Key 无效、余额不足、网络失败或模型响应异常，返回 HTTP 503 和 `model_unavailable`；APP 应显示“视觉服务暂时不可用”，不要把错误当成识别结果。

## 6. MobileAgentB 代理接口

APP 保留现有 OpenAI Chat Completions 请求格式，服务器只转发给 Qwen 并返回兼容响应：

```http
POST /v1/chat/completions
Content-Type: application/json
Authorization: Bearer <token>
```

请求至少包含 `messages` 数组，`messages` 可包含文字、截图 `image_url`、当前目标、操作历史和候选控件信息。`model` 可省略，服务器会使用配置的 Qwen 模型。

```json
{
  "model": "Qwen/Qwen3-VL-8B-Instruct",
  "messages": [
    { "role": "system", "content": "你是 Android 操作规划助手。只能从候选控件中选择。" },
    {
      "role": "user",
      "content": [
        { "type": "text", "text": "目标：打开设置。候选控件：..." },
        { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,<截图>" } }
      ]
    }
  ],
  "max_tokens": 512
}
```

响应沿用上游 `choices[0].message.content`。服务器不会执行点击，APP 必须验证模型建议中的候选控件后再执行本地操作。若 APK 已经使用 `/api/v1/screen/predict`，请先与服务负责人冻结接口，不要同时维护两套协议。

## 7. Android 调用建议

建议使用 OkHttp/Retrofit，统一设置：

```text
Base URL: http://<电脑局域网IP>:8000/
Authorization: Bearer <服务器令牌>
Connect timeout: 10 秒
Read timeout: 180 秒
```

OCR 上传示例（OkHttp）：

```kotlin
val request = Request.Builder()
    .url("$baseUrl/api/v1/vision/tasks/$taskId/frames/$frameId")
    .header("Authorization", "Bearer $serverToken")
    .header("X-Task-Id", taskId)
    .header("X-Frame-Id", frameId)
    .put(jpegBytes.toRequestBody("image/jpeg".toMediaType()))
    .build()
```

AndroidManifest 需要网络权限：

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

局域网 HTTP 调试时，Android 9 及以上可能需要允许明文流量；正式异地访问应改用 HTTPS 反向代理或安全隧道。

## 8. 错误处理和重试

统一错误格式：

```json
{ "error": { "code": "unauthorized", "message": "需要有效的访问凭证。" } }
```

| HTTP / code | APP 处理 |
| --- | --- |
| 401 `unauthorized` | 检查服务器地址和 Bearer Token，不要循环重试 |
| 404 `task_not_found` | 检查 task_id，必要时重新创建 |
| 409 `task_conflict` / `frame_conflict` | 不要复用冲突编号，创建新任务 |
| 413 | 压缩图片或降低分辨率 |
| 415 | 上传原始 JPEG，并设置 `Content-Type: image/jpeg` |
| 422 | 修正请求字段和编号格式 |
| 429 `queue_full` | 退避后重试，限制并发任务 |
| 503 `model_unavailable` | 等待服务恢复；不要伪造成功答案 |

断网重连时，优先使用原 `task_id` 查询状态，再决定是否重试上传。客户端建议每 0.5 到 1 秒轮询一次，最多等待 180 秒；同一任务不要并发重复上传同一个 frame。

## 9. 联调验收清单

- [ ] 手机访问 `http://电脑局域网IP:8000/health` 并看到 `ready=true`。
- [ ] 使用服务器令牌创建 OCR 任务。
- [ ] 上传真实 JPEG，服务返回 `202` 或幂等重试的 `200`。
- [ ] 查询任务得到 `succeeded`、`answer` 和 `evidence_text`。
- [ ] 调用物体识别接口得到真实 Qwen 回答。
- [ ] MobileAgentB 截图请求得到 `choices` 响应。
- [ ] 断网、错误令牌、超大图片能显示明确错误。
- [ ] 不同 task_id 的图片和答案不串线。

服务端现有详细接口定义仍可参考 `UNIFIED_SERVER.md`、`VISUAL_API.md` 和 `/docs`；本文件以 APP 实际接入流程为准。
