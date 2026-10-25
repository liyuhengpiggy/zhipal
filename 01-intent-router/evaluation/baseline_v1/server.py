"""Run the deployed, offline, CPU service with fixed baseline settings."""

import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent
os.environ["HF_HOME"] = str(ROOT / ".cache" / "huggingface")
os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"
os.environ["TOKENIZERS_PARALLELISM"] = "false"
os.environ["OMP_NUM_THREADS"] = "4"
os.environ["MKL_NUM_THREADS"] = "4"
os.environ["ROUTER_MODEL"] = str(ROOT / "models" / "bge-small-zh-v1.5")
os.environ["ROUTER_DEVICE"] = "cpu"
os.environ["ROUTER_THRESHOLD"] = "0.75"
os.environ["ROUTER_MARGIN"] = "0.08"

if __name__ == "__main__":
    import torch
    import uvicorn

    torch.set_num_threads(4)
    uvicorn.run("app:app", host="127.0.0.1", port=8010, access_log=False, app_dir=str(ROOT))
