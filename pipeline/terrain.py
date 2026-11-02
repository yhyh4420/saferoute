"""Terrain grid for on-device checks of the flood guidance ("높은 곳으로", "저지대·상습 침수지역 피하기").

The phone finds guidance-matching places around the user's own places in the background (app
learn/CandidateFinder.kt) and must judge "high ground" and "past flood area" without sending
coordinates to an elevation service, so the pack carries a coarse grid:

  "SRTR" | u16 version=1 | f64 south, west, dlat, dlon | i32 rows, cols
  | i16 ground elevation (m) [rows*cols, row-major from south] | u8 flood-trace bitmask [ceil(rows*cols/8)]

Elevation is Copernicus GLO-30, a surface model (roofs and trees add height), so each cell keeps the
minimum of its 3x3 neighbourhood as a ground estimate. Usage: python -m pipeline.terrain
"""

import struct
from pathlib import Path

import numpy as np
import rasterio
from rasterio.features import rasterize
from rasterio.merge import merge
from rasterio.transform import from_origin
from scipy.ndimage import minimum_filter

from pipeline.countries import kr

ROOT = Path(__file__).resolve().parent
DEM_TILES = sorted((ROOT / "raw" / "dem").glob("Copernicus_DSM_*.tif"))
OUT = ROOT / "out" / "terrain_seoul.bin"
SOUTH, WEST, NORTH, EAST = 37.41, 126.76, 37.72, 127.19
STEP = 1 / 3600  # ~30 m


def build() -> dict:
    rows, cols = round((NORTH - SOUTH) / STEP), round((EAST - WEST) / STEP)
    srcs = [rasterio.open(t) for t in DEM_TILES]
    mosaic, tf = merge(srcs, bounds=(WEST, SOUTH, EAST, NORTH), res=STEP)
    dem = mosaic[0][:rows, :cols].astype(np.float32)
    ground = minimum_filter(dem, size=3)[::-1]  # flip: row 0 = south
    flood = rasterize(kr.seoul_flood_polygons(), out_shape=(rows, cols),
                      transform=from_origin(WEST, NORTH, STEP, STEP), fill=0, default_value=1, dtype="uint8")[::-1]
    OUT.parent.mkdir(exist_ok=True)
    with open(OUT, "wb") as f:
        f.write(b"SRTR" + struct.pack("<H", 1) + struct.pack("<4d2i", SOUTH, WEST, STEP, STEP, rows, cols))
        f.write(np.clip(np.round(ground), -32768, 32767).astype("<i2").tobytes())
        f.write(np.packbits(flood.reshape(-1), bitorder="little").tobytes())
    return {"rows": rows, "cols": cols, "mb": round(OUT.stat().st_size / 1e6, 2),
            "flood_cells_pct": round(float(flood.mean()) * 100, 2),
            "elev_p5_p50_p95": [int(np.percentile(ground, q)) for q in (5, 50, 95)]}


if __name__ == "__main__":
    print(build())
