"""Re-score saved golden-set answers with the current checker, without re-running models.

Also reports echo (answer mostly copied from the grounding paragraph), which the
keyword checker alone counts as a pass.

Usage: python -m eval.rescore_golden pipeline/out/golden_ondevice_1b.json pipeline/out/golden_glimmer.json
"""

import json
import sys

from eval.golden_common import ECHO_THRESHOLD, GROUNDING, check, echo_ratio, load_items, site_for


def main() -> None:
    items = {it["id"]: it for it in load_items()}
    for path in sys.argv[1:]:
        results = json.load(open(path))["results"]
        passed, echoed, total = 0, [], 0
        changed = []
        for r in results:
            if r["id"] == "ts-07":  # source unverified (V18)
                continue
            it = items[r["id"]]
            total += 1
            ok, why = check(it, r["answer"], site_for(it))
            if "<DECODE_ERROR>" in r["answer"]:
                ok, why = False, "utf-8 split across tokens"
            ratio = echo_ratio(r["answer"], GROUNDING[it["hazard"]][it["lang"]].format(site=site_for(it)))
            if ok and ratio >= ECHO_THRESHOLD and len(r["answer"]) > 40:
                echoed.append((r["id"], round(ratio, 2)))
            passed += ok
            if ok != r["ok"]:
                changed.append((r["id"], r["ok"], ok, why))
        print(f"{path}: pass {passed}/{total}, echo-passes {echoed}, changed vs saved {changed}")


if __name__ == "__main__":
    main()
