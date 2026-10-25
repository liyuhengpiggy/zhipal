# B Agent 硅基流动接入与 Render 配置（0.7.3）

版本 0.7.3（14），包名和调试签名沿用 0.7.2。本次交付新增云端代理源码、手机严格解析和配置说明；没有替你部署 Render，也没有获取或配置提供商密钥。

## 固定的连接方式

手机截图 → 自建 HTTPS B 代理 → 硅基流动 `https://api.siliconflow.cn/v1/chat/completions` → `Qwen/Qwen3-VL-8B-Instruct`。

| 配置 | 放置位置 | 值 |
| --- | --- | --- |
| `SILICONFLOW_API_KEY` | Render 环境变量 | 用户单独配置的硅基流动 API Key |
| `B_PROXY_TOKEN` | Render 环境变量和手机 B 设置 | 与硅基流动 Key 不同的随机令牌，至少 32 字符 |
| B 云端代理地址 | 手机 B 设置 | 新部署服务的 HTTPS 根地址加 `/v1` |
| 视觉模型名称 | 手机 B 设置 | `Qwen/Qwen3-VL-8B-Instruct` |

硅基流动 Key 不进入 APK。手机必须持有代理访问令牌才能访问代理；该令牌只用于你自己的服务。不要把 API Key 发给我或写进代码仓库。已有 OCR URL、OCR 令牌和旧视觉后端配置独立保留。

## 发给后端同学的部署指令

将 `b_agent_proxy/` 上传到你们自己的代码仓库，在 Render 新建 Node Web Service：

```text
Root Directory: b_agent_proxy
Runtime: Node
Build Command: npm install --ignore-scripts
Start Command: npm start
Health Check Path: /healthz
NODE_VERSION: 22
SILICONFLOW_API_KEY: 在 Render Secret 环境变量中填写实际 Key
B_PROXY_TOKEN: 另行生成至少32字符的随机令牌，不能与上述 Key 相同
```

若把该目录内容单独放在一个仓库的根目录，则 Root Directory 留空。服务不依赖数据库或 Python；Node 标准库即可运行。端口读取 Render 的 `PORT`，监听 `0.0.0.0`。没有使用 `.env` 自动加载器；本地运行时也通过环境变量传入，不要只创建 `.env` 就期待生效。

后端同学部署后交付两项：实际代理地址和 `B_PROXY_TOKEN`。访问 `/healthz` 返回 `status:ok` 仅说明代理启动且启动配置通过，不证明提供商 Key 有效、余额充足或模型已推理成功。密钥有效性需要一次真实推理验证。

代理只提供 `POST /v1/chat/completions`，固定模型和上游，禁止客户端覆盖地址、模型、工具和流式参数。仅接受 1～2 张 JPEG Base64，单张最多 4 MiB，整个请求最多 12 MiB，响应最多 256 KiB，同时最多 4 个推理请求。提供商请求超时为 110 秒。代理不记录截图、提示词或凭证，不落盘；上游仍将收到推理必需的截图。非成功响应只回传脱敏错误和状态码，不回传提供商错误原文。

## 图片和模型返回格式

已核对[硅基流动官方 Chat Completions 文档](https://docs.siliconflow.cn/cn/api-reference/chat-completions/chat-completions)：`user.content` 支持多个 `image_url`，其 `url` 明确支持 `data:image/jpeg;base64,...`。

手机通过无障碍截取真实屏幕，先压缩为 JPEG（质量 85，长边最多 1344），使用无换行 Base64 编码。不会把本机文件路径或占位文字冒充图片。规划和候选操作各发送一张当前图；执行后检查发送操作前和操作后两张图。请求明确包含 `stream:false` 和 `max_tokens:4096`。

点击坐标来自手机原始无障碍控件列表的实际中心，不能按压缩图坐标猜测。模型提出的坐标必须精确对应一个唯一、启用、可点击且有可读标签的控件。

手机先验证 `choices` 恰好一个、`finish_reason:stop`、`message.role:assistant`、非空内容；接受 `content` 文本或纯文本分段数组。截断、拒绝、工具调用、函数调用、缺少字段都会停止。再严格解析对应阶段：

```text
规划：### Thought ###、### Plan ###
候选：### Thought ###、### Action ###、### Description ###
检查：### Outcome ###（仅 A/B/C 一字母）、### Error Description ###
```

候选动作仅接受一个 `{"action":"click","coordinate":[整数x,整数y]}`，拒绝多个动作、额外参数、三维坐标、浮点数和文本猜测。格式错误不会转成默认点击；检查失败不会默认为“无变化”或“已成功”。这是格式正确性的保证，不能保证模型对所有画面的语义理解都正确。

## 用户确认与 A 优先

页面持续停留 → 询问“需要我的帮助吗？”→ 按 B 录音描述目标 → 模型提出具体的一步点击 → 弹窗询问 → 用户再次按 B 说“对” → 重新截图核对页面、控件、任务和提示 ID → 执行一次 → 检查结果后停止。

“需要帮助”的回答不等于同意后续所有点击；模型输出中的“已确认”“立即执行”也不构成用户确认。只有本次 ACTION 提示下、B 来源、正确 task/prompt、未超时的明确确认有效。付款、发送、删除和授权等敏感操作仍交给用户手动处理。

A 开始元信息到达手机时，清理 B 的旧提示、录音识别、请求、待执行动作与旧回调；A 结束不会恢复旧 B。已交给系统的手势无法撤回。仍需固件按 0.7.2 协议携带正确 B 的 `prompt_id`、`task_id`、`source_button` 和 `purpose`，详见原版接入说明。

## 已完成验证与尚未验证

- Android 调试版、正式构建和 Lint 通过；Lint 为 0 errors、33 warnings。
- 121 项 JVM 测试通过，包含 B 状态机、响应解析、未确认不点击、重复确认、A 抢占和过期页面拒绝执行。
- 真实历史截图为 1080×2340：在本机重新编码为标准 JPEG 后，经本地 HTTP 代理和模拟提供商完成单图、双图传输，解码字节一致；未上传此图到外部服务。另通过鉴权、非法图片/工具/模型/流式参数拒绝、超大输入与错误脱敏测试。
- 没有配置硅基流动 Key，故尚未验证实际模型返回与画面理解；没有连接 Mate30 和 ESP32，故尚未验证真机无障碍截图、B 录音与点击整链路。模拟返回格式测试不能替代这些验证。

安装 `语音生活助手_0.7.3_调试版.apk`，填写代理地址、模型名和代理令牌，勾选 B 页面帮助与 B Agent，开启无障碍并同意本次截图分析后联调。先用无个人信息的普通页面验证，确保具体点击建议出现后，未再次按 B 确认就不会执行；确认一次只点击一次；再验证 A 抢占后旧 B 提示不生效。

调试版保留显式开启的局域网 IPv4 HTTP；正式构建仍强制 HTTPS。正式构建文件未签名，不能直接安装；保持既有发布签名流程。
