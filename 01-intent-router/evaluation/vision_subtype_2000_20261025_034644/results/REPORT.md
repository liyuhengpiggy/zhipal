# 二级视觉分类 2000 条测试

一级路由正确 **1635/2000 (81.75%)**。

视觉样本二级分类正确 **588/700 (84.00%)**。

应澄清却误分发：159 条；HTTP 错误：0。

视觉二级混淆矩阵（行期望，列预测）：

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

数据为新生成的 synthetic 样本，未根据本轮结果调参。