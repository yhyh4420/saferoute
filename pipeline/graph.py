"""Walking graph -> compact arrays with the edge attributes the hazard rules need.

The GraphML must be downloaded with layer/indoor/level tags kept (VALIDATION V19):
OSMnx's default useful_tags_way drops them, so underground passages look like streets.
"""

from dataclasses import dataclass
from pathlib import Path

import numpy as np
import osmnx as ox
from scipy.sparse import coo_matrix, csr_matrix

OVERPASS_MIRROR = "https://maps.mail.ru/osm/tools/overpass/api"  # overpass-api.de refuses this host
EXTRA_WAY_TAGS = ["layer", "covered", "indoor", "level", "incline", "wheelchair", "surface"]


@dataclass
class WalkGraph:
    node_ids: np.ndarray  # OSM node ids, in pack order (spatially sorted)
    lat: np.ndarray
    lon: np.ndarray
    u: np.ndarray  # edge endpoints as pack indices (undirected, one row per edge)
    v: np.ndarray
    length: np.ndarray  # metres
    underground: np.ndarray  # bool: tunnel=yes, layer<0, indoor, level<0
    steps: np.ndarray  # bool: highway=steps

    def matrix(self, weight: np.ndarray) -> csr_matrix:
        keep = np.isfinite(weight)
        n = len(self.node_ids)
        return coo_matrix((weight[keep], (self.u[keep], self.v[keep])), shape=(n, n)).tocsr()


def download(place: str, path: Path) -> None:
    ox.settings.overpass_url = OVERPASS_MIRROR
    ox.settings.useful_tags_way = list(ox.settings.useful_tags_way) + EXTRA_WAY_TAGS
    ox.save_graphml(ox.graph_from_place(place, network_type="walk"), path)


def _first(value):
    return value[0] if isinstance(value, list) else value


def _is_underground(d: dict) -> bool:
    if _first(d.get("tunnel")) == "yes":
        return True
    if _first(d.get("indoor")) not in (None, "no"):
        return True
    if str(_first(d.get("level")) or "").startswith("-"):
        return True
    try:
        return int(str(_first(d.get("layer"))).split(";")[0]) < 0
    except ValueError:
        return False


def load(path: Path) -> WalkGraph:
    g = ox.load_graphml(path)
    ids = np.array(list(g.nodes))
    lat = np.array([g.nodes[n]["y"] for n in ids])
    lon = np.array([g.nodes[n]["x"] for n in ids])
    # Coarse row-major spatial order keeps neighbours close (better compression, locality).
    order = np.lexsort((lon, np.floor(lat * 200)))
    ids, lat, lon = ids[order], lat[order], lon[order]
    index = {n: i for i, n in enumerate(ids)}

    seen = set()
    u, v, length, under, steps = [], [], [], [], []
    for a, b, d in g.edges(data=True):
        key = (min(a, b), max(a, b))
        if a == b or key in seen:
            continue
        seen.add(key)
        u.append(index[a])
        v.append(index[b])
        length.append(float(d.get("length", 0.0)))
        under.append(_is_underground(d))
        steps.append(_first(d.get("highway")) == "steps")
    return WalkGraph(ids, lat, lon, np.array(u), np.array(v), np.array(length),
                     np.array(under), np.array(steps))
