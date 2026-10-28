"""V20: score Muse Glimmer 30B (open weights, local llama.cpp) on eval/golden_qa.jsonl.

Start the server first (Metal, offline):
  llama-server -m pipeline/raw/models/glimmer/Muse-Glimmer-30B-KQuant-17GB-Q4_K_M.gguf --port 8091 -c 4096 -ngl 99
Usage: python -m eval.score_golden_glimmer [--url http://127.0.0.1:8091]
"""

import argparse
import json
import time
import urllib.request

from eval.golden_common import ROOT, SYSTEM, check, load_items, site_for, user_message


def chat(url: str, system: str, user: str) -> tuple[str, float]:
    body = json.dumps({
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        "temperature": 0,
        # Glimmer is a reasoning model: thinking tokens count toward max_tokens and
        # cannot be fully disabled; "low" effort keeps it to ~100-300 tokens (V20).
        "reasoning_effort": "low",
        "max_tokens": 1024,
    }).encode()
    req = urllib.request.Request(f"{url}/v1/chat/completions", body, {"Content-Type": "application/json"})
    t0 = time.time()
    resp = json.loads(urllib.request.urlopen(req, timeout=300).read())
    return resp["choices"][0]["message"]["content"].strip(), time.time() - t0


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://127.0.0.1:8091")
    args = ap.parse_args()

    items = load_items()
    results = []
    for it in items:
        answer, secs = chat(args.url, SYSTEM[it["lang"]], user_message(it))
        ok, why = check(it, answer, site_for(it))
        results.append({"id": it["id"], "ok": ok, "why": why, "secs": round(secs, 2), "answer": answer})
        print(f"{'PASS' if ok else 'FAIL'} {it['id']} ({secs:.1f}s) {it['q']} -> {answer[:160]} {why}", flush=True)

    scored = [r for r in results if r["id"] != "ts-07"]  # ts-07 source unverified (V18)
    summary = {"pass": sum(r["ok"] for r in scored), "total": len(scored)}
    (ROOT / "pipeline" / "out" / "golden_glimmer.json").write_text(
        json.dumps({"summary": summary, "results": results}, ensure_ascii=False, indent=2)
    )
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
