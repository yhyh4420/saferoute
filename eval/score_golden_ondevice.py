"""Score the on-device Llama 3.2 1B .pte against eval/golden_qa.jsonl (baseline for V13).

Usage: python -m eval.score_golden_ondevice
"""

import json

import torch  # noqa: F401  (loads libtorch for the runner)
from executorch.extension.llm.custom_ops import custom_ops  # noqa: F401  registers llama::* ops
import executorch.kernels.quantized  # noqa: F401  registers quantized_decomposed::* kernels
from executorch.extension.llm.runner import GenerationConfig, TextLLMRunner

from eval.golden_common import GROUNDING, ROOT, SITE, SYSTEM, check

MODEL = ROOT / "pipeline" / "raw" / "models"


def main() -> None:
    runner = TextLLMRunner(str(MODEL / "Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8.pte"), str(MODEL / "tokenizer.model"))
    items = [json.loads(line) for line in open(ROOT / "eval" / "golden_qa.jsonl")]
    results = []
    for it in items:
        lang, hz = it["lang"], it["hazard"]
        site = SITE[hz][0 if lang == "ko" else 1]
        prompt = (
            f"<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n{SYSTEM[lang]}<|eot_id|>"
            f"<|start_header_id|>user<|end_header_id|>\n\n{GROUNDING[hz][lang].format(site=site)}\n\n{it['q']}<|eot_id|>"
            "<|start_header_id|>assistant<|end_header_id|>\n\n"
        )
        runner.reset()  # KV cache persists across generate() calls otherwise (V13)
        out: list[str] = []
        try:
            runner.generate(prompt, GenerationConfig(echo=False, max_new_tokens=120, temperature=0.0), token_callback=out.append)
        except UnicodeDecodeError:
            # The binding decodes each token alone; a Hangul char split across tokens raises here.
            out.append("<DECODE_ERROR>")
        answer = "".join(out).replace("<|eot_id|>", "").strip()
        ok, why = check(it, answer, site)
        if "<DECODE_ERROR>" in answer:
            ok, why = False, "utf-8 split across tokens"
        results.append({"id": it["id"], "ok": ok, "why": why, "answer": answer})
        print(f"{'PASS' if ok else 'FAIL'} {it['id']} {it['q']} -> {answer[:120]} {why}", flush=True)

    scored = [r for r in results if r["id"] != "ts-07"]  # ts-07 source unverified (V18)
    summary = {
        "pass": sum(r["ok"] for r in scored),
        "total": len(scored),
        "deny_items_failed": [r["id"] for r in scored if not r["ok"] and any(i["id"] == r["id"] and i["expect"] == "deny" for i in items)],
    }
    (ROOT / "pipeline" / "out" / "golden_ondevice_1b.json").write_text(json.dumps({"summary": summary, "results": results}, ensure_ascii=False, indent=2))
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
