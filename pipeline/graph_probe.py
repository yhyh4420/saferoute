"""T1.5: Download a city's OSM walking network and estimate the offline pack size.

The device only needs node coordinates plus, per hazard, a pointer to the next
node on the shortest path to a suitable shelter. Edges are not shipped: the
route polyline is reconstructed by following next-node pointers.

Usage: python -m pipeline.graph_probe --place "Seoul, South Korea"
"""

import argparse
import json
import time
import zlib
from pathlib import Path

import numpy as np
import osmnx as ox

RAW_DIR = Path(__file__).parent / "raw"
OUT_DIR = Path(__file__).parent / "out"

# Per node, per hazard: next_node (int32) + remaining distance in metres (uint16)
# + shelter index (uint16).
BYTES_PER_NODE_PER_HAZARD = 4 + 2 + 2
HAZARD_VARIANTS = {
    "core": ["earthquake", "tsunami", "flood"],
    "core+accessible": ["earthquake", "tsunami", "flood", "earthquake_stepfree"],
}


def load_graph(place: str):
    RAW_DIR.mkdir(exist_ok=True)
    cache = RAW_DIR / f"{place.split(',')[0].lower().replace(' ', '_')}_walk.graphml"
    if cache.exists():
        return ox.load_graphml(cache), 0.0
    t0 = time.time()
    g = ox.graph_from_place(place, network_type="walk", simplify=True)
    elapsed = time.time() - t0
    ox.save_graphml(g, cache)
    return g, elapsed


def compressed_coords_size(lat: np.ndarray, lon: np.ndarray) -> int:
    """Quantise to 1e-6 deg (~0.1 m), order spatially, delta-encode, deflate."""
    q_lat = np.round(lat * 1e6).astype(np.int64)
    q_lon = np.round(lon * 1e6).astype(np.int64)
    order = np.lexsort((q_lon, q_lat // 1000))  # coarse row-major spatial order
    d_lat = np.diff(q_lat[order], prepend=0).astype(np.int32)
    d_lon = np.diff(q_lon[order], prepend=0).astype(np.int32)
    return len(zlib.compress(np.stack([d_lat, d_lon], axis=1).tobytes(), 9))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--place", default="Seoul, South Korea")
    args = parser.parse_args()

    g, download_s = load_graph(args.place)
    n_nodes = g.number_of_nodes()
    n_edges = g.number_of_edges()
    lat = np.array([d["y"] for _, d in g.nodes(data=True)])
    lon = np.array([d["x"] for _, d in g.nodes(data=True)])
    edge_len = np.array([d.get("length", 0.0) for *_, d in g.edges(data=True)])
    steps = sum(1 for *_, d in g.edges(data=True) if d.get("highway") == "steps")

    raw_coords = n_nodes * 8
    packed_coords = compressed_coords_size(lat, lon)
    estimates = {}
    for name, hazards in HAZARD_VARIANTS.items():
        trees = n_nodes * BYTES_PER_NODE_PER_HAZARD * len(hazards)
        estimates[name] = {
            "hazards": hazards,
            "raw_mb": round((raw_coords + trees) / 1e6, 2),
            # Tree pointers compress poorly; assume ~0.8 ratio, coords measured.
            "est_compressed_mb": round((packed_coords + trees * 0.8) / 1e6, 2),
        }

    report = {
        "place": args.place,
        "download_seconds": round(download_s, 1),
        "nodes": n_nodes,
        "edges_directed": n_edges,
        "edge_length_km_total": round(edge_len.sum() / 1000, 1),
        "edge_length_m_median": round(float(np.median(edge_len)), 1),
        "steps_edges": steps,
        "bbox": [round(lat.min(), 4), round(lon.min(), 4), round(lat.max(), 4), round(lon.max(), 4)],
        "coords_raw_mb": round(raw_coords / 1e6, 2),
        "coords_compressed_mb": round(packed_coords / 1e6, 2),
        "pack_estimates": estimates,
        "target_mb": 30,
    }
    OUT_DIR.mkdir(exist_ok=True)
    (OUT_DIR / "graph_probe.json").write_text(json.dumps(report, indent=2, ensure_ascii=False))
    print(json.dumps(report, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
