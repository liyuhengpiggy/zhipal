# B Agent 充值后实际模型测试（0.7.4）

测试日期：2026-10-24。结果：本轮三次实际请求全部成功，APK 同一套请求构造与响应解析器全部通过。

调用路径：本地运行项目的 B 云端代理代码 → `https://api.siliconflow.cn/v1/chat/completions`，实际返回模型为 `Qwen/Qwen3-VL-8B-Instruct`。API Key 仅通过临时进程环境变量提供，未写入 APK、源码或报告。此前账户余额不足的 HTTP 402 本轮未再出现。

| 检查 | 实际结果 | 总 token 数 |
| --- | --- | --- |
| 单图规划 | 从历史手机截图识别“检查 OCR 模型是否就绪”按钮，规划解析通过 | 2739 |
| 控件编号建议 | 返回 `{"action":"click","target_id":0}`，目标校验通过；测试控件对应中心为 `(540,257)` | 2785 |
| 双图反思 | 输入两张相同截图，返回 `Outcome: C`，正确判断没有可见变化，解析通过 | 5284 |

三次 `finish_reason` 均为 `stop`，合计 10808 tokens。终端最终结果为 `APP_PARSER_PASS: plan/action/reflection; candidate=(540,257); identical images=C; phone actions=0`。

原始返回保存在 `tmp/b-agent-live-recharged-response/plan.json`、`action.json`、`reflect.json`；复现脚本为 `scripts/b-model-live.mjs`。

## 验证范围

使用此前真实手机截图重新编码的标准 JPEG（1080×2340），以 Base64 `image_url` 输入。控件编号及位置是从该历史截图人工转录的测试数据，并非当前手机无障碍树。双图使用相同图片，仅验证“无可见变化”分支；模型解释中的界面细节不作为可靠事实或执行依据。

本次未连接或操作手机、ESP32，手机动作次数为 0，也未部署 Render 代理。因此结果证明当前账户可调用模型，且本轮截图输入、控件编号输出与 B Agent 解析兼容；不代表真机全流程已完成。用户确认与 A 优先取消 B 的机制未改动。

本轮无需修改 APK。后续仍需将代理部署并配置到 Render，再用 Mate30 验证截图、语音、动作确认及 A 抢占 B 的实际流程。
