"""Standalone check of the Tencent Cloud OCR path, before any deploy.

Verifies credentials, network reachability and the adapted line structure on
the two bundled sample images. Exits non-zero on failure.

Secret values are read from the environment only; never pass them on the
command line or commit them.

    set TENCENTCLOUD_SECRET_ID=...
    set TENCENTCLOUD_SECRET_KEY=...
    python verify_tencent_ocr.py
"""

from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SAMPLES = [ROOT / "examples" / "date_mfg_exp.jpg", ROOT / "examples" / "date_other.jpg"]


def fail(message):
    print(f"[失败] {message}", flush=True)
    raise SystemExit(1)


def check_credentials():
    missing = [name for name in ("TENCENTCLOUD_SECRET_ID", "TENCENTCLOUD_SECRET_KEY")
               if not os.environ.get(name, "").strip()]
    if missing:
        fail("缺少环境变量：" + "、".join(missing) +
             "。请先在终端安全设置，不要写入文件或提交到 Git。")
    print(f"[通过] 密钥已注入（SecretId 尾四位 …{os.environ['TENCENTCLOUD_SECRET_ID'].strip()[-4:]}）")
    print(f"[信息] 地域 {os.environ.get('TENCENTCLOUD_OCR_REGION', 'ap-guangzhou')}，"
          f"接口 GeneralAccurateOCR")


def build_engine():
    sys.path.insert(0, str(ROOT))
    try:
        import ocr_cloud_tencent
    except ImportError as error:
        fail(f"无法导入适配模块：{error}")
    try:
        engine = ocr_cloud_tencent.create_engine()
    except Exception as error:
        fail(f"创建 OCR 客户端失败：{error}")
    print("[通过] OCR 客户端已创建")
    return ocr_cloud_tencent, engine


def run_probe(module, engine):
    if os.environ.get("VISION_OCR_STARTUP_PROBE", "1").strip().lower() in ("0", "false", "no"):
        print("[跳过] 启动自检已关闭")
        return
    started = time.perf_counter()
    try:
        module.probe(engine)
    except Exception as error:
        fail(f"启动自检未通过：{error}")
    print(f"[通过] 启动自检通过，用时 {time.perf_counter() - started:.2f} 秒")


def run_samples(module, engine):
    results = []
    for path in SAMPLES:
        if not path.is_file():
            fail(f"缺少样例图片 {path}")
        started = time.perf_counter()
        try:
            lines = module.recognize(engine, path, path)
        except Exception as error:
            fail(f"识别 {path.name} 失败：{error}")
        elapsed = time.perf_counter() - started
        print(f"[信息] {path.name}：{len(lines)} 行，用时 {elapsed:.2f} 秒")
        for line in lines:
            print(f"        text={line['text']!r} conf={line['ocr_confidence']} "
                  f"bbox={line['bbox']}")
        results.append((path.name, lines))
        if not lines:
            fail(f"{path.name} 未识别出任何文字，无法据此判定链路可用。")
    return results


def check_structure(results):
    for name, lines in results:
        for line in lines:
            for key in ("text", "line_index", "ocr_confidence", "bbox", "source_image"):
                if key not in line:
                    fail(f"{name} 返回行缺少字段 {key}")
            if line["bbox"] is None:
                fail(f"{name} 的行 {line['line_index']} 缺少 bbox 坐标")
            confidence = line["ocr_confidence"]
            if confidence is not None and not 0.0 <= confidence <= 1.0:
                fail(f"{name} 的置信度 {confidence} 不在 0-1 区间")
    print("[通过] 返回结构与本地 PaddleOCR 路径一致（含 0-1 归一化置信度）")


def check_distinct(results):
    texts = [" ".join(line["text"] for line in lines) for _, lines in results]
    if len(texts) == 2 and texts[0] == texts[1]:
        fail("两张样例图的识别结果完全相同，样本与图片可能没有正确对应。")
    print("[通过] 两张样例图结果不同，各自对应各自图片")


def main():
    check_credentials()
    module, engine = build_engine()
    run_probe(module, engine)
    results = run_samples(module, engine)
    check_structure(results)
    check_distinct(results)
    print("[通过] 腾讯云 OCR 链路可用。合成样例只能证明链路，不能证明真实药盒准确率；"
          "请再用手边真实药盒照片复测。")
    print(json.dumps({name: lines for name, lines in results}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
