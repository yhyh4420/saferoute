"""V8/V9 on official data: Japanese designated emergency evacuation sites (GSI).

For one municipality, measures on the OSM walking network:
- per hazard: multi-source Dijkstra time, coverage, walking-time distribution
- how often the nearest earthquake site differs from the nearest tsunami site
- how often "straight-line nearest" differs from "network nearest", and the extra walk

Usage: python -m eval.hazard_divergence --city 高知県高知市 --place "Kochi, Kochi, Japan"
"""

import argparse
import json
import time
from pathlib import Path

import networkx as nx
import numpy as np
import osmnx as ox
import pandas as pd
from sklearn.neighbors import BallTree

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "pipeline" / "raw"
GSI_CSV = RAW / "jp" / "mergeFromCity_2.csv"
WALK_SPEED_MPS = 1.2
HAZARDS = {"earthquake": "地震", "tsunami": "津波", "flood": "洪水"}
OVERPASS_MIRRORS = [
    "https://overpass-api.de/api",
    "https://overpass.kumi.systems/api",
    "https://overpass.private.coffee/api",
    "https://maps.mail.ru/osm/tools/overpass/api",
]


def load_graph(place: str):
    cache = RAW / f"{place.split(',')[0].lower().replace(' ', '_')}_walk.graphml"
    if cache.exists():
        return ox.load_graphml(cache)
    last_err = None
    for url in OVERPASS_MIRRORS:
        ox.settings.overpass_url = url
        try:
            g = ox.graph_from_place(place, network_type="walk")
            ox.save_graphml(g, cache)
            return g
        except Exception as e:  # mirror down or rate-limited: try the next one
            last_err = e
    raise last_err


def haversine_tree(lat, lon) -> BallTree:
    return BallTree(np.radians(np.c_[lat, lon]), metric="haversine")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--city", default="高知県高知市")
    ap.add_argument("--place", default="Kochi, Kochi, Japan")
    ap.add_argument("--samples", type=int, default=2000)
    args = ap.parse_args()

    g = load_graph(args.place)
    u = g.to_undirected(as_view=True)
    nodes = np.array(list(g.nodes))
    nlat = np.array([g.nodes[n]["y"] for n in nodes])
    nlon = np.array([g.nodes[n]["x"] for n in nodes])
    node_tree = haversine_tree(nlat, nlon)

    df = pd.read_csv(GSI_CSV, encoding="utf-8-sig", dtype=str)
    df = df[df["都道府県名及び市町村名"] == args.city].copy()
    df["lat"] = df["緯度"].astype(float)
    df["lon"] = df["経度"].astype(float)
    d, i = node_tree.query(np.radians(df[["lat", "lon"]].values), k=1)
    df["node"] = nodes[i[:, 0]]
    snap_m = d[:, 0] * 6_371_000

    report = {
        "city": args.city,
        "nodes": len(nodes),
        "sites": len(df),
        "site_snap_m": {"median": round(float(np.median(snap_m))), "p90": round(float(np.percentile(snap_m, 90)))},
        "hazards": {},
    }
    trees = {}
    for hz, col in HAZARDS.items():
        sites = df[df[col] == "1"]
        if sites.empty:
            continue
        t0 = time.time()
        dist, paths = nx.multi_source_dijkstra(u, set(sites["node"]), weight="length")
        elapsed = time.time() - t0
        trees[hz] = (dist, paths, sites)
        minutes = np.array([dist.get(n, np.inf) for n in nodes]) / WALK_SPEED_MPS / 60
        reach = minutes[np.isfinite(minutes)]
        report["hazards"][hz] = {
            "sites": len(sites),
            "dijkstra_s": round(elapsed, 2),
            "coverage_pct": round(float(np.isfinite(minutes).mean() * 100), 2),
            "walk_min_median": round(float(np.median(reach)), 1),
            "pct_nodes_over_10min": round(float((minutes > 10).mean() * 100), 1),
            "pct_nodes_over_15min": round(float((minutes > 15).mean() * 100), 1),
        }

    if {"earthquake", "tsunami"} <= trees.keys():
        pe, pt = trees["earthquake"][1], trees["tsunami"][1]
        both = [n for n in nodes if n in pe and n in pt]
        differs = np.mean([pe[n][0] != pt[n][0] for n in both])
        report["pct_nodes_eq_vs_tsunami_target_differs"] = round(float(differs * 100), 1)

    rng = np.random.default_rng(0)
    sample = rng.choice(len(nodes), min(args.samples, len(nodes)), replace=False)
    for hz, (dist, paths, sites) in trees.items():
        _, si = haversine_tree(sites["lat"].values, sites["lon"].values).query(
            np.radians(np.c_[nlat[sample], nlon[sample]]), k=1
        )
        mismatch, extra = 0, []
        for j, k in enumerate(sample):
            n = nodes[k]
            if n not in paths:
                continue
            straight_node = sites["node"].values[si[j, 0]]
            if straight_node != paths[n][0]:
                mismatch += 1
                try:
                    extra.append(nx.shortest_path_length(u, n, straight_node, weight="length") - dist[n])
                except nx.NetworkXNoPath:
                    pass
        extra = np.array(extra) if extra else np.array([0.0])
        report["hazards"][hz]["straight_vs_network"] = {
            "mismatch_pct": round(mismatch / len(sample) * 100, 1),
            "extra_walk_m_median": round(float(np.median(extra))),
            "extra_walk_m_p90": round(float(np.percentile(extra, 90))),
        }

    out = ROOT / "pipeline" / "out" / f"hazard_divergence_{args.place.split(',')[0].lower()}.json"
    out.write_text(json.dumps(report, indent=2, ensure_ascii=False))
    print(json.dumps(report, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
