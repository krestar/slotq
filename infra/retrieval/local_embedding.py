"""Comparison-only CPU encoder. No remote runtime calls, no trust_remote_code, no query logs."""
import hashlib
import json
import os
from pathlib import Path
import sys
import time

MODEL = "sentence-transformers/all-MiniLM-L6-v2"
REVISION = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"
HASHES = {"model.onnx": "6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452",
          "tokenizer.json": "be50c3628f2bf5bb5e3a7f17b1f74611b2561a3a27eeab05e5aa30f411572037"}


def prepare(directory):
    # Explicit preparation fetches public weights only; inference never downloads anything.
    import urllib.request
    directory.mkdir(parents=True, exist_ok=True)
    hashes = {}
    for remote, local in [("onnx/model.onnx", "model.onnx"), ("tokenizer.json", "tokenizer.json")]:
        url = f"https://huggingface.co/{MODEL}/resolve/{REVISION}/{remote}"
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read(100_000_001)
        if len(data) > 100_000_000:
            raise ValueError("model size bound")
        (directory / local).write_bytes(data)
        hashes[local] = hashlib.sha256(data).hexdigest()
        if hashes[local] != HASHES[local]:
            raise ValueError("pinned model digest")
    (directory / "identity.json").write_text(json.dumps({"model": MODEL, "revision": REVISION, "sha256": hashes}), encoding="utf-8")
    print(json.dumps({"model": MODEL, "revision": REVISION, "sha256": hashes}))


def encode(directory):
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TOKENIZERS_PARALLELISM"] = "false"
    import numpy as np
    import onnxruntime as ort
    from tokenizers import Tokenizer
    import tokenizers
    import psutil
    proc = psutil.Process()
    cpu_start = proc.cpu_times()
    started = time.perf_counter()
    identity = json.loads((directory / "identity.json").read_text(encoding="utf-8"))
    if identity["model"] != MODEL or identity["revision"] != REVISION or identity["sha256"] != HASHES:
        raise ValueError("model identity")
    for name, expected in identity["sha256"].items():
        if name not in ("model.onnx", "tokenizer.json") or hashlib.sha256((directory / name).read_bytes()).hexdigest() != expected:
            raise ValueError("model digest")
    request = json.loads(sys.stdin.buffer.read(4_194_305))
    texts = request["texts"]
    if not 1 <= len(texts) <= 1025 or any(not isinstance(t, str) or len(t) > 16384 for t in texts):
        raise ValueError("input bound")
    tokenizer = Tokenizer.from_file(str(directory / "tokenizer.json"))
    tokenizer.enable_truncation(max_length=256)
    tokenizer.enable_padding()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(directory / "model.onnx"), sess_options=options, providers=["CPUExecutionProvider"])
    load_ms = (time.perf_counter() - started) * 1000
    inference_start = time.perf_counter()
    vectors = []
    per_text_ms = []
    token_count = 0
    # One text at a time keeps attention memory bounded even for the maximum corpus.
    for text in texts:
        text_started = time.perf_counter()
        encoded = tokenizer.encode(text)
        ids = np.asarray([encoded.ids], dtype=np.int64)
        mask = np.asarray([encoded.attention_mask], dtype=np.int64)
        feed = {"input_ids": ids, "attention_mask": mask, "token_type_ids": np.asarray([encoded.type_ids], dtype=np.int64)}
        hidden = session.run(None, {i.name: feed[i.name] for i in session.get_inputs()})[0]
        pooled = (hidden * mask[..., None]).sum(axis=1) / np.maximum(mask.sum(axis=1)[:, None], 1)
        normalized = pooled / np.maximum(np.linalg.norm(pooled, axis=1, keepdims=True), 1e-9)
        vectors.append(normalized[0].tolist())
        token_count += int(mask.sum())
        per_text_ms.append((time.perf_counter() - text_started) * 1000)
    cpu_end = proc.cpu_times()
    print(json.dumps({"vectors": vectors, "model": MODEL, "revision": REVISION,
                      "loadMillis": load_ms, "inferenceMillis": (time.perf_counter() - inference_start) * 1000,
                      "perTextMillis": per_text_ms,
                      "cpuSeconds": cpu_end.user + cpu_end.system - cpu_start.user - cpu_start.system,
                      "rssBytes": proc.memory_info().rss, "peakWorkingSetBytes": getattr(proc.memory_info(), "peak_wset", None),
                      "tokens": token_count, "invocations": len(texts), "externalCalls": 0,
                      "cost": "not_applicable_local_CPU", "versions": {"numpy": np.__version__, "onnxruntime": ort.__version__,
                                                                         "tokenizers": tokenizers.__version__, "psutil": psutil.__version__, "python": sys.version}}))


if __name__ == "__main__":
    try:
        if len(sys.argv) == 3 and sys.argv[1] == "prepare":
            prepare(Path(sys.argv[2]))
        elif len(sys.argv) == 3 and sys.argv[1] == "encode":
            encode(Path(sys.argv[2]))
        else:
            raise ValueError("command")
    except Exception:
        # Raw process/provider exceptions can contain local paths or input. Never export them.
        print("local_embedding_unavailable", file=sys.stderr)
        sys.exit(2)
