"""Korea (Seoul) adapter: official shelter datasets -> common schema.

- Flood destinations: 서울 열린데이터광장 OA-21064 이재민임시주거시설 (service TlInetqkP).
- Earthquake destinations: 재난안전데이터 공유플랫폼 DSSP-IF-00103 지진옥외대피소 (needs an
  approved KR_DATA_API_KEY). Until then the OSM park proxy from pipeline.countries.osm is used.

Source values (names, coordinates, ids) are copied unchanged — KOGL type 3 forbids
modification (VALIDATION V17). We only filter, merge rooms into buildings, and convert format.
"""

import json
import re
import sqlite3
import urllib.request
from collections import defaultdict
from pathlib import Path

from pipeline.schema import OUTDOOR_OPEN_SPACE, TEMPORARY_HOUSING, Shelter

RAW = Path(__file__).resolve().parents[1] / "raw" / "kr"
SEOUL_TEMP_HOUSING_JSON = RAW / "seoul_temp_housing_OA-21064.json"
SEOUL_BBOX = (37.40, 126.75, 37.72, 127.20)  # lat_min, lon_min, lat_max, lon_max
UNDERGROUND = re.compile(r"지하|B1|b1|지층")
DUMMY_NAME = re.compile(r"테스트|test", re.IGNORECASE)
BUILDING_ROUND = 4  # ~10 m: rooms of one building share coordinates (VALIDATION V2c)


def _in_seoul(lat: float, lon: float) -> bool:
    return SEOUL_BBOX[0] <= lat <= SEOUL_BBOX[2] and SEOUL_BBOX[1] <= lon <= SEOUL_BBOX[3]


def download_seoul_temp_housing(api_key: str) -> list[dict]:
    rows: list[dict] = []
    start = 1
    while True:
        url = f"http://openapi.seoul.go.kr:8088/{api_key}/json/TlInetqkP/{start}/{start + 999}/"
        body = json.loads(urllib.request.urlopen(url, timeout=60).read())["TlInetqkP"]
        rows += body["row"]
        if len(rows) >= int(body["list_total_count"]):
            break
        start += 1000
    SEOUL_TEMP_HOUSING_JSON.write_text(json.dumps(rows, ensure_ascii=False))
    return rows


def seoul_temp_housing(rows: list[dict] | None = None) -> tuple[list[Shelter], dict]:
    """Return one shelter per building plus a report of what was dropped."""
    rows = rows if rows is not None else json.loads(SEOUL_TEMP_HOUSING_JSON.read_text())
    report = {"rows": len(rows), "dropped_dummy": [], "dropped_out_of_bounds": []}
    buildings: dict[tuple[float, float], list[dict]] = defaultdict(list)
    for r in rows:
        name = r["ACTC_FCLT_NM"]
        lat, lon = float(r["LAT"]), float(r["LOT"])
        if DUMMY_NAME.search(name):
            report["dropped_dummy"].append(name)
        elif not _in_seoul(lat, lon):
            report["dropped_out_of_bounds"].append(name)
        else:
            buildings[(round(lat, BUILDING_ROUND), round(lon, BUILDING_ROUND))].append(r)

    shelters = []
    for rooms in buildings.values():
        first = rooms[0]
        # A building is unusable for floods only if every listed room is underground.
        underground = all(UNDERGROUND.search(r.get("DADDR") or "") for r in rooms)
        shelters.append(
            Shelter(
                id=f"KR:seoul-OA-21064:{first['FCLT_NO']}",  # FCLT_SN repeats across 구 (412 unique of 1,100)
                country="KR",
                name=first["ACTC_FCLT_NM"],
                lat=float(first["LAT"]),
                lon=float(first["LOT"]),
                facility=TEMPORARY_HOUSING,
                source="서울 열린데이터광장 OA-21064 이재민임시주거시설",
                address="; ".join(r.get("DADDR") or "" for r in rooms),
                underground=underground,
            )
        )
    report["buildings"] = len(shelters)
    report["underground_buildings"] = sum(s.underground for s in shelters)
    return shelters, report


def osm_open_space_proxy(path: Path) -> list[Shelter]:
    """Earthquake proxy until DSSP-IF-00103 is approved (VALIDATION V2): OSM parks >= 2,000 m2."""
    con = sqlite3.connect(path)
    rows = con.execute("SELECT id, name, lat, lon FROM shelter").fetchall()
    con.close()
    return [
        Shelter(id=i, country="KR", name=n, lat=la, lon=lo, facility=OUTDOOR_OPEN_SPACE,
                source="OpenStreetMap leisure=park (proxy until official data is approved)")
        for i, n, la, lo in rows
    ]
