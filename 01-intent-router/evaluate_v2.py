"""Compare labeled synthetic fixtures with actual deployed HTTP responses."""

import argparse
import hashlib
import json
import statistics
import time
from collections import Counter
from datetime import datetime
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parent
LABELS = ("vision", "phone_action", "clarify")


def file_hash(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(base_url, input_path, folder):
    rows = [json.loads(line) for line in input_path.read_text(encoding="utf-8").splitlines()]
    if not rows or len({row["text"] for row in rows}) != len(rows):
        raise ValueError("Dataset must contain distinct texts")
    if not all(row["expected"] in LABELS and row.get("synthetic") is True for row in rows):
        raise ValueError("Requires labeled, explicitly synthetic fixtures")
    folder.mkdir(parents=True, exist_ok=True)
    results, latencies = [], []
    started = datetime.now().astimezone().isoformat()
    start = time.perf_counter()
    with httpx.Client(base_url=base_url, timeout=60, trust_env=False) as client, (folder / "results.jsonl").open("w", encoding="utf-8") as stream:
        health_response = client.get("/health")
        health_response.raise_for_status()
        health = health_response.json()
        if not str(health.get("policy_version", "")).startswith("hybrid-v2"):
            raise RuntimeError("Expected the deployed hybrid-v2 service")
        client.post("/route", json={"text": "请打开手机里的计算器"}).raise_for_status()
        for index, row in enumerate(rows, 1):
            tick = time.perf_counter()
            response = client.post("/route", json={"text": row["text"]})
            elapsed = (time.perf_counter() - tick) * 1000
            record = dict(row, latency_ms=round(elapsed, 3), http_status=response.status_code)
            if response.status_code == 200:
                prediction = response.json()
                if prediction.get("route") not in LABELS:
                    raise RuntimeError("Invalid route from API")
                if prediction.get("policy_version") != health.get("policy_version"):
                    raise RuntimeError("Service version changed during evaluation")
                record.update(prediction=prediction["route"], correct=prediction["route"] == row["expected"], output=prediction)
            else:
                record.update(prediction="http_error", correct=False, output=response.text)
            results.append(record)
            latencies.append(elapsed)
            stream.write(json.dumps(record, ensure_ascii=False) + "\n")
            if index % 100 == 0:
                stream.flush()
                correct = sum(item["correct"] for item in results)
                print(f"{index}/{len(rows)}; correct={correct}; accuracy={correct / index:.2%}", flush=True)
    confusion = {label: dict.fromkeys((*LABELS, "http_error"), 0) for label in LABELS}
    for item in results:
        confusion[item["expected"]][item["prediction"]] += 1
    per_class = {}
    for label in LABELS:
        tp = confusion[label][label]
        support = sum(confusion[label].values())
        predicted = sum(confusion[expected][label] for expected in LABELS)
        precision = tp / predicted if predicted else 0
        recall = tp / support if support else 0
        per_class[label] = dict(count=support, correct=tp, precision=precision, recall=recall, f1=2 * precision * recall / (precision + recall) if precision + recall else 0)
    errors = [item for item in results if not item["correct"]]
    (folder / "errors.jsonl").write_text("".join(json.dumps(item, ensure_ascii=False) + "\n" for item in errors), encoding="utf-8")
    summary = {
        "started_at": started, "finished_at": datetime.now().astimezone().isoformat(),
        "synthetic": True, "total": len(results), "correct": len(results) - len(errors), "incorrect": len(errors),
        "accuracy": (len(results) - len(errors)) / len(results), "per_class": per_class,
        "confusion_matrix_expected_rows_predicted_columns": confusion,
        "latency_ms": {"mean": statistics.mean(latencies), "median": statistics.median(latencies), "p95": sorted(latencies)[int(.95 * (len(latencies) - 1))]},
        "duration_seconds": time.perf_counter() - start,
        "http_errors": sum(item["prediction"] == "http_error" for item in results),
        "prediction_counts": dict(Counter(item["prediction"] for item in results)),
        "decision_sources": dict(Counter(item["output"]["decision_source"] for item in results if isinstance(item["output"], dict))),
        "error_reason_counts": dict(Counter(item["output"]["reason"] if isinstance(item["output"], dict) else "http_error" for item in errors)),
        "false_dispatch_on_clarify": sum(item["expected"] == "clarify" and item["prediction"] in ("vision", "phone_action") for item in results),
        "dataset": str(input_path), "dataset_sha256": file_hash(input_path),
        "service": health, "base_url": base_url,
        "code_hashes": {name: file_hash(ROOT / name) for name in ("router.py", "intent_policy.py", "routes.json", "router_config.json", "app.py", "server.py")},
        "limitations": "合成测试，不能代表真实语音识别或生产准确率。数据生成方式、去重及冻结信息见对应数据集的 manifest。原2000条与已查看结果的120条均属于已见数据；新生成数据若用于后续调参，也不再属于未见测试。模板和语义相关性会限制代表性。"
    }
    (folder / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = [f"# 中文意图路由：{len(rows)} 条合成输入测试", "", f"测试时间：{started}", "", f"正确 **{summary['correct']} / {len(rows)}**，准确率 **{summary['accuracy']:.2%}**，HTTP 错误 **{summary['http_errors']}**。", "", f"服务版本：{health.get('policy_version')}；语义回退阈值：{health.get('threshold')}；分差：{health.get('margin')}。明确任务和否定、混合任务先按结构判断，剩余表达使用 BGE 相似度。", "", "| 类别 | 条数 | 正确 | 召回率 | 精确率 |", "| --- | ---: | ---: | ---: | ---: |"]
    for label, values in per_class.items():
        lines.append(f"| {label} | {values['count']} | {values['correct']} | {values['recall']:.2%} | {values['precision']:.2%} |")
    lines += ["", "混淆矩阵（行是期望，列是实际输出）：", "", "| 期望 / 实际 | vision | phone_action | clarify | HTTP 错误 |", "| --- | ---: | ---: | ---: | ---: |"]
    for label in LABELS:
        values = confusion[label]
        lines.append(f"| {label} | {values['vision']} | {values['phone_action']} | {values['clarify']} | {values['http_error']} |")
    lines += ["", f"需要澄清的输入被错误分发到模块：{summary['false_dispatch_on_clarify']} 条。实际返回 clarify 比例：{summary['prediction_counts'].get('clarify', 0) / len(results):.2%}。", "", "错误原因：" + json.dumps(summary["error_reason_counts"], ensure_ascii=False), "", f"本机逐条 HTTP 延迟：均值 {statistics.mean(latencies):.1f} ms，中位数 {statistics.median(latencies):.1f} ms，P95 {summary['latency_ms']['p95']:.1f} ms；总用时 {summary['duration_seconds']:.1f} s。含接口开销，不含服务启动与下载。", "", "错误示例（每类最多 3 条）：", "", "| 输入 | 期望 | 实际 |", "| --- | --- | --- |"]
    displayed = Counter()
    for item in errors:
        if displayed[item["expected"]] < 3:
            lines.append(f"| {item['text']} | {item['expected']} | {item['prediction']} |")
            displayed[item["expected"]] += 1
    if not errors:
        lines.append("| 无 | — | — |")
    lines += ["", summary["limitations"], "", "原始输入与期望保留在 summary.json 的 dataset 路径；results.jsonl 保存逐条输出，errors.jsonl 保存全部错误。模型得分是相似度，不是概率。结构判断结果可能与相似度最高类别不同，以 decision_source 和 reason 为准。"]
    (folder / "REPORT.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({key: summary[key] for key in ("total", "correct", "incorrect", "accuracy", "per_class", "http_errors", "false_dispatch_on_clarify", "service")}, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8010")
    parser.add_argument("--input", type=Path, default=ROOT / "evaluation" / "test_inputs_2000.jsonl")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "evaluation" / "v2_regression")
    args = parser.parse_args()
    run(args.url, args.input.resolve(), args.output_dir.resolve())
