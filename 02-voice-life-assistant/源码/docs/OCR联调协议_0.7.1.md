# 0.7.1 OCR 联调协议补充

本文件补充《A_B硬件联调协议_0.7.0.md》。BLE UUID、JSON 换行分片、A/B 录音来源、prompt 绑定、取消、A 优先规则继续适用。新包 versionName=0.7.1、versionCode=12；没有 ESP32 固件源码，本次未修改 GPIO、摄像头或舵机代码。

## A 的问题路由

打开应用、拨打已保存联系人继续在手机处理。读文字、生产日期、有效期、批号、药名、厂家、包装用法用量等进入独立 OCR 服务。客户端将“读一读”“念一下”统一为服务支持的“读一下”；记录中保留原始用户提问。

其他实物问题保留旧的 task/frame 专属照片 URL 流程，仍须原后端支持真实问答，OCR 包不提供通用物体识别。B 继续使用原 Render 页面视觉接口和建议接口；当前只显示建议，由用户操作手机。

## 固件新增：按任务上传 JPEG

手机先向 OCR 服务创建任务，成功后发送：

```json
{"v":2,"type":"capture","source_button":"A","task_id":"a-001","vision_task_id":"v_32位唯一编号","frame_id":"f0","question":"生产日期是什么？","upload_url":"https://新OCR服务.onrender.com/api/v1/vision/tasks/v_32位唯一编号/frames/f0","content_type":"image/jpeg"}
```

`task_id` 是本次 A 硬件任务；`vision_task_id` 是手机生成的独立服务编号，不能擅自替换。`frame_id` 固定为 `f0`。该消息中不携带令牌：板端需预先配置 OCR HTTPS 地址和 Bearer 令牌，只向配置的服务上传，不任意向收到的其他主机发送凭证。

板端拍摄本次照片，用 Wi-Fi 上传：

```http
PUT /api/v1/vision/tasks/{vision_task_id}/frames/f0
Authorization: Bearer <板端配置的OCR令牌>
Content-Type: image/jpeg

<完整原始 JPEG 字节>
```

不能传 multipart、base64、屏幕截图或上一张缓存照片。最多 8 MiB、1600 万像素。手机建任务后才允许上传，不通过 `/camera/latest` 交换结果。上传响应 202 只代表接收流程结束，body 仍可能 `status:failed`；识别成功必须等待 `succeeded`。

板端可选发送上传通知（不需要照片 URL）：

```json
{"v":2,"type":"image_ready","id":"img-a-001","data":{"task_id":"a-001","vision_task_id":"v_32位唯一编号","frame_id":"f0"}}
```

手机核对三个编号后回 `polling_ocr` ACK；该 ACK 不代表识别成功。即使不发通知，手机也会轮询已创建的同一 OCR 任务。不新增 `vision_uploaded` 消息类型，避免与旧协议解析器不一致。

手机约每秒查询，轮询预算约 240 秒。新 A、取消、断线和配置修改会关闭旧请求并使旧回调失效；原服务没有任务取消 API，旧任务在服务器上仍可能运行，但不会覆盖新任务。固件也须停止旧 capture/待传照片，不能把旧照片上传到新编号。

## 手机配置与无板端测试

在首页的 A/B 设置页中，B 地址保持原 Render；OCR 填新服务根地址和独立令牌。OCR 默认空，避免误把原 `/analyze` 当作异步 OCR 接口。正式使用 HTTPS；局域网 HTTP 仅在显式勾选后允许私网 IPv4，数据和令牌在该链路上明文传输。

先点“检查 OCR 模型是否就绪”，再输入问题并选择 JPEG 测试。选图测试作为新 A 任务，清理 B，并由手机自己上传照片。选图期间若启动了其他 A，原选择失效，需要重新选图。系统重建设置页后也需要重选。

断网或返回丢失后可点“按原编号查询上次 OCR 任务”，只 GET 上次编号，不重新传照片、不新建远端任务。必须使用同一 OCR 服务根地址；令牌可更新。若原请求没有到达服务器会收到 404，若在等待照片则继续显示等待；这种情况需要重新发起拍照任务。重拍必须用新编号。

## 结果核对与能力边界

手机核对 task_id、问题、expected_frames=1、f0、顶层和 result 的答案/原文、两处 SHA-256；手机选图时还核对上传 bytes 的 SHA-256。字段候选必须有本图证据且标记 review_required。字段候选显示核对提示，冲突显示多个候选且不自动选择，未找到提示重拍，不支持的问题明确说明。

结果和本机记录保留 answer_type、answer、evidence_text、服务任务编号、问题和照片 hash。BLE reply 为兼容现有协议最多 1800 字符，较长原文可在手机显示和本机记录查看。OCR 输出不会触发拨号或自动点击。

模型在服务器运行，140 MB 权重不放进 APK。电脑构建和协议测试不代表真实药盒准确率，也不代表华为手机/ESP32 已完成实机验收。部署步骤见《发给后端同学_Render部署指令.md》。
