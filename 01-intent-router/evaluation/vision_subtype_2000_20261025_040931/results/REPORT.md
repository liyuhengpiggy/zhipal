# 二级视觉分类 2000 条测试

一级路由正确 **1714/2000 (85.70%)**。

视觉样本二级分类正确 **588/700 (84.00%)**。

应澄清却误分发：101 条；HTTP 错误：0。

视觉二级混淆矩阵（行期望，列预测）：

```json
{
  "read_text": {
    "read_text": 200,
    "recognize_object": 0,
    "recognize_environment": 5,
    "null": 45
  },
  "recognize_object": {
    "read_text": 0,
    "recognize_object": 157,
    "recognize_environment": 0,
    "null": 43
  },
  "recognize_environment": {
    "read_text": 4,
    "recognize_object": 0,
    "recognize_environment": 231,
    "null": 15
  }
}
```

数据为新生成的 synthetic 样本，未根据本轮结果调参。