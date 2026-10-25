"""Download pinned mirror files and verify sizes and SHA256 hashes."""

import hashlib
import json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import quote
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parent
TARGET = ROOT / "models" / "bge-small-zh-v1.5"
SKIP = {".gitattributes", "pytorch_model.bin", "configuration.json"}


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def download(entry):
    path = TARGET / entry["Path"]
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.stat().st_size == entry["Size"] and sha256(path) == entry["Sha256"]:
        print("Verified existing:", entry["Path"], flush=True)
    else:
        url = "https://modelscope.cn/models/AI-ModelScope/bge-small-zh-v1.5/resolve/" + entry["Revision"] + "/" + quote(entry["Path"], safe="/")
        partial = path.with_name(path.name + ".partial")
        print("Downloading:", entry["Path"], flush=True)
        with urlopen(url, timeout=90) as response, partial.open("wb") as stream:
            while block := response.read(1024 * 1024):
                stream.write(block)
        if partial.stat().st_size != entry["Size"] or sha256(partial) != entry["Sha256"]:
            raise RuntimeError("Size or SHA256 mismatch: " + entry["Path"])
        partial.replace(path)
    return {key: entry[key] for key in ("Path", "Revision", "Size", "Sha256")}


if __name__ == "__main__":
    metadata = json.loads((ROOT / "modelscope-files.json").read_text(encoding="utf-8"))
    files = [item for item in metadata["Data"]["Files"] if item["Type"] == "blob" and item["Path"] not in SKIP]
    with ThreadPoolExecutor(max_workers=4) as pool:
        records = list(pool.map(download, files))
    manifest = {"upstream_model": "BAAI/bge-small-zh-v1.5", "download_mirror": "AI-ModelScope/bge-small-zh-v1.5", "files": records}
    (ROOT / "model-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print("All model files verified.", flush=True)
