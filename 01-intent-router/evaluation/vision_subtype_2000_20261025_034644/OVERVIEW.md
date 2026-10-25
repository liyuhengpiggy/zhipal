# 二级视觉分类新增2000条测试汇总

当前服务：`hybrid-v2.2`。本轮生成全新的合成样本，视觉样本额外标注 `read_text`、`recognize_object`、`recognize_environment`；没有根据测试结果调参。

| 指标 | 结果 |
| --- | ---: |
| 一级路由准确率 | 1635/2000（81.75%） |
| 视觉样本二级准确率 | 588/700（84.00%） |
| 应澄清却误分发 | 159 |
| HTTP 错误 | 0 |

## 视觉二级混淆矩阵

```json
{
  "read_text": {
    "read_text": 221,
    "recognize_object": 0,
    "recognize_environment": 3,
    "null": 26
  },
  "recognize_object": {
    "read_text": 0,
    "recognize_object": 134,
    "recognize_environment": 0,
    "null": 66
  },
  "recognize_environment": {
    "read_text": 1,
    "recognize_object": 0,
    "recognize_environment": 233,
    "null": 16
  }
}
```

## 分类别错误数

| 类别 | 样本数 | 错误数 |
| --- | ---: | ---: |
| environment_context | 100 | 1 |
| environment_scene | 150 | 16 |
| mixed | 140 | 81 |
| negative | 100 | 0 |
| object_attribute | 50 | 12 |
| object_identity | 150 | 54 |
| phone_control | 180 | 13 |
| phone_interaction | 160 | 19 |
| phone_open | 180 | 37 |
| phone_schedule | 180 | 43 |
| screen_reading | 140 | 72 |
| text_field | 80 | 20 |
| text_reading | 170 | 9 |
| unrelated | 100 | 6 |
| vague | 120 | 0 |

数据文件：`inputs_2000.jsonl`；逐条输出：`results/results.jsonl`；错误：`results/errors.jsonl`；机器汇总：`results/summary.json`。

数据是合成样本，去重和标签信息见 `dataset_manifest.json`，不能代表真实语音或生产准确率。当前结果暴露出屏幕读字、跨模块任务和部分环境/物体表达仍会影响一级路由；这些样本已查看，后续若据此修改规则，应另建未见测试集。
