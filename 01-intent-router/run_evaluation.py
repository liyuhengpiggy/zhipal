"""Compatibility entry point; writes v2 results without overwriting the baseline."""

import argparse
from evaluate_v2 import ROOT, run

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Evaluate the current router; preserves v1 results")
    parser.add_argument("--url", default="http://127.0.0.1:8010")
    args = parser.parse_args()
    run(args.url, ROOT / "evaluation" / "test_inputs_2000.jsonl", ROOT / "evaluation" / "current_regression")
