# B Agent 视觉识别：千问 Qwen 接入说明

> 记录 B Agent（`/suggest`、`/locate`）从 mock 切换到真实千问视觉模型的完整配置。

## 结论

**B Agent 的视觉预测已经用千问 Qwen3-VL 真实模型跑通**（实测通过）。

- `/suggest`：带截图 + 候选控件，千问正确选出目标控件，返回 `mode: cloud`
- `/locate`：视觉定位可点控件（含无文字图标），正确返回控件坐标，`mode: cloud`

## 配置（与 APK 原版完全一致）

配置来源：APK 项目 `AssistRuntime.kt` + `BuildConfig.java`（`INTEGRATED_MODEL_KEY`）。

| 配置项 | 值 |
|---|---|
| 模型 | `Qwen/Qwen3-VL-8B-Instruct`（APK 默认；可选 `Qwen/Qwen3-VL-32B-Instruct`）|
| 地址 | `https://api.siliconflow.cn/v1`（SiliconFlow）|
| API Key | `sk-ylzz...`（APK 的 `INTEGRATED_MODEL_KEY`）|

后端环境变量：

| 环境变量 | 值 |
|---|---|
| `VISION_MODE` | `openai_compatible` |
| `VISION_BASE_URL` | `https://api.siliconflow.cn/v1` |
| `VISION_MODEL` | `Qwen/Qwen3-VL-8B-Instruct` |
| `VISION_API_KEY` | SiliconFlow 的千问 key |
| `VISION_TIMEOUT_SECONDS` | `60`（Qwen-VL 响应较慢，建议调高）|

## 鉴权（额外必需）

iOS 会话接口（`/suggest`、`/locate`、`/reflect` 等）有 `VISION_API_TOKEN` Bearer 鉴权门槛：

- 环境变量：`VISION_API_TOKEN=某个自定义字符串`
- 请求头：`Authorization: Bearer <VISION_API_TOKEN>`

`VISION_API_TOKEN` 是**服务访问凭证**（防匿名调用），`VISION_API_KEY` 是**模型 API Key**（调千问），两者不是一回事。

## 已修改文件

1. `.env`（本地开发，不上传 Git）：千问配置 + 本地 token
2. `.env.example`：千问配置模板
3. `render.yaml`：云端 Render 部署接千问（密钥留空，需在 Render 控制台填）

## 云端部署注意

- `VISION_API_KEY` 和 `VISION_API_TOKEN` 是密钥，**不能明文提交 GitHub**。`render.yaml` 里留空，部署后在 Render 控制台 Environment 手动填。
- 千问 key 只充了少量额度（原话"只充了 10 元"），视觉请求消耗较快，注意额度。

## 备选：DeepSeek

如果以后想换 DeepSeek（官方 `deepseek-chat` 已支持图片输入），改环境变量即可：

```
VISION_MODE=openai_compatible
VISION_BASE_URL=https://api.deepseek.com/v1
VISION_MODEL=deepseek-chat
VISION_API_KEY=<DeepSeek 官方 key>
```

后端 `_model_configuration()` 已原生支持 OpenAI 兼容模式，只改环境变量，无需改代码。
