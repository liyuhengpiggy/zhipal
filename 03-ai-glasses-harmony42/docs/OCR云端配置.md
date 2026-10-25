# 独立 OCR 云端配置（无密钥版）

交付包 `ocr-cloud/` 为当前独立服务的云端相关代码快照，不是新建 OCR 实例的授权，也不改变现有部署。
腾讯云引擎不需要 Paddle 模型权重。原 Paddle 路径仍在源码中，但本包不带权重，不宣称可以离线直接运行。

Render Docker 部署设置 Root Directory 为 `ocr-cloud`，Dockerfile 路径为 `Dockerfile.cloud`；
端口从 Render 的 PORT 读取，启动入口 `python deploy_vision.py`，健康路径 `/health`。
如已有生产服务，先核对它的当前配置，不要覆盖密钥或误建同名实例。

环境变量由管理员通过 Render Environment 配置：

```dotenv
OCR_ENGINE=tencent
VISION_API_TOKEN=<单独生成的OCR服务访问令牌>
TENCENTCLOUD_SECRET_ID=<腾讯云SecretId>
TENCENTCLOUD_SECRET_KEY=<腾讯云SecretKey>
TENCENTCLOUD_OCR_REGION=ap-shanghai
VISION_DATA_DIR=/var/data/medicine-vision
VISION_INFERENCE_TIMEOUT=120
VISION_STARTUP_TIMEOUT=180
VISION_WAITING_TIMEOUT=600
```

region、API 权限和计费请以实际账号支持为准，不能仅凭示例保证可用。
`VISION_DATA_DIR` 若指向持久盘，须实际挂载对应目录；免费实例或临时磁盘不保证重启后任务可恢复。
独立 OCR 与照片后端变量不共享。照片后端的 `ASSISTANT_OCR_TOKEN` 应与这里的 `VISION_API_TOKEN` 匹配。
不要把真实凭据复制进本文、GitHub、APK 或聊天。

部署检查：先 GET /health /ready，确认 ready；用真实照片创建一个任务并 PUT 单张 JPEG，再查询终态。
任务创建成功不等于识别成功；检查 answer、evidence_text、result.fields 与原图，记录误字和低质量提示。
原服务可能保留照片/任务数据库，必须由管理员核实清理策略和第三方 OCR 数据留存规则。
