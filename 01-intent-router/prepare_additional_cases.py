"""Freeze additional manually authored synthetic cases, with no model labeling."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
source = ROOT / "evaluation" / "additional_cases.json"
cases = json.loads(source.read_text(encoding="utf-8"))
used = {json.loads(line)["text"] for line in (ROOT / "evaluation" / "test_inputs_2000.jsonl").read_text(encoding="utf-8").splitlines()}
used.update(t for values in json.loads((ROOT / "routes.json").read_text(encoding="utf-8")).values() for t in values)
counts = {"calibration": 60, "validation": 120}
manifest = {"synthetic": True, "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(), "splits": {}}
for split, total in counts.items():
    rows = []
    for expected, texts in cases[split].items():
        for text in texts:
            assert text not in used, f"Duplicate: {text}"
            used.add(text)
            rows.append({"id": f"{split}-{len(rows)+1:03d}", "text": text, "expected": expected, "synthetic": True, "category": split})
    assert len(rows) == total
    target = ROOT / "evaluation" / f"{split}.jsonl"
    target.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
    manifest["splits"][split] = {"count": total, "sha256": hashlib.sha256(target.read_bytes()).hexdigest()}
(ROOT / "evaluation" / "additional_cases_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(manifest, ensure_ascii=False, indent=2))
