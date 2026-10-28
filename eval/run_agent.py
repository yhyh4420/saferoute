"""Agent evaluation: N Seoul locations x hazards, scored against the offline oracle (plan v5, Day 6).

Usage: python -m eval.run_agent [--n 20] [--hazards earthquake,flood]
Writes pipeline/out/agent_eval.json.
"""

import argparse
import json

import numpy as np

from eval.agent import ROOT, Pack, api_key, run_agent, validate_plan

PROFILES = [
    {"with": "alone", "step_free": False},
    {"with": "a 5-year-old child", "step_free": False},
    {"with": "an elderly parent who cannot use stairs", "step_free": True},
]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=20)
    ap.add_argument("--hazards", default="earthquake,flood")
    args = ap.parse_args()

    pack = Pack("seoul")
    key = api_key()
    rng = np.random.default_rng(7)
    picks = rng.choice(len(pack.lat), args.n, replace=False)
    results = []
    for i, node in enumerate(picks):
        place = {"name": f"place-{i}", "lat": round(float(pack.lat[node]), 6), "lon": round(float(pack.lon[node]), 6)}
        profile = PROFILES[i % len(PROFILES)]
        for hazard in args.hazards.split(","):
            run = run_agent(pack, key, place, hazard, profile)
            problems = validate_plan(run, hazard, pack) if not run.error else [run.error]
            results.append({"place": place, "hazard": hazard, "profile": profile, "valid": not problems,
                            "problems": problems, "calls": run.calls, "seconds": round(run.seconds, 1),
                            "usage": run.usage, "plan": run.plan})
            print(f"{'OK ' if not problems else 'BAD'} {place['name']} {hazard:10s} {profile['with'][:20]:20s} "
                  f"{run.seconds:5.1f}s calls={run.calls} {problems}", flush=True)

    valid = [r for r in results if r["valid"]]
    usage_in = sum(r["usage"]["prompt_tokens"] for r in results)
    usage_out = sum(r["usage"]["completion_tokens"] for r in results)
    summary = {
        "runs": len(results),
        "valid": len(valid),
        "valid_pct": round(len(valid) / len(results) * 100, 1),
        "invented_ids": sum(any("never returned" in p for p in r["problems"]) for r in results),
        "median_seconds": float(np.median([r["seconds"] for r in results])),
        "median_tool_calls": float(np.median([len(r["calls"]) for r in results])),
        "cost_usd_standard": round(usage_in / 1e6 * 1.25 + usage_out / 1e6 * 4.25, 3),
    }
    (ROOT / "pipeline" / "out" / "agent_eval.json").write_text(
        json.dumps({"summary": summary, "results": results}, ensure_ascii=False, indent=2))
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
