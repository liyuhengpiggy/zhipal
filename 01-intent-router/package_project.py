"""Bundle portable project files and verify ZIP CRC and every content hash."""

import hashlib
import json
import zipfile
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent
EXCLUDED = {".venv", ".cache", "__pycache__", ".git", "logs"}


def sha(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def main():
    files = []
    for path in sorted(ROOT.rglob("*")):
        relative = path.relative_to(ROOT)
        if (not path.is_file() or any(part in EXCLUDED for part in relative.parts)
                or path.suffix in {".pid", ".pyc", ".partial"}
                or path.name == "PACKAGE_MANIFEST.json"):
            continue
        if len(relative.parts) > 1 and relative.parts[0] not in {"evaluation", "models"}:
            continue
        if len(relative.parts) == 1 and path.suffix not in {".py", ".md", ".txt", ".json", ".ps1"} and path.name != ".gitignore":
            continue
        files.append(path)
    entries = [{"path": path.relative_to(ROOT).as_posix(), "size_bytes": path.stat().st_size, "sha256": sha(path)} for path in files]
    model_manifest = json.loads((ROOT / "model-manifest.json").read_text(encoding="utf-8"))
    for item in model_manifest["files"]:
        path = ROOT / "models" / "bge-small-zh-v1.5" / item["Path"]
        assert path.stat().st_size == item["Size"] and sha(path) == item["Sha256"], item["Path"]
    created = datetime.now().astimezone()
    manifest = {"created_at": created.isoformat(), "policy_version": "hybrid-v2.3", "files": entries,
                "excludes": sorted(EXCLUDED) + ["*.pid", "*.pyc", "*.partial"],
                "includes_model_weights": True, "includes_python_environment": False,
                "note": "Manifest excludes its own hash; install Python dependencies after extracting."}
    manifest_path = ROOT / "PACKAGE_MANIFEST.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    output = ROOT.parent / "deliverables"
    output.mkdir(exist_ok=True)
    target = output / f"中文指令路由项目_hybrid-v2.3_{created.strftime('%Y%m%d_%H%M%S')}.zip"
    prefix = "intent-router/"
    with zipfile.ZipFile(target, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in files + [manifest_path]:
            archive.write(path, prefix + path.relative_to(ROOT).as_posix())
    with zipfile.ZipFile(target) as archive:
        assert archive.testzip() is None
        assert len(archive.namelist()) == len(entries) + 1
        for entry in entries:
            data = archive.read(prefix + entry["path"])
            assert len(data) == entry["size_bytes"]
            assert hashlib.sha256(data).hexdigest() == entry["sha256"], entry["path"]
        assert archive.read(prefix + "PACKAGE_MANIFEST.json") == manifest_path.read_bytes()
    archive_hash = sha(target)
    target.with_suffix(".zip.sha256").write_text(f"{archive_hash}  {target.name}\n", encoding="utf-8")
    verification = {"archive": target.name, "sha256": archive_hash, "size_bytes": target.stat().st_size,
                    "file_count": len(entries) + 1, "crc_verified": True, "all_file_hashes_verified": True,
                    "model_manifest_verified": True}
    target.with_suffix(".verification.json").write_text(json.dumps(verification, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(verification, ensure_ascii=False, indent=2))
    print(str(target))


if __name__ == "__main__":
    main()
