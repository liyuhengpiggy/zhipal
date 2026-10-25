# 0.7.8 BLE PCM 语音接入补充

原 WAV、v1/en 文字协议仍保留。此版本新增**有元信息的 JSON 分块 PCM**接收并重建标准 WAV，交给现有手机 Vosk 本地 ASR。由于没有拿到最新固件的二进制 PCM 帧定义，不能宣称已经适配任意裸 PCM 流。固件若发送其他二进制包格式，必须提供真实帧定义/抓包再适配。

## 推荐规格与 BLE 通道

推荐 16 kHz、单声道、16-bit 小端有符号整数 PCM，录音约 2–8 秒。不要传浮点数、MP3、AAC 或 I²S 32-bit 容器而标为 16-bit。

默认 GATT 沿用设置：

| 用途 | UUID |
| --- | --- |
| Service | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |
| Notify（板→手机） | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |
| Write（手机→板） | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` |

设备广播名由固件设置，项目中没有证据能保证唯一设备名；按同学提供的 MAC/广播名与真实 UUID 找设备。BLE 连接状态需显示可双向收发，不以系统蓝牙配对代替 GATT 订阅。

JSON 使用 UTF-8，每条末尾加 `\n`，一条可拆为多个通知；接收端按流重组，不把 20 字节通知当完整消息。每条消息完整结束后才能插入另一条，禁止两路音频交错。

## A 录音示例

每次开机生成唯一 session_id，每次新录音 event_id、A task_id 唯一，标识符 `[A-Za-z0-9._:-]{1,96}`。按 A 开始录音先发：

```json
{"v":2,"type":"button_start","id":"rec-a-001","data":{"session_id":"boot-001","task_id":"a-001","source_button":"A","purpose":"command","format":"pcm_s16le"}}
```

该事件使 A 立即抢占 B。录完后发最终原始 PCM 字节数；此处示例 32000 是 1 秒，仅用于说明：

```json
{"v":2,"type":"recording_meta","id":"rec-a-001","data":{"session_id":"boot-001","task_id":"a-001","source_button":"A","purpose":"command","format":"pcm_s16le","size":32000}}
```

等待 `ack` 的 event_id 匹配且 status=`ready_for_audio`，再发相同 id：

```json
{"v":2,"type":"audio_start","id":"rec-a-001","data":{"format":"pcm_s16le","size":32000,"sample_rate":16000,"channels":1,"bits_per_sample":16}}
{"v":2,"type":"audio_chunk","id":"rec-a-001","data":{"index":0,"base64":"本块PCM字节的Base64，占位非有效音频"}}
```

index 从 0 连续递增，无缺包/重复/乱序，最后：

```json
{"v":2,"type":"audio_end","id":"rec-a-001","data":{"chunks":32}}
```

chunks 必须等于实际块数，不是固定 32。建议每个 JSON 块放约 512–2048 字节 PCM，完整 JSON 再按协商的 MTU 分成顺序通知。手机只对元信息回 `ready_for_audio`、对 `audio_start` 回 `audio_started`，收齐整段后回 `audio_received`；**没有逐 `audio_chunk` 的 ACK，不要在每块后等待手机 ACK**。通知速度应按 ESP32 BLE 协议栈的拥塞/发送完成状态控制，不能靠每包固定长延时限速。size 是纯 PCM 总长度，**不含 44 字节 WAV 头**。手机自动补 RIFF/WAVE PCM 头，输出大小=size+44；audio_start/chunk/end 的 id 必须与录音元信息完全相同。

手机页面分别显示“硬件录音中 → 接收录音（已收/总量、平均速率）→ 校验文件 → 离线转文字 → 执行/视觉请求 → 完成或失败”。接收进度约每 250 ms 刷新，最后一次必更新。JSON PCM 的计数与速率按解码后的 PCM 字节计算，不含 Base64/JSON 额外开销；裸 WAV 按实际 WAV 文件字节计算。`audio_received` 表示传输收齐，最终是否识别/执行成功以 `result` 为准。

APK 会请求较大的 MTU 与高连接优先级，但最终 MTU/连接间隔由手机和固件共同决定；固件若仍固定 20 字节通知或固定长间隔，单改 APK 不能保证提速。已有固件支持裸 WAV 时，可继续发送原二进制 WAV，避免 Base64 约 33% 的体积增长。首次离线模型复制/加载与 BLE 接收分开显示，后台连接开启后提前准备，后续复用模型。

手机允许 8000–48000 Hz、1/2 声道、16-bit，长度按声道×2 字节对齐；重建文件不超过 10 MiB、PCM 不超过 180 秒。实际业务等待远短于最大音频时长，不要录满。A 录音等待约 90 秒，传输进行中 30 秒无进度会终止。

## B 录音与确认

B 必须先收到手机最新的 `assist_prompt`，回传其**原样 task_id 和 prompt_id**：

```json
{"v":2,"type":"recording_meta","id":"rec-b-002","data":{"session_id":"boot-001","task_id":"b-手机给出的prompt","source_button":"B","purpose":"assist_reply","prompt_id":"手机给出的prompt","format":"pcm_s16le","size":64000}}
```

上面中文为占位，要替换为合法实际 ID。随后同 A 发 audio_start/chunk/end；不能按识别文字猜是 A 还是 B。每次 B 录音生成新的 event_id，使用最新问题 ID；同意帮助之后询问目标、提出点击之后询问确认，都可能换 prompt，固件不能缓存旧值。

B Agent 提议一个点击后，用户再次按 B 说“对”才可点击一次并验证结果；说“不需要”不执行，说“不是这个”不确认旧操作。A 开始、超时、断线、页面变化会清理旧 B。固件收到 `assist_cancel` 就停止对应旧 B 音频。A 运行期间的 B 元信息会拒绝，不中断 A 的朗读和有效 A 分块。

## 去重、错误和兼容

- 成功收齐才进入 ASR；不完整文件、格式/参数/长度不符、块号缺失会拒绝，不识别半截。
- 根据 BLE 对端 + session_id + event_id 持久去重，避免重发造成重复拨号。重试须用户重新录音、新 event_id；断线丢弃半帧、清理 A/B 状态。
- PCM 元信息不能后接裸 WAV；PCM 必须通过上述 JSON 分块。WAV 格式仍可用原 RIFF 二进制流或 JSON 分块，但不能在裸 WAV 中插 JSON。
- 旧无来源 WAV 和 v1/en 文字仅为 A 兼容，不满足“按硬件标签区分 A/B”，也不能替代本次 PCM 硬件验收。它们不根据文字内容猜按钮。
- 明确 A/B 的录音识别记录保留 session_id、task_id、event_id、prompt_id；A prompt_id 为 null，B 必须有效。结果回传与记录继续携带这些关联信息。

手机在设置开启“保持蓝牙后台连接”；华为应用启动管理允许后台活动，开启无障碍且开始本次 B 观察后，在其他 App 前台执行 B 测试。电脑测试已验证 PCM→WAV 参数/采样内容，但未接真实硬件，不保证固件当前字节流已符合本协议。
