"""Pitch numbers from the built Seoul pack (population-weighted, WorldPop 2020).

- share of people whose nearest earthquake destination differs from the nearest flood destination
- flood: share whose route changes because underground passages are avoided
- flood: share for which the straight-line nearest shelter is not the walking-nearest one

- flood: share whose shortest walk would cross a recorded flood area (침수흔적도) without the rule

Usage: python -m eval.seoul_divergence
"""

import json
import struct

import numpy as np
import osmnx as ox
import rasterio
from rasterio.mask import mask
from scipy.sparse.csgraph import dijkstra
from sklearn.neighbors import BallTree

from pipeline import graph as walkgraph
from pipeline.build_pack import CITIES, OUT, RAW
from pipeline.oracle import NO_ROUTE, snap
from pipeline.schema import suitable

EARTH_RADIUS_M = 6_371_000


def read_trees(path):
    with open(path, "rb") as f:
        f.read(4)
        _, hlen = struct.unpack("<HI", f.read(6))
        header = json.loads(f.read(hlen))
        n = header["nodes"]
        f.read(8 * n)
        trees = {}
        for t in header["trees"]:
            nxt = np.frombuffer(f.read(4 * n), "<i4")
            dist = np.frombuffer(f.read(2 * n), "<u2")
            shel = np.frombuffer(f.read(2 * n), "<u2")
            trees[(t["hazard"], t["profile"])] = (nxt, dist, shel)
    return trees


def main() -> None:
    city = CITIES["seoul"]
    g = walkgraph.load(city["graph"])
    city["edges"](g)
    shelters = city["shelters"]()
    trees = read_trees(OUT / "pack_seoul.bin")

    boundary = ox.geocode_to_gdf(city["place"])
    with rasterio.open(RAW / "pop" / "kor_ppp_2020_constrained.tif") as r:
        img, tf = mask(r, boundary.to_crs(r.crs).geometry, crop=True)
    band = img[0]
    rows, cols = np.where(band > 0)
    pop = band[rows, cols].astype(float)
    xs, ys = rasterio.transform.xy(tf, rows, cols)
    cell_node, _ = snap(g, np.array(ys), np.array(xs))
    total = pop.sum()

    eq = trees[("earthquake", "default")][2][cell_node]
    fl = trees[("flood", "default")][2][cell_node]
    ok = (eq != NO_ROUTE) & (fl != NO_ROUTE)
    is_osm = np.array([s.source.startswith("OpenStreetMap") for s in shelters] + [False])
    gap = (eq != NO_ROUTE) & is_osm[np.minimum(eq, len(shelters))]
    # The two destination sets are disjoint (parks vs housing), so "different shelter" is always
    # true; what matters to a person is whether they must walk a different way.
    def first_bearing(tree_next):
        step = np.asarray(cell_node)
        for _ in range(5):  # ~5 nodes ahead (median edge 38 m) so tiny jogs do not count
            nxt = tree_next[step]
            step = np.where(nxt >= 0, nxt, step)
        dy = g.lat[step] - g.lat[cell_node]
        dx = (g.lon[step] - g.lon[cell_node]) * np.cos(np.radians(g.lat[cell_node]))
        return np.degrees(np.arctan2(dx, dy))

    b_eq = first_bearing(trees[("earthquake", "default")][0])
    b_fl = first_bearing(trees[("flood", "default")][0])
    turn = np.abs((b_eq - b_fl + 180) % 360 - 180)
    differ = ok & (turn > 90)

    # Flood without the underground penalty: whose shortest walk would pass underground?
    idx = [k for k, s in enumerate(shelters) if suitable(s, "flood")]
    src_nodes, _ = snap(g, np.array([shelters[k].lat for k in idx]), np.array([shelters[k].lon for k in idx]))
    cost, pred, _ = dijkstra(g.matrix(g.length), directed=False, indices=np.unique(src_nodes),
                             min_only=True, return_predecessors=True)
    under_edge = {}
    for a, b, u in zip(g.u, g.v, g.underground):
        under_edge[(a, b)] = under_edge[(b, a)] = u
    trace_edge = {}
    for a, b, t in zip(g.u, g.v, g.flood_trace):
        trace_edge[(a, b)] = trace_edge[(b, a)] = t
    passes = np.zeros(len(g.node_ids), bool)
    crosses = np.zeros(len(g.node_ids), bool)
    for node in np.argsort(cost):
        p = pred[node]
        if p >= 0:
            passes[node] = passes[p] or under_edge[(node, p)]
            crosses[node] = crosses[p] or trace_edge[(node, p)]
    would_go_under = passes[cell_node]
    would_cross_trace = crosses[cell_node]

    # Straight-line nearest flood shelter vs walking-nearest.
    lat_s = np.array([shelters[k].lat for k in idx]); lon_s = np.array([shelters[k].lon for k in idx])
    _, straight = BallTree(np.radians(np.c_[lat_s, lon_s]), metric="haversine").query(np.radians(np.c_[ys, xs]), k=1)
    straight_idx = np.array(idx)[straight[:, 0]]
    walk_idx = fl.astype(int)
    mismatch = (fl != NO_ROUTE) & (straight_idx != walk_idx)

    report = {
        "population_worldpop_2020": round(total),
        "earthquake_source": "DSSP-IF-00103 행정안전부_지진옥외대피소 (official) + OSM parks only where none within 1 km",
        "earthquake_destination_is_osm_gap_fill": {"people": round(pop[gap].sum()), "pct": round(pop[gap].sum() / total * 100, 1)},
        "eq_vs_flood_initial_direction_differs_over_90deg": {"people": round(pop[differ].sum()), "pct": round(pop[differ].sum() / total * 100, 1)},
        "flood_route_would_pass_underground_without_rule": {"people": round(pop[would_go_under].sum()), "pct": round(pop[would_go_under].sum() / total * 100, 1)},
        "flood_route_would_cross_recorded_flood_area_without_rule": {"people": round(pop[would_cross_trace].sum()), "pct": round(pop[would_cross_trace].sum() / total * 100, 1)},
        "flood_straight_line_nearest_wrong": {"people": round(pop[mismatch].sum()), "pct": round(pop[mismatch].sum() / total * 100, 1)},
    }
    (OUT / "seoul_divergence.json").write_text(json.dumps(report, indent=2, ensure_ascii=False))
    print(json.dumps(report, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
