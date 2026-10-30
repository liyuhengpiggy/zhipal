# B Agent 视觉识别：DeepSeek 接入说明

> 记录 B Agent（`/suggest`、`/locate`）从 mock 切换到真实 DeepSeek 视觉模型的完整配置。

## 结论

**B Agent 的视觉预测已经用 DeepSeek 真实模型跑通**（实测通过）。

- `/suggest`：带截图 + 候选控件，DeepSeek 正确选出 `target_id=3`（"打开微信"按钮），返回 `mode: cloud`
- `/locate`：视觉定位可点控件（含无文字图标），正确返回 5 个控件的坐标，`mode: cloud`

## 关键发现

**DeepSeek 官方 `deepseek-chat` 现在支持图片输入了**（返回的实际模型是 `deepseek-flash`，是 DeepSeek 新的多模态模型）。之前担心"DeepSeek 不能看图"是过时认知——实测能识别图片内容。

## 配置（4 个环境变量）

| 环境变量 | 值 |
|---|---|
| `VISION_MODE` | `openai_compatible` |
| `VISION_BASE_URL` | `https://api.deepseek.com/v1` |
| `VISION_MODEL` | `deepseek-chat` |
| `VISION_API_KEY` | DeepSeek 官方 API Key（`sk-...`） |

可选：`VISION_TIMEOUT_SECONDS=40`（默认 25 秒，DeepSeek 视觉响应较慢，建议调高）。

## 鉴权（额外必需）

iOS 会话接口（`/suggest`、`/locate`、`/reflect` 等）有 `VISION_API_TOKEN` Bearer 鉴权门槛：

- 环境变量：`VISION_API_TOKEN=某个自定义字符串`
- 请求头：`Authorization: Bearer <VISION_API_TOKEN>`

`VISION_API_TOKEN` 是**服务访问凭证**（防匿名调用），`VISION_API_KEY` 是**模型 API Key**（调 DeepSeek），两者不是一回事，别混淆。

## 已修改文件

1. `.env`（本地开发，不上传 Git）：加了 DeepSeek 配置 + 本地 token
2. `.env.example`：补充 DeepSeek 配置模板
3. `render.yaml`：云端 Render 部署改接 DeepSeek（密钥留空，需在 Render 控制台填）

## 云端部署注意

- `VISION_API_KEY` 和 `VISION_API_TOKEN` 是密钥，**不能明文提交 GitHub**。`render.yaml` 里留空，部署后在 Render 控制台 Environment 手动填。
- DeepSeek 免费/低价额度下视觉请求可能较慢，`VISION_TIMEOUT_SECONDS=40` 较稳妥。

## 备选：千问（Qwen）

如果以后想换千问视觉模型，用 SiliconFlow 托管（OpenAI 兼容）：

```
VISION_MODE=openai_compatible
VISION_BASE_URL=https://api.siliconflow.cn/v1
VISION_MODEL=Qwen/Qwen2.5-VL-72B-Instruct
VISION_API_KEY=<SiliconFlow 的 key>
```

后端 `_model_configuration()` 已原生支持，只改环境变量即可，无需改代码。
