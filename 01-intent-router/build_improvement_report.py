"""Verify frozen fixture/result integrity and summarize recorded evaluations."""

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
EVAL = ROOT / "evaluation"


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    baseline = read(EVAL / "baseline_v1" / "summary.json")
    original_manifest = read(EVAL / "baseline_v1" / "dataset_manifest.json")
    assert sha(EVAL / "test_inputs_2000.jsonl") == original_manifest["dataset_sha256"]
    manifest = read(EVAL / "additional_cases_manifest.json")
    for split, info in manifest["splits"].items():
        assert sha(EVAL / f"{split}.jsonl") == info["sha256"]
    prototypes = {text for texts in read(ROOT / "routes.json").values() for text in texts}
    for name in ("test_inputs_2000", "calibration", "validation"):
        texts = {json.loads(line)["text"] for line in (EVAL / f"{name}.jsonl").read_text(encoding="utf-8").splitlines()}
        assert not texts & prototypes, f"Prototype overlap: {name}"
    for name in ("summary.json", "results_2000.jsonl", "errors.jsonl", "REPORT.md"):
        assert sha(EVAL / name) == sha(EVAL / "baseline_v1" / name), f"Baseline modified: {name}"
    summaries = []
    for folder in ("v2_regression", "v2_validation", "v21_regression", "v21_validation"):
        summary = read(EVAL / folder / "summary.json")
        results = [json.loads(line) for line in (EVAL / folder / "results.jsonl").read_text(encoding="utf-8").splitlines()]
        assert len(results) == summary["total"]
        assert sum(row["correct"] for row in results) == summary["correct"]
        if folder.startswith("v21"):
            for name, digest in summary["code_hashes"].items():
                assert sha(ROOT / name) == digest, f"Evaluated source changed: {name}"
        summaries.append(summary)
    initial, initial_validation, final, validation = summaries
    lines = [
        "# 意图路由准确率改进报告", "",
        "本地服务已更新为 hybrid-v2.1。原 2000 条合成输入保持不变，通过实际 HTTP 接口逐条核对。", "",
        "| 版本与数据 | 正确 / 总数 | 准确率 | 应澄清却误调用模块 |",
        "| --- | ---: | ---: | ---: |",
    ]
    for title, summary in (("原版 / 原2000条", baseline), ("v2首轮 / 原2000条", initial), ("v2.1最终 / 原2000条回归", final), ("v2首次 / 新增120条验证", initial_validation), ("v2.1最终 / 新增120条复测", validation)):
        false_dispatch = summary.get("false_dispatch_on_clarify")
        if false_dispatch is None:
            matrix = summary["confusion_matrix_expected_rows_predicted_columns"]
            false_dispatch = matrix["clarify"]["vision"] + matrix["clarify"]["phone_action"]
        lines.append(f"| {title} | {summary['correct']} / {summary['total']} | {summary['accuracy']:.2%} | {false_dispatch} |")
    lines += [
        "", "## 修改及原因", "",
        "原版主要把明确指令过度判成 clarify：固定阈值偏高，正向示例与澄清类中的否定/混合示例也容易相互干扰。",
        "",
        "- 新增 intent_policy.py：明确的实物读字和手机操作按任务结构分类；否定、混合任务和当前不支持的手机屏幕 OCR 返回澄清。",
        "- 限定消息、搜索和输入正文的作用范围，避免正文中的“不要”“识别文字”改变外层指令。补上“微信发给”和“搜索结果”的边界识别。",
        "- 调整 routes.json，扩充正向任务覆盖，避免否定示例干扰正向语义。",
        "- 用单独 60 条合成校准数据网格选参，语义回退阈值从 0.75 调到 0.45、分差从 0.08 调到 0.06；规则仍优先。未重新训练 BGE 权重。",
        "- 接口保留原字段，增加 policy_version 和 decision_source，便于定位规则或模型判断来源。相似度分数不是概率。",
        "", "## 剩余错误", "", "| 数据 | 输入 | 期望 | 实际 |", "| --- | --- | --- | --- |",
    ]
    for folder, title in (("v21_regression", "原2000条"), ("v21_validation", "新增120条")):
        for line in (EVAL / folder / "errors.jsonl").read_text(encoding="utf-8").splitlines():
            row = json.loads(line)
            lines.append(f"| {title} | {row['text']} | {row['expected']} | {row['prediction']} |")
    lines += [
        "", "这些错误均为分差不足返回澄清，没有互相误判视觉与手机操作。没有为消除这几条错误继续降阈值。",
        "", "## 验证与使用限制", "",
        "15 项单元/真实模型 API 测试通过。两批最终 HTTP 测试均无接口错误。",
        f"最终2000条本机逐条请求平均延迟 {final['latency_ms']['mean']:.1f} ms，P95 {final['latency_ms']['p95']:.1f} ms；不包含模型启动和语音识别时间，也不代表并发吞吐。",
        "",
        "原2000条已参与错误分析，99.95% 是回归准确率，不能当作未见测试或实际用户准确率。新增120条在 v2 首测前未参与阈值校准；首测后已查看错误。v2.1仅根据原2000条的混合任务/屏幕读字错误补规则，但其120条结果仍按复测报告，不再声称未见验证。",
        "",
        "所有输入均为明确标记 synthetic 的合成样本，未使用模型输出充当期望标签。输入及标签哈希保持不变，当前路由示例与三批数据均无完全相同的句子，但仍存在模板和语义相关性。需要新收集真实语音转写，并冻结新的独立测试集后才能估计上线表现。规则覆盖有限，复杂口语、多重否定、指代和 ASR 错字仍可能失败。",
        "",
        "baseline_v1/ 保存原版结果及源码；v2_regression/、v2_validation/ 保留首轮改进结果；v21_regression/、v21_validation/ 保存最终逐条输出、完整错误、汇总与代码哈希。没有覆盖基线。",
        "",
        "服务：http://127.0.0.1:8010/docs；运行与复现命令见项目 README.md。",
    ]
    (EVAL / "IMPROVEMENT_REPORT.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("Verified fixture hashes, baseline integrity, zero exact prototype overlap, result counts and deployed source hashes.")
    print("Written evaluation/IMPROVEMENT_REPORT.md")


if __name__ == "__main__":
    main()
