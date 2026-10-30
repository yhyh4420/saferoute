"""Per-hazard shortest-path trees towards suitable shelters, written as a pack the app memory-maps.

For every walking-graph node and every (hazard, profile) the pack stores the next node towards
the nearest suitable shelter, the remaining walking distance and that shelter's index. The app
follows `next` pointers to draw the route; no routing happens on the device.

Pack format v2 (little-endian), see docs/PACK_FORMAT.md:
  b"SRPK" | u16 version | u32 header_len | header JSON (utf-8)
  i32 lat_e6[n] | i32 lon_e6[n]
  per tree in header["trees"] order: i32 next[n] | u16 dist_m[n] | u16 shelter[n]
  grid: u32 cells | i64 key[cells] (sorted) | i32 start[cells + 1] | i32 node[n]
The grid is pre-built so the app maps it instead of indexing 170k nodes at startup
(v1 took ~900 ms on an emulator).
"""

import json
import struct
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from scipy.sparse.csgraph import dijkstra
from sklearn.neighbors import BallTree

from pipeline.graph import WalkGraph
from pipeline.schema import HAZARD_RULES, Shelter, suitable

EARTH_RADIUS_M = 6_371_000
UNDERGROUND_PENALTY = 20.0  # flood: walk underground only if there is no other way out
FLOOD_TRACE_PENALTY = 3.0  # flood: prefer streets that did not flood before, without forbidding them
NO_ROUTE = 0xFFFF
MAGIC, VERSION = b"SRPK", 2
GRID_CELL_DEG = 0.002  # ~220 m; must match RoutePack.kt
GRID_KEY = 1_000_000
PROFILES = ("default", "stepfree")
UNLOCKED = "unlocked"  # never towards a may_lock destination (night/holiday fallback)
UNLOCKED_PROFILES = (UNLOCKED, "unlocked_stepfree")  # the fallback must respect step-free too


def is_unlocked(profile: str) -> bool:
    return profile.startswith(UNLOCKED)


@dataclass
class Tree:
    hazard: str
    profile: str
    next: np.ndarray  # int32, -1 = unreachable, self = at a shelter
    dist_m: np.ndarray  # uint16, NO_ROUTE = unreachable
    shelter: np.ndarray  # uint16 shelter idx, NO_ROUTE = unreachable


def edge_weight(g: WalkGraph, hazard: str, profile: str) -> np.ndarray:
    w = g.length.copy()
    if "underground" in HAZARD_RULES[hazard].avoid_edges:
        w[g.underground] *= UNDERGROUND_PENALTY
    if "flood_trace" in HAZARD_RULES[hazard].avoid_edges and g.flood_trace is not None:
        w[g.flood_trace] *= FLOOD_TRACE_PENALTY
    if profile.endswith("stepfree"):
        w[g.steps] = np.inf
    return w


def snap(g: WalkGraph, lat: np.ndarray, lon: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    tree = BallTree(np.radians(np.c_[g.lat, g.lon]), metric="haversine")
    d, i = tree.query(np.radians(np.c_[lat, lon]), k=1)
    return i[:, 0], d[:, 0] * EARTH_RADIUS_M


def build_tree(g: WalkGraph, shelters: list[Shelter], hazard: str, profile: str) -> Tree:
    idx = [k for k, s in enumerate(shelters) if suitable(s, hazard) and not (is_unlocked(profile) and s.may_lock)]
    nodes, _ = snap(g, np.array([shelters[k].lat for k in idx]), np.array([shelters[k].lon for k in idx]))
    node_shelter: dict[int, int] = {}
    for k, n in zip(idx, nodes):
        node_shelter.setdefault(int(n), k)
    sources = np.array(sorted(node_shelter))

    w = edge_weight(g, hazard, profile)
    cost, pred, src = dijkstra(g.matrix(w), directed=False, indices=sources,
                               min_only=True, return_predecessors=True)
    n = len(g.node_ids)
    reach = np.isfinite(cost)
    nxt = np.where(reach, pred, -1).astype(np.int32)
    nxt[sources] = sources  # pred is -9999 at the sources themselves

    # Real walking metres along the chosen path (cost includes penalties).
    lookup = {}
    for a, b, length in zip(g.u, g.v, g.length):
        lookup[(a, b)] = lookup[(b, a)] = length
    metres = np.full(n, np.inf)
    metres[sources] = 0.0
    for node in np.argsort(cost):
        if not reach[node] or metres[node] == 0.0:
            continue
        metres[node] = metres[nxt[node]] + lookup[(node, nxt[node])]

    shelter = np.full(n, NO_ROUTE, dtype=np.uint16)
    shelter[reach] = [node_shelter[int(s)] for s in src[reach]]
    dist = np.where(reach, np.minimum(np.round(metres), NO_ROUTE - 1), NO_ROUTE).astype(np.uint16)
    return Tree(hazard, profile, nxt, dist, shelter)


def write_pack(path: Path, g: WalkGraph, trees: list[Tree], meta: dict) -> None:
    header = dict(meta, version=VERSION, nodes=len(g.node_ids), coord_scale=1_000_000,
                  grid_cell_deg=GRID_CELL_DEG, grid_key=GRID_KEY,
                  trees=[{"hazard": t.hazard, "profile": t.profile} for t in trees])
    raw = json.dumps(header, ensure_ascii=False).encode()
    with open(path, "wb") as f:
        f.write(MAGIC + struct.pack("<HI", VERSION, len(raw)) + raw)
        f.write(np.round(g.lat * 1e6).astype("<i4").tobytes())
        f.write(np.round(g.lon * 1e6).astype("<i4").tobytes())
        for t in trees:
            f.write(t.next.astype("<i4").tobytes())
            f.write(t.dist_m.astype("<u2").tobytes())
            f.write(t.shelter.astype("<u2").tobytes())
        keys = (np.floor(g.lat / GRID_CELL_DEG).astype(np.int64) * GRID_KEY
                + np.floor(g.lon / GRID_CELL_DEG).astype(np.int64))
        order = np.argsort(keys, kind="stable")
        cells, start = np.unique(keys[order], return_index=True)
        f.write(struct.pack("<I", len(cells)))
        f.write(cells.astype("<i8").tobytes())
        f.write(np.append(start, len(order)).astype("<i4").tobytes())
        f.write(order.astype("<i4").tobytes())


def check(trees: list[Tree], shelters: list[Shelter]) -> dict:
    """Coverage and rule-violation report used by `make verify`."""
    out = {}
    for t in trees:
        reach = t.next >= 0
        bad = {int(s) for s in np.unique(t.shelter[reach]) if not suitable(shelters[int(s)], t.hazard)
               or (is_unlocked(t.profile) and shelters[int(s)].may_lock)}
        out[f"{t.hazard}/{t.profile}"] = {
            "coverage_pct": round(float(reach.mean() * 100), 2),
            "rule_violations": len(bad),
            "median_walk_m": int(np.median(t.dist_m[reach])),
        }
    return out
