"""Common shelter schema shared by every country adapter, plus the hazard rule table.

Hazards are data-driven: a hazard exists in a pack only if a rule row exists for it
and the country adapter supplies destinations for it. Destination choice is decided
by this rule table (official guidance), never by an LLM.
"""

import json
import sqlite3
from dataclasses import asdict, dataclass, field
from pathlib import Path

# Facility types a country adapter may emit.
OUTDOOR_OPEN_SPACE = "outdoor_open_space"  # 지진 옥외대피장소 (parks, school yards)
TEMPORARY_HOUSING = "temporary_housing"  # 이재민 임시주거시설
DESIGNATED_REFUGE = "designated_refuge"  # a site designated as safe *for* a given hazard (e.g. JP 指定緊急避難場所)
ASSEMBLY_POINT = "assembly_point"  # generic emergency assembly point (OSM)

# What a country's per-hazard flag means (VALIDATION V21). Only "refuge" flags may
# become destinations; "shelter_capacity" means "can house evacuees after the event".
FLAG_REFUGE = "refuge"
FLAG_SHELTER_CAPACITY = "shelter_capacity"


@dataclass(frozen=True)
class HazardRule:
    hazard: str
    facilities: frozenset[str]  # destination facility types
    exclude_underground: bool  # drop underground destinations
    avoid_edges: frozenset[str]  # edge attributes penalised in the walking graph
    verb_ko: str
    verb_en: str
    forbid_ko: str
    forbid_en: str
    source: str


# Sources: docs/VALIDATION.md V5 (행정안전부 국민행동요령).
HAZARD_RULES: dict[str, HazardRule] = {
    r.hazard: r
    for r in [
        HazardRule(
            hazard="earthquake",
            facilities=frozenset({OUTDOOR_OPEN_SPACE, ASSEMBLY_POINT, DESIGNATED_REFUGE}),
            exclude_underground=False,
            avoid_edges=frozenset(),
            verb_ko="흔들림이 멈추면 건물 밖 넓은 곳으로",
            verb_en="When shaking stops, go outside to open space",
            forbid_ko="엘리베이터·차량 이용 금지, 건물과 거리 두기",
            forbid_en="No elevators or cars; keep away from buildings",
            source="행정안전부 지진 국민행동요령 (weather.go.kr/pews/man/m1.html)",
        ),
        HazardRule(
            hazard="flood",
            facilities=frozenset({TEMPORARY_HOUSING, DESIGNATED_REFUGE}),
            exclude_underground=True,
            avoid_edges=frozenset({"underground"}),
            verb_ko="높은 곳으로 빨리, 지하는 피하기",
            verb_en="Move to higher ground quickly; avoid anything underground",
            forbid_ko="지하주차장 진입·침수 도로 운전 금지",
            forbid_en="Never enter underground parking or drive through flood water",
            source="행정안전부 홍수·지하공간 침수 국민행동요령",
        ),
    ]
}


@dataclass
class Shelter:
    id: str  # "<country>:<source>:<source id>" — source ids are kept unchanged (KOGL type 3)
    country: str  # ISO 3166-1 alpha-2
    name: str
    lat: float
    lon: float
    facility: str
    source: str  # dataset name / URL
    address: str = ""
    capacity: int | None = None
    underground: bool = False
    flag_semantics: str = FLAG_REFUGE
    updated_at: str = ""  # source modification date (ISO)
    hazards: list[str] = field(default_factory=list)

    def __post_init__(self) -> None:
        if not self.hazards:
            self.hazards = [h for h in HAZARD_RULES if suitable(self, h)]


def suitable(s: Shelter, hazard: str) -> bool:
    rule = HAZARD_RULES[hazard]
    if s.flag_semantics != FLAG_REFUGE or s.facility not in rule.facilities:
        return False
    return not (rule.exclude_underground and s.underground)


def write_pack(shelters: list[Shelter], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.unlink(missing_ok=True)
    con = sqlite3.connect(path)
    con.execute(
        """CREATE TABLE shelter (
            idx INTEGER PRIMARY KEY, id TEXT UNIQUE, country TEXT, name TEXT, lat REAL, lon REAL,
            facility TEXT, hazards TEXT, address TEXT, capacity INTEGER, underground INTEGER,
            source TEXT, updated_at TEXT)"""
    )
    con.execute(
        """CREATE TABLE hazard_rule (
            hazard TEXT PRIMARY KEY, verb_ko TEXT, verb_en TEXT, forbid_ko TEXT, forbid_en TEXT,
            source TEXT, rule_json TEXT)"""
    )
    rows = []
    for i, s in enumerate(shelters):
        d = asdict(s)
        d["idx"] = i
        d["hazards"] = ",".join(s.hazards)
        d["underground"] = int(s.underground)
        rows.append(d)
    con.executemany(
        """INSERT INTO shelter VALUES
           (:idx, :id, :country, :name, :lat, :lon, :facility, :hazards, :address,
            :capacity, :underground, :source, :updated_at)""",
        rows,
    )
    con.executemany(
        "INSERT INTO hazard_rule VALUES (?, ?, ?, ?, ?, ?, ?)",
        [
            (r.hazard, r.verb_ko, r.verb_en, r.forbid_ko, r.forbid_en, r.source,
             json.dumps({"facilities": sorted(r.facilities), "exclude_underground": r.exclude_underground,
                         "avoid_edges": sorted(r.avoid_edges)}))
            for r in HAZARD_RULES.values()
        ],
    )
    con.commit()
    con.close()
