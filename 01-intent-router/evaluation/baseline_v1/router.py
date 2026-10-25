"""Chinese semantic routing, using Sentence Transformers and a BGE model."""

import json
import math
import os
from pathlib import Path
from threading import Lock


MAX_TEXT_LENGTH = 200
ROUTES = ("vision", "phone_action", "clarify")


def decide(scores, threshold=0.75, margin=0.08):
    """Similarity scores are NOT calibrated probabilities."""
    if set(scores) != set(ROUTES):
        raise ValueError("scores must contain vision, phone_action and clarify")
    if not all(math.isfinite(v) and -1.0001 <= v <= 1.0001 for v in scores.values()):
        raise ValueError("scores must be finite cosine similarities")
    if not 0 <= threshold <= 1 or not 0 <= margin <= 2:
        raise ValueError("invalid threshold or margin")
    ranked = sorted(scores, key=scores.get, reverse=True)
    best, runner_up = ranked[:2]
    gap = scores[best] - scores[runner_up]
    if best == "clarify":
        route, reason = "clarify", "matches_clarification_examples"
    elif scores[best] < threshold:
        route, reason = "clarify", "low_similarity"
    elif gap < margin:
        route, reason = "clarify", "small_margin"
    else:
        route, reason = best, "semantic_match"
    return {
        "route": route,
        "needs_clarification": route == "clarify",
        "reason": reason,
        "scores": {key: round(value, 6) for key, value in scores.items()},
        "margin": round(gap, 6),
    }


class IntentRouter:
    def __init__(self, model_name=None, threshold=None, margin=None):
        # Lazy import permits testing routing decisions without downloading weights.
        os.environ.setdefault("HF_HOME", str(Path(__file__).parent / ".cache" / "huggingface"))
        from sentence_transformers import SentenceTransformer

        self.threshold = float(os.getenv("ROUTER_THRESHOLD", "0.75")) if threshold is None else threshold
        self.margin = float(os.getenv("ROUTER_MARGIN", "0.08")) if margin is None else margin
        decide(dict.fromkeys(ROUTES, 0.0), self.threshold, self.margin)
        local_model = Path(__file__).parent / "models" / "bge-small-zh-v1.5"
        default_model = str(local_model) if (local_model / "model.safetensors").exists() else "BAAI/bge-small-zh-v1.5"
        model_name = model_name or os.getenv("ROUTER_MODEL", default_model)
        self.model = SentenceTransformer(model_name, device=os.getenv("ROUTER_DEVICE", "cpu"))
        # All accepted commands must fit without silent token truncation.
        self.model.max_seq_length = 512
        self.lock = Lock()
        examples = json.loads(Path(__file__).with_name("routes.json").read_text(encoding="utf-8"))
        self.labels = [label for label, texts in examples.items() for _ in texts]
        texts = [text for items in examples.values() for text in items]
        self.examples = self.model.encode(texts, normalize_embeddings=True, show_progress_bar=False)

    def route(self, text):
        if not isinstance(text, str) or not text.strip():
            raise ValueError("text 必须是非空字符串")
        text = text.strip()
        if len(text) > MAX_TEXT_LENGTH:
            raise ValueError(f"text 最长 {MAX_TEXT_LENGTH} 个字符，请输入单条简短指令")
        with self.lock:
            if len(self.model.tokenizer.encode(text, add_special_tokens=True)) > self.model.max_seq_length:
                raise ValueError("指令超过模型长度限制，请缩短输入")
            vector = self.model.encode([text], normalize_embeddings=True, show_progress_bar=False)[0]
        similarities = self.examples @ vector
        # Nearest example in each class, rather than a single class description.
        scores = {
            label: max(float(score) for score, example_label in zip(similarities, self.labels) if example_label == label)
            for label in ROUTES
        }
        return decide(scores, self.threshold, self.margin)


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="中文指令模块路由")
    parser.add_argument("text")
    args = parser.parse_args()
    print(json.dumps(IntentRouter().route(args.text), ensure_ascii=False, indent=2))
