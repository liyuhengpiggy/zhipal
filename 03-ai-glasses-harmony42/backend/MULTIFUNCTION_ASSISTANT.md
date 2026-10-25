# 多功能视觉助手

新增独立入口，不替换药品 OCR，不修改其 `fields`，不改变摄像头上传、预览、增强和旧识别接口。

## 使用

首页“多功能视觉助手”支持通用读文字、识别物品、描述场景。点击时仅截取内存中最近一张原图；超过5秒未更新时拒绝识别，不自动逐帧调用。镜像由硬件端处理，助手直接提交收到的原图，不再翻转或重新编码。

所有新接口需本网站 `VISION_API_TOKEN`（Bearer）：

- `GET /api/v1/assistant/capabilities`：配置状态，无密钥内容。
- `POST /api/v1/assistant/analyze`：`{"mode":"objects"}`，mode 可为 read_text / objects / scene；不接受 mirror 参数。
- `GET /api/v1/assistant/ocr/{task_id}`：查询助手 OCR 任务，原样返回上游结果，含 fields。

读文字使用现有独立 OCR 服务，设置 `ASSISTANT_OCR_TOKEN`，或页面填写该服务令牌（作为 `X-OCR-Token` 转发）。不要把云模型密钥当作访问令牌。可用 `ASSISTANT_OCR_BASE_URL` 配置可信 HTTPS OCR 服务，默认 medicine-vision-ocr.onrender.com；目标不能由浏览器指定。

识别物品、场景复用现有视觉接口配置：`VISION_QWEN_API_KEY`、可选 `VISION_QWEN_BASE_URL`、`VISION_QWEN_MODEL`、`VISION_QWEN_TIMEOUT`。未配置时明确报错，不影响原功能。

令牌不写入浏览器存储，不自动读取服务器本地测试 token 文件。照片会发送所选服务，可能产生费用，上游可能留存照片和结果。停止等待不等于取消已提交任务。结果需人工核对，不可据此决定用药或保证通行安全。

## 部署与回退

运行完整测试后，将 app/assistant_routes.py、app/main.py、app/index.html 一起发布到照片后端；无需修改 OCR 服务。不要上传 .env、config 下令牌或 artifacts。生产部署和真实模型测试尚需独立验证。

回退时撤销 main.py 中 assistant_router 的导入/注册和 index.html 新助手区及对应脚本，即可移除入口；无需改动摄像头与 OCR 旧代码。
