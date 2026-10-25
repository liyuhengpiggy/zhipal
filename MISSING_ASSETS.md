# 未纳入仓库的文件清单

原四个交付包中以下文件未提交，原因分别为「超过 GitHub 单文件 100 MB 硬限制」「可重新生成」或「二进制产物非源码」。

> 本仓库根目录另行提供了 `Zhipal-0.9.5-Cover.apk`（84.7 MB，versionName `0.9.5-hardware-b`），
> 它是独立于四个交付包的后续构建，与下表中的安装包不是同一版本。

| 原文件 | 大小 | 原因 | 获取方式 |
|---|---|---|---|
| `02-voice-life-assistant/待合并视觉后端/medicine-vision-apk-integration.zip` | 100.7 MB | 超过 GitHub 单文件 100 MB 限制 | 向交付方索取原包 |
| `01-intent-router/models/bge-small-zh-v1.5/model.safetensors` | 91.4 MB | 模型权重，公开可下载 | `cd 01-intent-router && python download_model.py` |
| `02-voice-life-assistant/安装包/语音生活助手_0.8.0_调试版.apk` | 81.3 MB | 安装包 | 由 `02-voice-life-assistant/源码` 构建 |
| `04-assistant-merged/04_APK/01_既有1.3性能优化版.apk` | 5.6 MB | 安装包 | 同上 |
| `04-assistant-merged/04_APK/02_模块化集成示例_需重新授权.apk` | 5.6 MB | 安装包（需重新授权） | 同上 |
| `04-assistant-merged/04_APK/测试工具/模块化示例设备测试.apk` | 0.9 MB | 测试工具 | 同上 |
| `04-assistant-merged/04_APK/03_二进制宿主混淆验证_未配置模型.apk` | 0.4 MB | 验证产物 | 同上 |

如需完整原始包（含上述所有文件），请向交付方索取对应的四个 ZIP 原件。
