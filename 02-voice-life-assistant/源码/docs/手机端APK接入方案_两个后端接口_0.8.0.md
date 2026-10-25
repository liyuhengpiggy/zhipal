# 手机端 APK 接入方案与联调说明（两个后端接口）

版本基线：`0.8.0` / versionCode `21`，包名 `cn.seniorvoice.demo`。本文按《手机端 APK 接入提示词》第六节要求，说明两个已部署后端接口在手机端与眼镜硬件之间的接入现状、涉及文件、新增权限、需固件配合的协议，并分别标明「已实现 / 需要固件配合 / 尚未实现」。

后端地址：`https://ai-glasses-backend-7bj3.onrender.com`；接口文档：`/docs`。两个接口均需请求头 `Authorization: Bearer <VISION_API_TOKEN>` 与 `Content-Type: application/json`。令牌经安全渠道提供，不入日志、不入代码仓库，不硬编码进 APK（当前接入把令牌保存在手机 SharedPreferences，正式上线仍需改为用户身份鉴权）。

---

## 1 各端职责

| 端 | 职责 |
| --- | --- |
| 后端 | 调用视觉模型分析图片并同步返回结果；`/v1/chat/completions` 转发 MobileAgentB 屏幕分析。 |
| 手机端 | 接收眼镜事件、生成 `task_id`、调度拍照、上传图片（Base64）、处理并播报结果；通过无障碍服务实现单步操作执行。 |
| 眼镜固件 | 上报按键事件、接收拍照指令、完成拍照与照片传输、配合任务关联。 |

后端接口不会主动收到眼镜按键事件，也不会主动控制眼镜拍照；按键上报、拍照指令、照片传输由手机端与固件共同约定。

---

## 2 接口一：物体识别 `POST /api/v1/object-recognition`

### 2.1 协议（已实现）

同步接口，直接返回结果，无任务查询接口，无轮询。

请求：

```json
{
  "task_id": "手机端生成的唯一任务ID",
  "question": "请描述图片中的主要物体及其颜色，不确定时明确说明。",
  "images": ["data:image/jpeg;base64,<图片Base64内容>"]
}
```

成功响应：

```json
{
  "task_id": "手机端生成的唯一任务ID",
  "status": "succeeded",
  "model": "Qwen/Qwen3-VL-8B-Instruct",
  "answer": "图片中的主要物体是一个蓝色杯子。",
  "evidence_text": "由图片视觉模型生成；请以原图核对。",
  "review_required": true
}
```

### 2.2 已实现内容

- `MedicineVisionApiClient.recognizeObject(id, question, images, ticket)`：将 JPEG 编码为 `data:image/jpeg;base64,` 前缀的 Base64，POST 到 `api/v1/object-recognition`；校验响应 `task_id`、`status == "succeeded"`、`question` 匹配；拒绝 mock/模拟结果；单张限制 8 MiB、请求体限制 48 MiB；`review_required` 缺省时按保守处理（日期、药名、剂量需核对）。
- 图片压缩：复用 `com/mobileagent/app/api/ImageEncoder`（长边 ≤ 1344、JPEG 质量 85），兼顾清晰度与上传速度。
- 异常处理：`401/403` 令牌无效、`404` 接口或任务不存在、`413` 图片过大、`415` 格式错误、`422` 字段错误、`429` 队列满、`503` 服务不可用/初始化中、`300..399` 重定向——均映射为明确中文提示；鉴权与配置错误不重试；冷启动提示「正在连接/正在识别」并支持取消。
- 调度与播报：`AssistRuntime.startVisualQuestion` 识别视觉问题后，经 `MedicineVisionWorkflow` 完成健康检查 → 拍照/上传 → `recognizeObject` → 结果 `result(task, "vision_answer", ...)` 显示 + 手机 TTS 朗读。

### 2.3 需要固件配合（任务关联与照片传输）

物体识别的「眼镜拍照」链路依赖固件把本次照片回传手机：

- 手机生成 `task_id`，经 BLE `capture` 通知固件，携带硬件 `task_id`、服务 `vision_task_id`、`frame_id=f0`、`question`、`upload_url`、`content_type=image/jpeg`。
- 固件拍照后经 Wi-Fi 上传 JPEG，再以 `image_ready` 回传匹配的 `task_id` / `vision_task_id` / `f0` 及可读取 `url`/`image_url`。
- 手机只从已配置服务源下载当前 JPEG（`TaskPhotoDownloader`），再转 Base64 调 `object-recognition`；**不会**把「上传完成」当作已有可分析图片，也**不会**读取 `latest` 缓存。

