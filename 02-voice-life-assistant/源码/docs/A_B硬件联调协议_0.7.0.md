# A/B 硬件联调协议 0.7.0

配套 Android 包：`cn.seniorvoice.demo`，versionCode 11。目标板为 ESP32-S3-Korvo-2 V3；此文规定手机通信，不规定 GPIO、I²C 或舵机接线。固件未提供，不能用手机 APK 替代固件升级。

## BLE 传输约定

沿用手机设置中的 GATT Service / Notify / Write UUID。手机订阅 Notify，固件接收 Write（须支持带响应 WRITE）。默认 Nordic UART：Service `6e400001-b5a3-f393-e0a9-e50e24dcca9e`，Notify `6e400003-b5a3-f393-e0a9-e50e24dcca9e`，Write `6e400002-b5a3-f393-e0a9-e50e24dcca9e`。

JSON 用 UTF-8，末尾换行；BLE 分片可拆开 JSON，由接收端按流重组。不要交错两份 WAV，也不要在一份裸 WAV 字节中插入 JSON。手机回传同样是分片 JSON 行。

每次启动固件生成不同 `session_id`；每次录音生成不同 `event_id`、A `task_id`。标识符限定 `[A-Za-z0-9._:-]{1,96}`。B 的 task_id 和 prompt_id 必须原样使用手机刚发的值，不能由固件猜测。

## A：录音开始立即通知手机

按键开始录音时先发，不能等到 WAV 上传才通知：

```json
{"v":2,"type":"button_start","id":"rec-a-001","data":{"session_id":"boot-unique-001","task_id":"a-001","source_button":"A","purpose":"command"}}
```

手机校验成功后立即抢占 B，回 `ack`，其中 `event_id=rec-a-001`、`status=ready_for_audio`。此 ACK 代表元信息已接收，不代表录音已接收或动作已完成。

录音结束、修正 RIFF 文件长度后，发送同一录音编号的最终元信息（示例 size 仅为示例，必须填真实字节数）：

```json
{"v":2,"type":"recording_meta","id":"rec-a-001","data":{"session_id":"boot-unique-001","task_id":"a-001","source_button":"A","purpose":"command","format":"wav","size":96044}}
```

等同 ID 的 `ready_for_audio`，然后从 `RIFF` 第一个字节开始有序发送完整 WAV。推荐 16 kHz、16 bit、单声道整数 PCM，2～8 秒；格式上最多 10 MiB / 180 秒，但本任务等待录音约 90 秒，不能按最长文件时长录满。不得使用假 WAV 后缀、错误长度或丢包。建议 MTU 允许范围内分包并限速。

按键单击/双击与录音结束手势由板端实现，手机不解释电平或按键次数。本协议只要求每次实际开始录音时上报来源，结束后传完整 WAV。

完整收到后回 `audio_received`，随后离线转写。A 结果含原录音 `event_id`、`task_id`、`status`、`reply`。打开应用和拨号的成功仅表示发出系统请求，不代表通话接通。错误和低置信度停止，不猜测联系人。

## B：先收到询问，再允许回答

手机在本地同页停留检测和 `/vision/analyze` 云端判断后发：

```json
{"v":2,"type":"assist_prompt","prompt_id":"550e8400-e29b-41d4-a716-446655440000","task_id":"b-550e8400-e29b-41d4-a716-446655440000","question":"需要我的帮助吗？"}
```

用户按 B 开始录音时发：

```json
{"v":2,"type":"button_start","id":"rec-b-001","data":{"session_id":"boot-unique-001","task_id":"b-550e8400-e29b-41d4-a716-446655440000","source_button":"B","purpose":"assist_reply","prompt_id":"550e8400-e29b-41d4-a716-446655440000"}}
```

结束后同 ID 再发 `recording_meta`，保留 task、来源、purpose、prompt，加上 `format: wav` 和真实 `size`，等 `ready_for_audio` 后传 WAV。未收到 ACK / 收到 rejected 时不传本次 WAV。对重复元信息只可在同一份 WAV 尚未开始传输前更新。

- “不需要/不用/取消”等完整拒绝语结束本次帮助。
- “需要/好的”等简短同意会生成**新 prompt_id 和 task_id**，问题为“你想完成什么？”。下一段 B 录音用新值。
- 具体目标结合**当前手机截图**调用 `/analyze`，只显示 `message` 建议，回 `result`，`status=assist_advice`；不把返回 action 自动执行，也不请求眼镜相机。

