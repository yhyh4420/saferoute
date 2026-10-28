"""Japan (Kochi) adapter: GSI 指定緊急避難場所 data -> common schema.

A "1" in a hazard column means the site is designated safe *for* that hazard
(flag_semantics = refuge). Only flags that also have a rule in HAZARD_RULES
become destinations; the rest (e.g. volcano) are kept out of `hazards`.
"""

import csv
from pathlib import Path

from pipeline.schema import DESIGNATED_REFUGE, HAZARD_RULES, Shelter

RAW = Path(__file__).resolve().parents[1] / "raw" / "jp"
DEFAULT_CSV = RAW / "mergeFromCity_2.csv"
CITY = "高知県高知市"
SOURCE = "国土地理院 指定緊急避難場所データ (PDL1.0)"

COLUMN_HAZARDS = {
    "洪水": "flood",
    "崖崩れ、土石流及び地滑り": "landslide",
    "高潮": "storm_surge",
    "地震": "earthquake",
    "津波": "tsunami",
    "大規模な火事": "fire",
    "内水氾濫": "inland_flood",
    "火山現象": "volcano",
}


def gsi_refuges(path: Path = DEFAULT_CSV, city: str = CITY) -> tuple[list[Shelter], dict]:
    """Return Kochi designated refuges plus a report of what was dropped."""
    report: dict = {"rows": 0, "dropped_other_city": 0, "dropped_no_flag": 0,
                    "dropped_bad_coords": 0}
    shelters = []
    with open(path, encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            report["rows"] += 1
            if row.get("都道府県名及び市町村名") != city:
                report["dropped_other_city"] += 1
                continue
            hazards = [hid for col, hid in COLUMN_HAZARDS.items()
                       if (row.get(col) or "").strip() == "1" and hid in HAZARD_RULES]
            if not hazards:
                report["dropped_no_flag"] += 1
                continue
            try:
                lat, lon = float(row["緯度"]), float(row["経度"])
            except (TypeError, ValueError):
                report["dropped_bad_coords"] += 1
                continue
            if not (-90 <= lat <= 90 and -180 <= lon <= 180):
                report["dropped_bad_coords"] += 1
                continue
            shelters.append(
                Shelter(
                    id=f"JP:gsi:{row['共通ID']}",
                    country="JP",
                    name=row["施設・場所名"],
                    lat=lat,
                    lon=lon,
                    facility=DESIGNATED_REFUGE,
                    source=SOURCE,
                    address=row.get("住所") or "",
                    hazards=hazards,
                    designated_for=frozenset(hazards),
                )
            )
    report["shelters"] = len(shelters)
    return shelters, report
