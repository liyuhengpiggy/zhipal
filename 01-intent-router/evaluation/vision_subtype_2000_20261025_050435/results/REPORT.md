# 二级视觉分类 2000 条测试

一级路由正确 **1725/2000 (86.25%)**。

视觉样本二级分类正确 **627/700 (89.57%)**。

应澄清却误分发：99 条；HTTP 错误：0。

视觉二级混淆矩阵（行期望，列预测）：

```json
{
  "read_text": {
    "read_text": 216,
    "recognize_object": 0,
    "recognize_environment": 0,
    "null": 34
  },
  "recognize_object": {
    "read_text": 0,
    "recognize_object": 163,
    "recognize_environment": 0,
    "null": 37
  },
  "recognize_environment": {
    "read_text": 2,
    "recognize_object": 0,
    "recognize_environment": 248,
    "null": 0
  }
}
```

数据为新生成的 synthetic 样本，未根据本轮结果调参。