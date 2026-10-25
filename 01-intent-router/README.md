# 中文指令 → 视觉 / 手机操作

完整项目说明请先阅读 [项目综合介绍](项目综合介绍.md)，包含架构、部署、接口、历史与最新测试结论。当前优化版额外冻结 2000 条测试的一级路由为 1729/2000（86.45%），视觉二级分类为 618/700（88.29%）。

视觉一级路由命中后，`vision_task` 进一步返回 `read_text`（读取文字）、`recognize_object`（识别物体）或 `recognize_environment`（识别周围环境）；无法可靠区分时为 `null`。非 `vision` 请求的 `vision_task` 始终为 `null`。

基于开源 [Sentence Transformers](https://github.com/huggingface/sentence-transformers) 和 [BAAI/bge-small-zh-v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5)，在后端实现中文任务路由。当前版本为 `hybrid-v2.3`，结合任务结构规则和语义相似度；接口只分类，不执行手机操作。

语音识别结果 → `POST /route` → 按 `route` 调用现有模块。

| route | 含义 | 示例 |
| --- | --- | --- |
| vision | 摄像头前实物的文字提取 | 帮我念一下这张纸上的字 |
| phone_action | 手机应用和界面操作 | 打开微信、开启相机 |
| clarify | 需要澄清或超出当前能力 | 帮我看一下、不要打开微信、先读纸上号码再拨号 |

当前视觉模块限定为摄像头 OCR，读取手机屏幕文字返回 clarify。混合任务没有自动拆解能力。消息正文和搜索、输入的内容不应改变外层操作意图，例如“发微信说不要等我”属于手机操作。

## 本地运行

依赖已安装在 `.venv`，模型完整保存在 `models/bge-small-zh-v1.5/`。模型来源及哈希见 `model-manifest.json`，已安装版本见 `requirements.lock.txt`。

```powershell
.\start-service.ps1
```

脚本隐藏窗口启动本地 CPU 服务，日志写入 `logs/`，记录启动进程至 `service.pid`，不注册开机启动。已存在服务时脚本不会重启；修改分类代码或配置后需要重启服务。

也可前台启动：

```powershell
.\.venv\Scripts\python.exe server.py
```

- 健康检查：http://127.0.0.1:8010/health
- 接口文档：http://127.0.0.1:8010/docs
- 分类接口：POST http://127.0.0.1:8010/route

请求正文：

```json
{"text":"帮我读一下摄像头前这张纸上的字"}
```

返回保留 `route`、`needs_clarification`、`reason`、`scores`、`margin`，新增 `policy_version` 和 `decision_source`（structure / semantic）。scores 是余弦相似度，不是概率；margin 是相似度前两名分差。规则优先时，最终 route 可以与最高相似度类别不同。

手机将原始文字交给对应现有模块；手机操作模块自行解析动作和参数。本地 127.0.0.1 只在电脑本机可用，手机接入需通过现有后端或受控网络访问。这是后端实现，不是直接运行在 Android 上的 Python 模型。

## 修改内容与配置

`intent_policy.py` 识别明确读字、手机操作、否定、混合任务、消息/输入内容边界和不支持的屏幕 OCR；不能确认结构时由 `router.py` 使用 BGE 与 `routes.json` 中各类示例的最高相似度回退判断。

`router_config.json` 保存语义阈值 0.45、分差 0.06，以及 60 条校准样本的哈希和选参结果。配置来自合成校准数据；不是实际用户数据上的最优值。可用 ROUTER_THRESHOLD / ROUTER_MARGIN 覆盖。调整阈值只影响语义回退，规则优先。模型及示例向量在启动时加载一次，server.py 使用本地模型、CPU、4 个推理线程离线运行。

输入必须是非空文字，最多 200 字符；超过 token 上限明确报错。规则是有限模式匹配，无法保证覆盖所有口语、错别字、否定和多步表达。

## 测试与评估

```powershell
.\.venv\Scripts\python.exe -X utf8 -m unittest -v test_router.py test_policy.py test_api.py
.\.venv\Scripts\python.exe -X utf8 evaluate_v2.py --output-dir evaluation\current_regression
.\.venv\Scripts\python.exe -X utf8 evaluate_v2.py --input evaluation\validation.jsonl --output-dir evaluation\current_validation
```

15 项测试覆盖分数决策、结构边界及真实模型 API 生命周期。评估通过实际 HTTP 接口逐条核对，保存输入、期望、预测、得分、原因、延迟、错误和源文件哈希。`run_evaluation.py` 作为兼容入口写入 current_regression，不再覆盖旧基线。

原始 2000 条是模板合成数据（700 视觉、700 手机操作、600 澄清），已用于错误分析，只能报告回归准确率。标签和输入没有更改。原版正确 750/2000（37.50%），结果及原始源码保留在 `evaluation/baseline_v1/`。

新增数据包含 60 条校准和 120 条手写合成验证。v2 首次验证正确 117/120（97.50%）；查看错误后这批数据已成为已见验证集，v2.1 的结果应称复测，不再作为独立未见测试。所有数据均明确标注 synthetic，不是真实用户记录。

最终对比、剩余错误与限制见 `evaluation/IMPROVEMENT_REPORT.md`。生产准确率需要另外收集真实语音转写，并冻结全新的独立测试集。

## 重新安装

Python 3.10+。新环境可执行：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

本地模型未提供时，首次运行需要下载模型。框架和模型许可证以上游 LICENSE、模型卡为准。
