"""Summarise a field-test log pulled with scripts/pull_fieldlog.sh, without printing coordinates.

Usage: python -m eval.fieldlog_report pipeline/out/fieldlog_YYYYMMDD_HHMM.jsonl
Reports GPS quality, offline coverage, compute time, how the remaining distance fell while walking
towards one destination, and Q&A use. Locations stay in the (gitignored) log file.
"""

import json
import statistics as st
import sys
from datetime import datetime


def pct(xs, q):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(q * len(xs)))] if xs else None


def main(path: str) -> None:
    rows = [json.loads(line) for line in open(path) if line.strip()]
    guide = [r for r in rows if r["event"] == "guide"]
    qa = [r for r in rows if r["event"] == "qa"]
    if not guide:
        print("no guidance entries")
        return
    t0, t1 = guide[0]["t"], guide[-1]["t"]
    fmt = lambda t: datetime.fromtimestamp(t / 1000).strftime("%m-%d %H:%M:%S")
    print(f"entries {len(rows)} (guide {len(guide)}, qa {len(qa)}) · {fmt(t0)} → {fmt(t1)}")

    acc = [r["acc_m"] for r in guide if r.get("acc_m") is not None]
    age = [r["fix_age_s"] for r in guide if r.get("fix_age_s") is not None]
    print(f"GPS accuracy m: median {st.median(acc):.0f}, p90 {pct(acc, .9):.0f}" if acc else "GPS accuracy: n/a")
    print(f"fix age s: median {st.median(age):.0f}, p90 {pct(age, .9):.0f}" if age else "fix age: n/a")

    results = {}
    for r in guide:
        results[r["result"]] = results.get(r["result"], 0) + 1
    print("results:", results)
    off = [r for r in guide if r.get("online") is False]
    print(f"offline entries {len(off)} ({len(off) / len(guide):.0%}); ok while offline: "
          f"{sum(r['result'] == 'Ok' for r in off)}")
    ms = [r["compute_ms"] for r in guide if r.get("compute_ms") is not None]
    print(f"compute ms: median {st.median(ms):.2f}, max {max(ms):.1f}" if ms else "compute ms: n/a")
    print(f"outside city {sum(bool(r.get('outside_city')) for r in guide)}, lock warning "
          f"{sum(bool(r.get('lock')) for r in guide)}, compass {sum(bool(r.get('heading')) for r in guide)}/{len(guide)}")
    snap = [r["snap_m"] for r in guide if r.get("snap_m") is not None]
    if snap:
        print(f"distance to nearest walkable node m: median {st.median(snap):.0f}, p90 {pct(snap, .9):.0f}")

    # Walks: consecutive entries with the same hazard and destination; did the remaining distance fall?
    walks, cur = [], []
    for r in guide:
        if r["result"] != "Ok":
            continue
        if cur and (r["shelter"] != cur[-1]["shelter"] or r["hazard"] != cur[-1]["hazard"]):
            walks.append(cur)
            cur = []
        cur.append(r)
    if cur:
        walks.append(cur)
    walks = [w for w in walks if len(w) >= 5]
    print(f"walk segments (same destination, ≥5 fixes): {len(walks)}")
    for i, w in enumerate(walks, 1):
        d = [r["dist_m"] for r in w]
        mins = (w[-1]["t"] - w[0]["t"]) / 60000
        rises = sum(1 for a, b in zip(d, d[1:]) if b > a + 30)
        print(f"  #{i} {w[0]['hazard']}: {d[0]} m → {d[-1]} m in {mins:.1f} min, {len(w)} fixes, "
              f"jumps up >30 m: {rises}{' (stepfree)' if w[0].get('stepfree') else ''}")

    if qa:
        print(f"Q&A: {len(qa)} asked, matched {sum(bool(r.get('matched')) for r in qa)}")
        for r in qa:
            print(f"  {r['hazard']}: {r['q'][:40]!r} → {r.get('intent')}")


if __name__ == "__main__":
    main(sys.argv[1])