询问等待约 45 秒，录音等待约 90 秒；超时、页面切换/滚动、锁屏、配置修改、断线、A 启动都会使原 B 任务失效。手机发 `assist_cancel`（prompt_id，A 抢占时 reason 为 a_priority），固件应丢弃对应 B 录音和待发送数据，清空旧 prompt。A 运行中按 B 不创建可执行任务；收到新的 assist_prompt 才能再次回答。

若旧 B WAV 已开始发送，必须先完成这一份流或重连清空传输，再发送 A 元信息，不能插入 JSON 导致文件损坏。要做到录音开始即 A 抢占，建议在板端避免长时间占用裸 WAV 流，或采用下面的 JSON 分块方式，在完整消息边界发送 A button_start。

## 可选 JSON 音频分块

仍须先发送并获得元信息 ACK；用相同 `id` 发 `audio_start`，data 含 `format:wav`、`size`；依次发 `audio_chunk`（data.index 从 0 连续递增，data.base64 为该块），最后 `audio_end`（data.chunks 为块数）。沿用既有 BleAudioTransfer 校验。A 元信息到达会放弃旧 JSON 音频传输；固件停止旧块，再发新录音，不能继续交错。

## A 实物照片：严格关联原问题

手机针对实物提问发：

```json
{"v":2,"type":"capture","source_button":"A","task_id":"a-001","frame_id":"a-001-frame-1","question":"这是什么？"}
```

固件拍摄**本次** JPEG，并上传到配置视觉服务的可读取资源，回：

```json
{"v":2,"type":"image_ready","id":"image-a-001","data":{"task_id":"a-001","frame_id":"a-001-frame-1","url":"https://ai-glasses-backend-7bj3.onrender.com/由后端提供的本张JPEG路径"}}
```

URL 为占位示例，实际必须是可 GET 的真实 JPEG（最多 2 MiB），同一 HTTPS 主机和端口，不跳转，绑定本张图且在手机读完前有效。不接受把 `/camera/latest` 这样的可变化资源当作任务关联依据。手机按 task/frame 拒绝旧照片，再把 image + 原始问题 intent 交给 `/analyze`。

目前部署的 `/upload` 响应未给出可验证的稳定图片 URL 契约；`/camera/frame` 只是预览上传，不能单凭预览成功认定问答完成。需后端和板端约定稳定资源地址；此 APK 只支持以上 URL 回传方式，不支持 BLE 原始 JPEG。

## 取消、去重及兼容

取消 A：`{"v":2,"type":"cancel","id":"cancel-1","data":{"task_id":"a-001"}}`。取消 B：data 改为当前 `prompt_id`。不属于当前任务的取消消息忽略。收到旧录音、旧 ASR、旧网络结果均不执行；识别前持久保存去重标记（peer + session_id + event_id），同一录音不得重发造成重复拨号。若要重新尝试，用户重新录音并生成新 ID。

v1 command、裸 WAV 保留 A 兼容；旧裸 WAV 没有按键开始事件，只能在收到 WAV 头时抢占，**不能实现 B，也不能保证按下 A 那一刻抢占**。一条连接上收到过带来源的录音元信息后，后续不带元信息的 WAV 会丢弃。旧 COMPLETE 不再直接执行 AI 单步。

断线清理全部任务；重连需重新发任务、重新等待询问。固件不能把重连前缓存的 B 回答重新发送。

## 后端接口边界

文档：https://ai-glasses-backend-7bj3.onrender.com/docs 。已只读核对 health 和 OpenAPI；未部署或修改服务器。

| 用途 | 请求 | 手机处理 |
| --- | --- | --- |
| B 停留场景分析 | POST /vision/analyze，multipart image | 只接受 status=ok、mode=cloud 且有实际场景和 suggested_assistance |
| B 具体目标 / A 实物问题 | POST /analyze，multipart image + intent | 显示 message，不将 action 自动执行 |
| 文字事件记录 | 当前服务没有约定接口 | 本机 SQLite 保留，原可选同步配置独立 |

当前 /analyze 文档主要返回 open_app/unknown 动作与 message，不是完整手机操作 agent 或通用实物问答契约。B 的自动连续操作、完整实物问答和板端照片稳定 URL 均需进一步后端/固件联调，不能仅凭 APK 构建通过视为完成。
