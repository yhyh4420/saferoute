"""Korea (Seoul) adapter: official shelter datasets -> common schema.

- Flood destinations: 서울 열린데이터광장 OA-21064 이재민임시주거시설 (service TlInetqkP).
- Earthquake destinations: 재난안전데이터 공유플랫폼 DSSP-IF-00103 지진옥외대피소 (KR_EQ_SHELTER_KEY).
- Flood route penalty: DSSP-IF-00117 침수흔적도 polygons (KR_FLOOD_TRACE_KEY).

Source values (names, coordinates, ids) are copied unchanged — KOGL type 3 forbids
modification (VALIDATION V17). We only filter, merge rooms into buildings, and convert format.
"""

import json
import math
import re
import sqlite3
import urllib.parse
import urllib.request
from collections import defaultdict
from pathlib import Path

from pipeline.schema import OUTDOOR_OPEN_SPACE, TEMPORARY_HOUSING, Shelter

RAW = Path(__file__).resolve().parents[1] / "raw" / "kr"
SEOUL_TEMP_HOUSING_JSON = RAW / "seoul_temp_housing_OA-21064.json"
EQ_OUTDOOR_JSON = RAW / "eq_outdoor_DSSP-IF-00103.json"
FLOOD_TRACE_JSON = RAW / "flood_trace_DSSP-IF-00117.json"
SEOUL_CTPV = "11"
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


def download_dssp(api: str, service_key: str, path: Path, rows_per_page: int = 1000) -> list[dict]:
    """Page through a 재난안전데이터 공유플랫폼 V2 API and save the raw rows unchanged."""
    rows: list[dict] = []
    page = 1
    while True:
        q = urllib.parse.urlencode({"serviceKey": service_key, "numOfRows": rows_per_page, "pageNo": page,
                                    "returnType": "json"})
        body = json.loads(urllib.request.urlopen(f"https://www.safetydata.go.kr/V2/api/{api}?{q}", timeout=120).read())
        rows += body.get("body") or []
        if not body.get("body") or len(rows) >= int(body["totalCount"]):
            break
        page += 1
    path.write_text(json.dumps(rows, ensure_ascii=False))
    return rows


def web_mercator_to_wgs84(x: float, y: float) -> tuple[float, float]:
    """EPSG:3857 -> (lat, lon)."""
    lon = math.degrees(x / 6378137.0)
    lat = math.degrees(2 * math.atan(math.exp(y / 6378137.0)) - math.pi / 2)
    return lat, lon


def seoul_eq_outdoor(rows: list[dict] | None = None) -> tuple[list[Shelter], dict]:
    """Official 지진옥외대피소 in Seoul (DSSP-IF-00103), one shelter per THINGS_MNG_NO."""
    rows = rows if rows is not None else json.loads(EQ_OUTDOOR_JSON.read_text())
    report = {"rows": len(rows), "other_region": 0, "duplicate_id": 0, "dropped_out_of_bounds": [],
              "underground": 0}
    seen, shelters = set(), []
    for r in rows:
        if r.get("CTPV_NM") != "서울특별시" and not str(r.get("STDG_SGG_CD", "")).startswith(SEOUL_CTPV):
            report["other_region"] += 1
            continue
        if r["THINGS_MNG_NO"] in seen:
            report["duplicate_id"] += 1
            continue
        seen.add(r["THINGS_MNG_NO"])
        lat, lon = web_mercator_to_wgs84(float(r["XMAP_CRTS"]), float(r["YMAP_CRTS"]))
        if not _in_seoul(lat, lon):
            report["dropped_out_of_bounds"].append(r["THINGS_NM"])
            continue
        underground = str(r.get("UDGD_YN", "0")).strip() in ("1", "Y")
        report["underground"] += underground
        shelters.append(
            Shelter(
                id=f"KR:DSSP-IF-00103:{r['THINGS_MNG_NO']}",
                country="KR",
                name=r["THINGS_NM"],
                lat=lat,
                lon=lon,
                facility=OUTDOOR_OPEN_SPACE,
                source="재난안전데이터 공유플랫폼 DSSP-IF-00103 행정안전부_지진옥외대피소",
                address=" ".join(str(r.get(k) or "").strip() for k in ("SGG_NM", "ROAD_NM", "ADDR_MNO")),
                underground=underground,
            )
        )
    report["shelters"] = len(shelters)
    return shelters, report


def mark_flood_trace(g, rows: list[dict] | None = None) -> dict:
    """Set g.flood_trace for walking edges whose midpoint lies in a Seoul 침수흔적도 polygon."""
    import numpy as np
    import shapely
    from shapely.ops import transform

    rows = rows if rows is not None else json.loads(FLOOD_TRACE_JSON.read_text())
    to_wgs = np.vectorize(lambda x, y: web_mercator_to_wgs84(x, y)[::-1])
    polys = [transform(lambda x, y: to_wgs(x, y), shapely.from_wkt(r["GEOM"]))
             for r in rows if str(r.get("STDG_CTPV_CD")) == SEOUL_CTPV]
    tree = shapely.STRtree(polys)
    mid = shapely.points((g.lon[g.u] + g.lon[g.v]) / 2, (g.lat[g.u] + g.lat[g.v]) / 2)
    hit, _ = tree.query(mid, predicate="within")
    g.flood_trace = np.zeros(len(g.u), dtype=bool)
    g.flood_trace[np.unique(hit)] = True
    return {"polygons": len(polys), "edges_in_trace": int(g.flood_trace.sum()), "edges": len(g.u)}


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


GAP_FILL_M = 1000  # official 지진옥외대피소 covers 18 of 25 Seoul districts (VALIDATION V28)


def seoul_eq_with_gap_fill(osm_path: Path) -> tuple[list[Shelter], dict]:
    """Official earthquake shelters, plus OSM parks only where no official one is within GAP_FILL_M."""
    import numpy as np
    from sklearn.neighbors import BallTree

    official, report = seoul_eq_outdoor()
    parks = osm_open_space_proxy(osm_path)
    tree = BallTree(np.radians([[s.lat, s.lon] for s in official]), metric="haversine")
    d, _ = tree.query(np.radians([[p.lat, p.lon] for p in parks]), k=1)
    gap = [p for p, dist in zip(parks, d[:, 0] * 6_371_000) if dist > GAP_FILL_M]
    report["gap_fill_parks"] = len(gap)
    return official + gap, report


def osm_open_space_proxy(path: Path) -> list[Shelter]:
    """OSM parks >= 2,000 m2 (VALIDATION V2); used only to fill gaps in the official data."""
    con = sqlite3.connect(path)
    rows = con.execute("SELECT id, name, lat, lon FROM shelter").fetchall()
    con.close()
    return [
        Shelter(id=i, country="KR", name=n or "이름 없는 공원", lat=la, lon=lo, facility=OUTDOOR_OPEN_SPACE,
                source="OpenStreetMap 공원 (공식 지정 아님 · 공식 데이터 없는 지역 보완)")
        for i, n, la, lo in rows
    ]
