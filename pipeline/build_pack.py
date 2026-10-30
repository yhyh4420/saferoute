"""Build an offline pack for one city.

Usage: python -m pipeline.build_pack --city seoul
Outputs pipeline/out/pack_<city>.bin (route trees) and pack_<city>.sqlite (shelters + hazard rules).
"""

import argparse
import json
import sqlite3
import time
from pathlib import Path

from pipeline import graph, oracle
from pipeline.countries import jp, kr
from pipeline.qa_intents import INTENTS
from pipeline.schema import HAZARD_RULES, suitable, write_pack

ROOT = Path(__file__).resolve().parent
RAW, OUT = ROOT / "raw", ROOT / "out"

CITIES = {
    "seoul": {
        "place": "Seoul, South Korea",
        "graph": RAW / "seoul_walk_v2.graphml",
        "shelters": lambda: kr.seoul_temp_housing()[0] + kr.seoul_eq_with_gap_fill(OUT / "pack_seoul_osm.sqlite")[0],
        "edges": kr.mark_flood_trace,
    },
    "kochi": {
        "place": "Kochi, Kochi, Japan",
        "graph": RAW / "kochi_walk.graphml",
        "shelters": lambda: jp.gsi_refuges()[0],
    },
}


def write_qa(db_path: Path) -> dict:
    """Add pre-generated, auto-checked answers (pipeline/gen_answers.py) to the pack's sqlite."""
    src = OUT / "qa_answers.json"
    if not src.exists():
        return {"qa": "missing — run python -m pipeline.gen_answers"}
    from pipeline.gen_answers import rejected
    answers = [a for a in json.loads(src.read_text())
               if a["ok"] and a["answer"] not in rejected()]
    intents = {i.id: i for i in INTENTS}
    con = sqlite3.connect(db_path)
    con.execute("CREATE TABLE qa (hazard TEXT, intent TEXT, lang TEXT, question TEXT, answer TEXT, keywords TEXT, "
                "PRIMARY KEY (hazard, intent, lang))")
    con.executemany("INSERT INTO qa VALUES (?, ?, ?, ?, ?, ?)", [
        (a["hazard"], a["intent"], a["lang"], a["question"], " ".join(a["answer"].split()),  # layout only
         "|".join(intents[a["intent"]].keywords_ko if a["lang"] == "ko" else intents[a["intent"]].keywords_en))
        for a in answers
    ])
    con.commit()
    con.close()
    return {"qa_answers": len(answers)}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--city", default="seoul", choices=sorted(CITIES))
    args = ap.parse_args()
    city = CITIES[args.city]

    if not city["graph"].exists():
        graph.download(city["place"], city["graph"])
    t0 = time.time()
    g = graph.load(city["graph"])
    shelters = city["shelters"]()
    edges = city["edges"](g) if "edges" in city else {}
    hazards = [h for h in HAZARD_RULES if any(suitable(s, h) for s in shelters)]
    profiles = {h: oracle.PROFILES + (oracle.UNLOCKED_PROFILES if any(suitable(s, h) and s.may_lock for s in shelters) else ())
                for h in hazards}
    trees = [oracle.build_tree(g, shelters, h, p) for h in hazards for p in profiles[h]]

    OUT.mkdir(exist_ok=True)
    bin_path, db_path = OUT / f"pack_{args.city}.bin", OUT / f"pack_{args.city}.sqlite"
    oracle.write_pack(bin_path, g, trees, {"city": args.city, "shelters": len(shelters)})
    write_pack(shelters, db_path, hazards)
    qa = write_qa(db_path)
    report = {
        "city": args.city,
        "nodes": len(g.node_ids),
        "edges": len(g.u),
        "shelters": len(shelters),
        **edges,
        "trees": oracle.check(trees, shelters),
        **qa,
        "bin_mb": round(bin_path.stat().st_size / 1e6, 2),
        "sqlite_mb": round(db_path.stat().st_size / 1e6, 2),
        "build_s": round(time.time() - t0, 1),
    }
    (OUT / f"pack_{args.city}_report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False))
    print(json.dumps(report, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
