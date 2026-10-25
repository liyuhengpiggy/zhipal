# 新增2000条合成测试汇总

当前服务 hybrid-v2.1；标签在预测前固定。本轮未修改模型权重、规则、路由示例或阈值，冻结源码哈希及数据哈希均验证一致。

正确1869/2000，准确率93.45%，错误131条，HTTP错误0。需澄清的600条中72条被误分发到模块（12.00%）。

| 场景 | 样本数 | 错误数 | 准确率 |
| --- | ---: | ---: | ---: |
| fresh_cancelled | 100 | 0 | 100.00% |
| fresh_cross_module | 150 | 31 | 79.33% |
| fresh_phone_apps | 150 | 0 | 100.00% |
| fresh_phone_media | 100 | 19 | 81.00% |
| fresh_phone_payload | 100 | 0 | 100.00% |
| fresh_phone_settings | 100 | 0 | 100.00% |
| fresh_phone_tools | 100 | 20 | 80.00% |
| fresh_phone_ui | 150 | 2 | 98.67% |
| fresh_screen_ocr | 150 | 35 | 76.67% |
| fresh_unrelated | 100 | 6 | 94.00% |
| fresh_vague | 100 | 0 | 100.00% |
| fresh_visual_context | 150 | 0 | 100.00% |
| fresh_visual_fields | 150 | 5 | 96.67% |
| fresh_visual_spoken | 150 | 5 | 96.67% |
| fresh_visual_transcription | 250 | 8 | 96.80% |

该数据与历史测试、校准、验证及路由示例经NFKC、大小写、标点和空白归一化后零重复；使用新模板和场景，但仍为模板合成，并非真实用户语音或独立外部标注。新集93.45%与旧集99.95%的差异说明原回归集不能代表新表达的表现。

输入及预先固定的期望：inputs_2000.jsonl；去重来源及冻结哈希：dataset_manifest.json；逐条预测：results/results.jsonl；全部错误：results/errors.jsonl；混淆矩阵及延迟：results/REPORT.md。
