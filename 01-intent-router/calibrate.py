"""Select fallback thresholds on calibration only; validation is never read."""

import hashlib
import json
import os
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent
os.environ["HF_HOME"] = str(ROOT / ".cache" / "huggingface")
os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"
os.environ["OMP_NUM_THREADS"] = "4"
os.environ["MKL_NUM_THREADS"] = "4"

if __name__ == "__main__":
    import torch
    from router import IntentRouter, decide, route_from_scores

    torch.set_num_threads(4)
    path = ROOT / "evaluation" / "calibration.jsonl"
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    assert len(rows) == 60
    router = IntentRouter()
    outputs = [router.route(row["text"]) for row in rows]
    trials = []
    for threshold in (.45, .50, .55, .60, .65, .70, .75):
        for margin in (0.02, 0.04, 0.06, 0.08):
            semantic = [decide(output["scores"], threshold, margin)["route"] for output in outputs]
            hybrid = [route_from_scores(row["text"], output["scores"], threshold, margin)["route"] for row, output in zip(rows, outputs)]
            trials.append({
                "threshold": threshold, "margin": margin,
                "semantic_correct": sum(p == row["expected"] for p, row in zip(semantic, rows)),
                "hybrid_correct": sum(p == row["expected"] for p, row in zip(hybrid, rows)),
                "hybrid_false_dispatch": sum(p != "clarify" and row["expected"] == "clarify" for p, row in zip(hybrid, rows))
            })
    # Prefer overall hybrid correctness, then fewer false dispatches, then stronger
    # semantic-only classification. Ties prefer more conservative thresholds.
    best = max(trials, key=lambda r: (r["hybrid_correct"], -r["hybrid_false_dispatch"], r["semantic_correct"], r["threshold"], r["margin"]))
    config = {"threshold": best["threshold"], "margin": best["margin"], "status": "calibrated_v2", "calibration_count": 60, "calibration_sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "selection": best}
    (ROOT / "router_config.json").write_text(json.dumps(config, ensure_ascii=False, indent=2), encoding="utf-8")
    result = {"chosen": config, "trials": trials, "decision_sources": dict(Counter(output["decision_source"] for output in outputs))}
    (ROOT / "evaluation" / "calibration_report.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    (ROOT / "evaluation" / "calibration_outputs.jsonl").write_text("".join(json.dumps(dict(row, scores=output["scores"]), ensure_ascii=False) + "\n" for row, output in zip(rows, outputs)), encoding="utf-8")
    print(json.dumps(config, ensure_ascii=False, indent=2), flush=True)
