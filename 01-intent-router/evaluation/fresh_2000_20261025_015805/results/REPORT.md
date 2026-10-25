# 中文意图路由：2000 条合成输入测试

测试时间：2026-10-25T01:58:13.278278+08:00

正确 **1869 / 2000**，准确率 **93.45%**，HTTP 错误 **0**。

服务版本：hybrid-v2.1；语义回退阈值：0.45；分差：0.06。明确任务和否定、混合任务先按结构判断，剩余表达使用 BGE 相似度。

| 类别 | 条数 | 正确 | 召回率 | 精确率 |
| --- | ---: | ---: | ---: | ---: |
| vision | 700 | 682 | 97.43% | 96.33% |
| phone_action | 700 | 659 | 94.14% | 93.08% |
| clarify | 600 | 528 | 88.00% | 90.41% |

混淆矩阵（行是期望，列是实际输出）：

| 期望 / 实际 | vision | phone_action | clarify | HTTP 错误 |
| --- | ---: | ---: | ---: | ---: |
| vision | 682 | 0 | 18 | 0 |
| phone_action | 3 | 659 | 38 | 0 |
| clarify | 23 | 49 | 528 | 0 |

需要澄清的输入被错误分发到模块：72 条。实际返回 clarify 比例：29.20%。

错误原因：{"small_margin": 33, "explicit_phone_task": 42, "semantic_match": 22, "mixed_module_request": 20, "explicit_visual_text_task": 11, "matches_clarification_examples": 3}

本机逐条 HTTP 延迟：均值 9.0 ms，中位数 8.9 ms，P95 10.6 ms；总用时 18.4 s。含接口开销，不含服务启动与下载。

错误示例（每类最多 3 条）：

| 输入 | 期望 | 实际 |
| --- | --- | --- |
| 看一下展馆入口的参观须知，把读到的字告诉我 | vision | clarify |
| 读取桌边的折叠餐牌上的文字，随后用微信发给小夏 | clarify | phone_action |
| 把展馆入口的参观须知的字读出来，然后通过QQ告诉陈老师 | clarify | phone_action |
| 帮我把酷我音乐切到下一首 | phone_action | clarify |
| 把桌边的折叠餐牌的字读出来，然后通过QQ告诉阿哲 | clarify | phone_action |
| 看一下门边的展厅导览牌，把读到的字告诉我 | vision | clarify |
| 在手机日历里添加下周日下午五点的读书提醒 | phone_action | clarify |
| 帮我把番茄畅听切到下一首 | phone_action | clarify |
| 我想听清展馆入口的参观须知写的内容，帮我念一遍 | vision | clarify |

合成测试，不能代表真实语音识别或生产准确率。数据生成方式、去重及冻结信息见对应数据集的 manifest。原2000条与已查看结果的120条均属于已见数据；新生成数据若用于后续调参，也不再属于未见测试。模板和语义相关性会限制代表性。

原始输入与期望保留在 summary.json 的 dataset 路径；results.jsonl 保存逐条输出，errors.jsonl 保存全部错误。模型得分是相似度，不是概率。结构判断结果可能与相似度最高类别不同，以 decision_source 和 reason 为准。
