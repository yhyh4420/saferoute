"""V4c: walking time to the nearest tsunami evacuation site for people who live
inside the official tsunami inundation assumption zone (国土数値情報 A40).

Usage: python -m eval.tsunami_zone_reach --city 高知県高知市 --place "Kochi, Kochi, Japan" --a40 pipeline/raw/jp/a40/A40-16_39.shp
"""

import argparse
import json

import geopandas as gpd
import networkx as nx
import numpy as np
import osmnx as ox
import pandas as pd
import rasterio
from rasterio.features import geometry_mask
from rasterio.mask import mask

from eval.hazard_divergence import GSI_CSV, RAW, ROOT, WALK_SPEED_MPS, haversine_tree, load_graph
from eval.population_reach import EARTH_RADIUS_M, THRESHOLDS_MIN


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--city", default="高知県高知市")
    ap.add_argument("--place", default="Kochi, Kochi, Japan")
    ap.add_argument("--a40", default=str(RAW / "jp" / "a40" / "A40-16_39.shp"))
    ap.add_argument("--raster", default="jpn")
    ap.add_argument("--speed", type=float, default=WALK_SPEED_MPS, help="m/s; FDMA guide: 1.0 general, 0.5 mobility-impaired")
    ap.add_argument("--delay", type=float, default=0.0, help="minutes before people start moving; FDMA guide: 2-5")
    args = ap.parse_args()

    boundary = ox.geocode_to_gdf(args.place)
    zone = gpd.read_file(args.a40).to_crs(4326)
    zone = zone[zone.intersects(boundary.geometry.iloc[0])]

    with rasterio.open(RAW / "pop" / f"{args.raster}_ppp_2020_constrained.tif") as r:
        img, transform = mask(r, boundary.to_crs(r.crs).geometry, crop=True)
    band = img[0]
    city_cells = band > 0
    in_zone = ~geometry_mask(zone.geometry, out_shape=band.shape, transform=transform, all_touched=False)
    rows, cols = np.where(city_cells & in_zone)
    pop = band[rows, cols].astype(float)
    xs, ys = rasterio.transform.xy(transform, rows, cols)

    g = load_graph(args.place)
    nodes = np.array(list(g.nodes))
    node_tree = haversine_tree(
        np.array([g.nodes[n]["y"] for n in nodes]), np.array([g.nodes[n]["x"] for n in nodes])
    )
    d, i = node_tree.query(np.radians(np.c_[ys, xs]), k=1)
    cell_node = nodes[i[:, 0]]

    sites = pd.read_csv(GSI_CSV, encoding="utf-8-sig", dtype=str)
    sites = sites[(sites["都道府県名及び市町村名"] == args.city) & (sites["津波"] == "1")]
    _, si = node_tree.query(np.radians(sites[["緯度", "経度"]].astype(float).values), k=1)
    dist = nx.multi_source_dijkstra_path_length(g.to_undirected(as_view=True), set(nodes[si[:, 0]]), weight="length")
    minutes = (np.array([dist.get(n, np.inf) for n in cell_node]) + d[:, 0] * EARTH_RADIUS_M) / args.speed / 60 + args.delay

    total = pop.sum()
    report = {
        "city": args.city,
        "zone_source": args.a40,
        "zone_polygons_in_city": len(zone),
        "city_population": round(float(band[city_cells].sum())),
        "population_in_inundation_zone": round(total),
        "tsunami_sites": len(sites),
        "speed_mps": args.speed,
        "start_delay_min": args.delay,
        "pop_weighted_median_min": round(float(np.interp(0.5, np.cumsum(pop[np.argsort(minutes)]) / total, np.sort(minutes))), 1),
        **{
            f"zone_pop_over_{t}min": {"people": round(pop[minutes > t].sum()), "pct": round(pop[minutes > t].sum() / total * 100, 1)}
            for t in THRESHOLDS_MIN
        },
    }
    out = ROOT / "pipeline" / "out" / f"tsunami_zone_reach_{args.place.split(',')[0].lower()}_v{args.speed}_d{args.delay:g}.json"
    out.write_text(json.dumps(report, indent=2, ensure_ascii=False))
    print(json.dumps(report, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