若现有固件无法携带 `task_id`，需固件同学升级协议（见第 5 节），避免连续拍照串任务。**缺少图片下载 URL 的生成接口前，眼镜物体识别不能宣布闭环**，应先完成「手机选图 → object-recognition」通路验证。

---

## 3 接口二：MobileAgentB 屏幕分析 `POST /v1/chat/completions`

### 3.1 协议（已实现）

OpenAI 兼容，`stream: false`，从 `choices[0].message.content` 读取模型内容。

请求：

```json
{
  "messages": [
    {
      "role": "user",
      "content": [
        { "type": "text", "text": "请分析当前屏幕，说明主要内容和可以操作的控件。" },
        { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,<截图Base64内容>" } }
      ]
    }
  ],
  "stream": false
}
```

### 3.2 已实现内容

- 屏幕截图：`BAgentScreenshot.capture` 经用户授权的 Android `AccessibilityService.takeScreenshot`（API 30+），原始显示坐标，不缩放。
- 请求与解析：`OpenAiCompatibleClient` + `BModelProtocol`——OpenAI 兼容 messages（`image_url` Base64 + `text`），严格解析 `choices`（单条、`assistant` 角色、`finish_reason=stop`、无 tool_calls/function_call/refusal），并按既有 B 规划/动作格式解析 `### Thought ###` / `### Plan ###` / `### Action ###` / `### Description ###`。
- 动作协议校验：`Action` 只接受一个 `click` 且 `target_id` 必须来自本次真实无障碍控件列表；**不接受**自然语言直接当指令、不接受模型猜坐标。
- 单步执行模块：`AccessibilityActionExecutor`（`performAction(ACTION_CLICK)` / `dispatchGesture` 滚动 / `GLOBAL_ACTION_BACK`），执行前重新校验页面、窗口、控件特征、隐私过滤，敏感操作（支付、删除、发送、授权）交由用户手动完成。

### 3.3 当前边界与尚未实现（自动操作）

提示词要求「自动点击、滑动、返回、输入文字」由手机端执行模块完成。当前状态：

- **已实现**：截图分析 + 单次点击/滚动/返回（用户再次确认后执行一步）。
- **尚未实现**：连续多步代办循环、任意文字输入、自动发消息、支付等；动作后的自动重截图判断尚未形成完整连续循环。提示词第 4 节要求的「最大执行步数、超时、停止按钮」的连续循环闭环仍待实现（当前为单步 + 用户逐次确认模型）。

坐标换算：截图使用原始显示坐标，无障碍 bounds 同为屏上坐标，无需缩放；系统栏偏移依赖 `getBoundsInScreen`，已正确处理。

---

## 4 涉及文件清单

手机端源码根：`app/src/main/java/cn/seniorvoice/demo/`（`com/mobileagent` 路径除外）。

| 文件 | 用途 |
| --- | --- |
| `assist/MedicineVisionApiClient.kt` | 交接服务健康检查、异步 OCR、原始 JPEG PUT、**物体识别 `object-recognition`** 与解析 |
| `assist/MedicineVisionWorkflow.kt` | 视觉流程：180 秒预算、单张 f0、同图组合、取消与 OCR 恢复 |
| `assist/AssistRuntime.kt` | A/B 路由、配置保存、任务关联、A 抢占、结果与 TTS |
| `assist/VisionApiClient.kt` / `VisionWorkflow.kt` | 旧「统一视觉任务」接口（OCR/物体/组合，兼容保留） |
| `assist/VisionClient.kt` | 旧 `/analyze` multipart 视觉服务（兼容保留） |
| `assist/TaskPhotoDownloader.kt` | 校验并下载当前任务照片 |
| `assist/ServerRouteTestActivity.kt` | 通路测试页（交接 OCR/物体/组合、B Agent 独立测试入口） |
| `assist/BAgentConfig.kt` / `BAssistController.kt` / `BAgentScreenshot.kt` | B 配置、停留检测、单步状态机、无障碍截图 |
| `assist/ServerConfig.kt` / `OcrProtocol.kt` | 服务器根地址与接口协议校验 |
| `com/mobileagent/app/api/OpenAiCompatibleClient.kt` / `BModelProtocol.kt` / `ImageEncoder.kt` | MobileAgentB 请求、响应解析、图片压缩 |
| `nextaction/execution/AccessibilityActionExecutor.kt` | 单步点击/滚动/返回执行模块 |
| `nextaction/observation/NextActionAccessibilityService.kt` / `AccessibilityBridge.kt` | 无障碍服务与控件树解析 |
| `assist/AssistSettingsActivity.kt` | 交接服务器配置入口 |

