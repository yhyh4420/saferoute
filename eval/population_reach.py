"""Population-weighted walking time to the nearest suitable evacuation site.

Uses WorldPop 2020 constrained 100m rasters so mountain areas with nobody living
there do not inflate the "far from a shelter" share.

Usage: python -m eval.population_reach --city 高知県高知市 --place "Kochi, Kochi, Japan" --raster jpn
"""

import argparse
import json

import networkx as nx
import numpy as np
import osmnx as ox
import pandas as pd
import rasterio
from rasterio.mask import mask

from eval.hazard_divergence import GSI_CSV, HAZARDS, RAW, ROOT, WALK_SPEED_MPS, haversine_tree, load_graph

EARTH_RADIUS_M = 6_371_000
THRESHOLDS_MIN = (5, 10, 15, 20)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--city", default="高知県高知市")
    ap.add_argument("--place", default="Kochi, Kochi, Japan")
    ap.add_argument("--raster", default="jpn", help="WorldPop ISO3 code, lower case")
    args = ap.parse_args()

    g = load_graph(args.place)
    u = g.to_undirected(as_view=True)
    nodes = np.array(list(g.nodes))
    node_tree = haversine_tree(
        np.array([g.nodes[n]["y"] for n in nodes]), np.array([g.nodes[n]["x"] for n in nodes])
    )

    boundary = ox.geocode_to_gdf(args.place)
    with rasterio.open(RAW / "pop" / f"{args.raster}_ppp_2020_constrained.tif") as r:
        img, transform = mask(r, boundary.to_crs(r.crs).geometry, crop=True)
    band = img[0]
    rows, cols = np.where(band > 0)
    pop = band[rows, cols].astype(float)
    xs, ys = rasterio.transform.xy(transform, rows, cols)
    d, i = node_tree.query(np.radians(np.c_[ys, xs]), k=1)
    cell_node = nodes[i[:, 0]]
    cell_snap_m = d[:, 0] * EARTH_RADIUS_M

    df = pd.read_csv(GSI_CSV, encoding="utf-8-sig", dtype=str)
    df = df[df["都道府県名及び市町村名"] == args.city]
    _, si = node_tree.query(np.radians(df[["緯度", "経度"]].astype(float).values), k=1)
    df = df.assign(node=nodes[si[:, 0]])

    total = pop.sum()
    report = {"city": args.city, "population_worldpop_2020": round(total), "hazards": {}}
    for hz, col in HAZARDS.items():
        sites = df[df[col] == "1"]
        if sites.empty:
            continue
        dist = nx.multi_source_dijkstra_path_length(u, set(sites["node"]), weight="length")
        minutes = (np.array([dist.get(n, np.inf) for n in cell_node]) + cell_snap_m) / WALK_SPEED_MPS / 60
        report["hazards"][hz] = {
            "sites": len(sites),
            "pop_weighted_median_min": round(float(np.interp(0.5, np.cumsum(pop[np.argsort(minutes)]) / total, np.sort(minutes))), 1),
            **{
                f"pop_over_{t}min": {"people": round(pop[minutes > t].sum()), "pct": round(pop[minutes > t].sum() / total * 100, 1)}
                for t in THRESHOLDS_MIN
            },
        }

    out = ROOT / "pipeline" / "out" / f"population_reach_{args.place.split(',')[0].lower()}.json"
    out.write_text(json.dumps(report, indent=2, ensure_ascii=False))
    print(json.dumps(report, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
