# Zhipal — 源码归档（四包合并）

本仓库把四个独立交付包合并为一棵目录树，便于外部协作者一次性获取全部源码、文档与联调资料。

> 归档日期：2026-10-25　｜　内容规模：660 个文件，约 216 MB（含 84.7 MB 安装包；已剔除超限二进制，见文末）

## 目录结构

| 目录 | 原交付包 | 内容 | 技术栈 |
|---|---|---|---|
| `01-intent-router/` | 中文指令路由项目 hybrid v2.3 | 中文指令意图路由服务、评测集与实验报告 | Python / FastAPI / 向量模型 |
| `02-voice-life-assistant/` | 语音生活助手 0.8.0 精简合并交接包 | Android 工程 + 离线语音模型 + 后端代理 + 交接文档 | Kotlin / Gradle / Node / Python |
| `03-ai-glasses-harmony42/` | ai-glasses-harmony42-integration-source | 智能眼镜后端、OCR 服务、Android 移动端、部署脚本 | Python / FastAPI / Android |
| `04-assistant-merged/` | 智能助手 完整合并交付包 | 模型调用层、Android 功能 SDK（AAR/JAR）、Maven 元数据、集成文档 | Java / Android |

根目录另附可直接安装的构建产物：

| 文件 | 包名 | versionName | 大小 | SHA256 |
|---|---|---|---|---|
| `Zhipal-0.9.5-Cover.apk` | `cn.seniorvoice.demo` | `0.9.5-hardware-b` | 84.7 MB | `fa4e37960585a2bebde82bbe703d1a6f26162667cf89ec47cac6701891fab774` |

> 注意：文件名标注为 `Cover`，但安装包内 `versionName` 实际为 `0.9.5-hardware-b`（硬件 B 通道构建）。
> 安装前请核对，避免与同名路演构建混淆。

## 各部分入口文档

- **意图路由**：`01-intent-router/README.md`、`01-intent-router/项目综合介绍.md`
- **语音助手**：`02-voice-life-assistant/00_先读我.md` → 再读 `源码/docs/项目技术交接与合并指南_0.8.0.md`
- **智能眼镜**：`03-ai-glasses-harmony42/backend/README.md`、`backend/LOCAL_DEPLOYMENT.md`
- **合并交付包**：`04-assistant-merged/00_开始阅读.md` → 再读 `06_技术文档/01_APK合并接入手册.md`

## 快速开始

### 意图路由（Python）

```bash
cd 01-intent-router
pip install -r requirements.txt
python download_model.py      # 拉取向量模型权重（仓库内未包含，见文末）
python app.py                 # 或 uvicorn server:app
```

### 语音助手 Android 工程

```bash
cd 02-voice-life-assistant/源码
./gradlew assembleDebug       # Windows: gradlew.bat assembleDebug
```

离线 ASR 模型（vosk-model-small-cn-0.22）已随仓库提供，位于 `app/src/main/assets/asr/`。
后端代理与 Python 服务见 `agent_backend/`、`b_agent_proxy/`，各自目录下均有 `.env.example`。

### 智能眼镜后端

```bash
cd 03-ai-glasses-harmony42/backend
pip install -r requirements.txt
python -m app
```

OCR 服务为独立进程，见 `OCR_INTEGRATION.md`；云/本地两套部署方式见 `DEPLOY_RENDER.md`、`LOCAL_DEPLOYMENT.md`。

### Android SDK 接入

`04-assistant-merged/03_Android功能SDK/` 提供 AAR/JAR 与本地 Maven 仓库（`maven/com/bagent/...`），
依赖配置与接入顺序见 `06_技术文档/01_APK合并接入手册.md`。

## 配置与密钥

仓库内所有配置均为 `.env.example` / `.example.json` 模板，**不含任何真实密钥或账号**。
使用前复制为 `.env` 并自行填写。请勿把填好的 `.env`、签名文件或 keystore 提交进仓库。

## 未包含的文件（体积超 GitHub 限制或可重新生成）

| 文件 | 大小 | 原因 | 获取方式 |
|---|---|---|---|
| `02-voice-life-assistant/待合并视觉后端/medicine-vision-apk-integration.zip` | 100.7 MB | 超过 GitHub 单文件 100 MB 硬限制 | 向交付方索取原包 |
| `01-intent-router/models/bge-small-zh-v1.5/model.safetensors` | 91.4 MB | 模型权重，可从公开源获取 | `python download_model.py` |
| 各版本 APK（5 个） | 0.4 ~ 81.3 MB | 二进制安装包，非源码 | 由源码自行构建，或向交付方索取 |

完整清单见 `.excluded_list.txt`。

## 说明

- `04-assistant-merged` 中 `01_原始模型源码/` 的嵌套 ZIP、`02_模型调用层` 的 AAR/JAR 均为原样保留的交付物。
- 第三方组件（vosk、bge、OCR 权重等）的许可证见各自目录下的 `LICENSE` / `09_来源与许可`。
- 未纳入文件的完整清单见 [`MISSING_ASSETS.md`](MISSING_ASSETS.md)。