测试：`app/src/test/java/.../assist/MedicineVisionApiClientTest.kt`、`MedicineVisionWorkflowTest.kt`、`BModelProtocolTest.kt`、`BAgentIntegrationTest.kt` 等。

---

## 5 新增权限与需固件配合的协议

### 5.1 新增/相关权限（AndroidManifest 已声明）

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 调用两个后端接口 |
| `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN`（`neverForLocation`） | BLE 接收眼镜事件、下发拍照指令 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CONNECTED_DEVICE` / `POST_NOTIFICATIONS` / `WAKE_LOCK` / `RECEIVE_BOOT_COMPLETED` | 后台连接保活 |
| `RECORD_AUDIO` | 兼容 WAV 离线转写路径（BLE 输入本身不要求麦克风） |
| `CALL_PHONE` | 拨打已保存联系人（A 指令） |
| 无障碍服务（`BIND_ACCESSIBILITY_SERVICE`，`NextActionAccessibilityService`） | 截图与单步操作，需用户在系统设置单独授权 |

本次两个接口**未新增**运行时权限；截图与自动操作依赖用户已授权的无障碍服务（API 30+ 截图）。

### 5.2 需固件同学配合的协议

| 项目 | 现状 | 需固件配合 |
| --- | --- | --- |
| 按键事件上报 | 已实现（A 文字帧 v1 / B prompt 录音） | 无 |
| 拍照指令 `capture` | 已实现（携带 task_id/vision_task_id/frame_id/upload_url/headers） | 按本协议识别并仅向配置服务上传 |
| 照片回传 `image_ready` | **需要固件配合** | 回传匹配的 task_id / vision_task_id / f0 及可读取 url |
| `task_id` 关联 | 已实现 | 若固件无法携带 task_id，需升级协议，避免连续拍照串任务 |

---

## 6 验收清单与状态标注

| 编号 | 验收项 | 状态 |
| --- | --- | --- |
| 1 | 手机选测试图片 → 调用物体识别 → 显示并播报结果 | 已实现（代码），待真机联调 |
| 2 | 眼镜按键触发拍照 → 上传 → 识别 → 播报，全程同一 task_id | **需要固件配合**（照片回传 URL 协议） |
| 3 | 手机截图提交 MobileAgentB 并展示分析结果 | 已实现（代码），待真机联调 |
| 4 | 自动操作（授权后执行、可随时停止） | 单步点击/滚动/返回已实现；连续循环、文字输入**尚未实现** |
| 5 | 保留实时照片与腾讯云 OCR 功能 | 已实现（旧接口兼容保留） |

状态三分类：**已实现**（代码完成、通过编译与单元测试）／**需要固件配合**（手机端就绪，缺固件侧协议）／**尚未实现**（连续自动操作循环）。接口接通不等于完整产品流程已完成。

---

## 7 联调说明

1. 手机「设置 → A/B 页面帮助配置」保存交接服务器根地址（不含 `/docs`、`/api/v1`）与 `VISION_API_TOKEN`；调试包可显式勾选「允许局域网 HTTP」。
2. 点击「检查交接服务与模型能力（/health）」，核对 `ready=true` 与 `ocr` / `object_recognition` / `mobileagentb_proxy` 能力。
3. 「服务器通路测试」→「单独测试交接物体识别（真实图片 → Qwen）」，核对确实走 `/api/v1/object-recognition`、答案来自当前图片。
4. 「单独测试 B Agent」，核对截图 Base64 输入、choices 响应与规划格式解析（不会操作手机）。
5. 真实眼镜：A 录音 → 本地 ASR → 拍照 → 指定服务 → 手机显示 + TTS；B 停留询问 → B 录音 → 建议 → 再次确认 → 执行一步并停止。
6. 错误定位：`401` 查令牌、`503 configuration_error` 联系后端、`503 model_unavailable` 提示稍后重试、`504 model_timeout` 提示重试；不反复重试鉴权/配置错误，日志不含令牌或完整照片。
