# 中文意图路由：2,000 条合成输入测试

测试时间：2026-10-25T01:34:52.104891+08:00

总数 **2000**，正确 **750**，错误 **1250**，准确率 **37.50%**。

使用本地 BGE 中文模型，逐条调用已部署的 POST /route；期望标签预先生成，未根据预测改标签或调参。阈值 0.75，分差阈值 0.08。

| 类别 | 条数 | 正确 | 召回率 | 精确率 |
| --- | ---: | ---: | ---: | ---: |
| vision | 700 | 74 | 10.57% | 92.50% |
| phone_action | 700 | 98 | 14.00% | 85.96% |
| clarify | 600 | 578 | 96.33% | 32.00% |

混淆矩阵（行是期望，列是实际输出）：

| 期望 / 实际 | vision | phone_action | clarify | HTTP 错误 |
| --- | ---: | ---: | ---: | ---: |
| vision | 74 | 0 | 626 | 0 |
| phone_action | 0 | 98 | 602 | 0 |
| clarify | 6 | 16 | 578 | 0 |

实际返回 clarify 的比例：90.30%。错误原因计数：{"low_similarity": 1017, "matches_clarification_examples": 207, "semantic_match": 22, "small_margin": 4}。

low_similarity 表示最佳示例相似度未达到 0.75；matches_clarification_examples 表示澄清类示例得分最高；small_margin 表示类别分差不足；semantic_match 表示通过阈值却选错类别。当前配置对明确任务过度澄清，尚不适合直接作为业务分发依据。

| 子类 | 正确 / 总数 | 准确率 |
| --- | ---: | ---: |
| clarify_ambiguous | 120 / 120 | 100.00% |
| clarify_mixed | 120 / 120 | 100.00% |
| clarify_negation | 98 / 120 | 81.67% |
| clarify_out_of_scope | 120 / 120 | 100.00% |
| clarify_screen_reading | 120 / 120 | 100.00% |
| phone_launch | 5 / 140 | 3.57% |
| phone_message | 0 / 140 | 0.00% |
| phone_settings | 8 / 140 | 5.71% |
| phone_tools | 57 / 140 | 40.71% |
| phone_ui | 28 / 140 | 20.00% |
| visual_camera_context | 15 / 100 | 15.00% |
| visual_colloquial | 1 / 100 | 1.00% |
| visual_field | 20 / 200 | 10.00% |
| visual_read | 38 / 300 | 12.67% |

本机逐条 HTTP 延迟：均值 8.7 ms，中位数 8.5 ms，P95 10.3 ms；总用时 17.7 s。不含模型下载和服务启动。

错误示例（每个子类最多 2 条）：

| 输入 | 期望 | 实际 |
| --- | --- | --- |
| 把手机移动数据关闭 | phone_action | clarify |
| 摄像头前这份购物小票上写的商店名称是什么 | vision | clarify |
| 用微信给小李发消息说记得带雨伞 | phone_action | clarify |
| 我要用美团，替我打开它 | phone_action | clarify |
| 进入系统设置的个人热点页面 | phone_action | clarify |
| 识别一下摄像头前这份手里的贺卡上写了什么 | vision | clarify |
| 替我找到并点开当前页面的分享 | phone_action | clarify |
| 我要用时钟，替我打开它 | phone_action | clarify |
| 打开短信，给小刘发送我已经到家了 | phone_action | clarify |
| 请提取摄像头前这份手里的贺卡里的文字内容 | vision | clarify |
| 替我找到并点开当前页面的下一步 | phone_action | clarify |
| 麻烦把眼前商品价签那几个字读给我 | vision | clarify |
| 添加一个下周一上午八点半的系统提醒 | phone_action | clarify |
| 请在备忘录里记下后天上午六点开会 | phone_action | clarify |
| 那个购物小票我看不清，你给念念 | vision | clarify |
| 镜头里这份商品标签上写的生产日期是什么 | vision | clarify |
| 现在拍到的是购物小票，帮忙提取文本 | vision | clarify |
| 不要返回桌面 | clarify | phone_action |
| 请直接读取当前摄像头画面中商店招牌的文字 | vision | clarify |
| 别替我滑动手机屏幕 | clarify | phone_action |

说明：数据是明确标注的模板组合合成测试，不是真实用户记录。无原始示例的完全重复，但有语义相近表达及模板相关性。本结果只反映这批合成数据，不能代表实际语音识别后的准确率。clarify 按当前能力定义包含否定、混合指令、无关输入和手机屏幕读字。

文件：test_inputs_2000.jsonl 为输入及期望；results_2000.jsonl 包含每条实际输出、得分及是否正确；errors.jsonl 保存全部错误；summary.json 为机器可读汇总。
