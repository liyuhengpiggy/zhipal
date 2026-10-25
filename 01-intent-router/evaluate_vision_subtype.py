"""Evaluate both primary route and visual subtype over a frozen JSONL fixture."""
import argparse, hashlib, json, statistics, time
from collections import Counter
from pathlib import Path
import httpx

ROOT = Path(__file__).resolve().parent
LABELS = ("vision", "phone_action", "clarify")
TASKS = ("read_text", "recognize_object", "recognize_environment")

def main(input_path, output_dir, url):
    rows = [json.loads(x) for x in input_path.read_text(encoding="utf-8").splitlines()]
    assert len(rows) == 2000 and len({x["text"] for x in rows}) == 2000
    assert all(x.get("synthetic") is True for x in rows)
    output_dir.mkdir(parents=True, exist_ok=True); results=[]; latency=[]
    with httpx.Client(base_url=url, timeout=60, trust_env=False) as client:
        health=client.get("/health"); health.raise_for_status(); health=health.json()
        assert health["policy_version"].startswith("hybrid-v2.")
        for row in rows:
            tick=time.perf_counter(); response=client.post("/route", json={"text":row["text"]}); elapsed=(time.perf_counter()-tick)*1000; latency.append(elapsed)
            if response.status_code != 200: result=dict(row, prediction="http_error", vision_prediction=None, route_correct=False, subtype_correct=False, output=response.text)
            else:
                out=response.json(); pred=out["route"]; task=out.get("vision_task")
                result=dict(row, prediction=pred, vision_prediction=task, route_correct=pred==row["expected"], subtype_correct=pred=="vision" and task==row.get("vision_task"), output=out)
            result["latency_ms"]=round(elapsed,3); results.append(result)
    (output_dir/"results.jsonl").write_text("".join(json.dumps(x,ensure_ascii=False)+"\n" for x in results),encoding="utf-8")
    errors=[x for x in results if not x["route_correct"] or (x["expected"]=="vision" and not x["subtype_correct"])]
    (output_dir/"errors.jsonl").write_text("".join(json.dumps(x,ensure_ascii=False)+"\n" for x in errors),encoding="utf-8")
    route_correct=sum(x["route_correct"] for x in results); vision_rows=[x for x in results if x["expected"]=="vision"]; subtype_correct=sum(x["subtype_correct"] for x in vision_rows)
    confusion={label:dict.fromkeys((*LABELS,"http_error"),0) for label in LABELS}
    for x in results: confusion[x["expected"]][x["prediction"]]+=1
    subtype_confusion={task:dict.fromkeys((*TASKS,"null"),0) for task in TASKS}
    for x in vision_rows: subtype_confusion[x["vision_task"] if x["vision_task"] in TASKS else "null"][x["vision_prediction"] if x["vision_prediction"] in TASKS else "null"]+=1
    summary={"total":len(results),"route_correct":route_correct,"route_accuracy":route_correct/len(results),"vision_total":len(vision_rows),"vision_subtype_correct":subtype_correct,"vision_subtype_accuracy":subtype_correct/len(vision_rows),"http_errors":sum(x["prediction"]=="http_error" for x in results),"false_dispatch_on_clarify":sum(x["expected"]=="clarify" and x["prediction"] in ("vision","phone_action") for x in results),"confusion":confusion,"vision_subtype_confusion":subtype_confusion,"prediction_counts":dict(Counter(x["prediction"] for x in results)),"decision_sources":dict(Counter(x["output"].get("decision_source") for x in results if isinstance(x["output"],dict))),"vision_task_sources":dict(Counter(x["output"].get("vision_task_source") for x in vision_rows if isinstance(x["output"],dict))),"latency_ms":{"mean":statistics.mean(latency),"median":statistics.median(latency),"p95":sorted(latency)[int(.95*(len(latency)-1))]},"dataset_sha256":hashlib.sha256(input_path.read_bytes()).hexdigest(),"service":health,"synthetic":True}
    (output_dir/"summary.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding="utf-8")
    report=f"# 二级视觉分类 2000 条测试\n\n一级路由正确 **{route_correct}/2000 ({summary['route_accuracy']:.2%})**。\n\n视觉样本二级分类正确 **{subtype_correct}/{len(vision_rows)} ({summary['vision_subtype_accuracy']:.2%})**。\n\n应澄清却误分发：{summary['false_dispatch_on_clarify']} 条；HTTP 错误：{summary['http_errors']}。\n\n视觉二级混淆矩阵（行期望，列预测）：\n\n```json\n{json.dumps(subtype_confusion,ensure_ascii=False,indent=2)}\n```\n\n数据为新生成的 synthetic 样本，未根据本轮结果调参。"
    (output_dir/"REPORT.md").write_text(report,encoding="utf-8")
    print(json.dumps({k:summary[k] for k in ("total","route_correct","route_accuracy","vision_total","vision_subtype_correct","vision_subtype_accuracy","http_errors","false_dispatch_on_clarify","latency_ms")},ensure_ascii=False,indent=2))

if __name__ == "__main__":
    p=argparse.ArgumentParser(); p.add_argument("--input",type=Path,required=True); p.add_argument("--output-dir",type=Path,required=True); p.add_argument("--url",default="http://127.0.0.1:8010"); a=p.parse_args(); main(a.input.resolve(),a.output_dir.resolve(),a.url)
