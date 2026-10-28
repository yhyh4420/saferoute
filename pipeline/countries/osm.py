"""Interim shelter source from OpenStreetMap (no API key needed).

Used to unblock routing work until official national data is wired in. OSM
records are community-mapped, so every row is tagged source="OSM" and the
official adapter replaces them in the final pack.
"""

import osmnx as ox

from pipeline.schema import ASSEMBLY_POINT, OUTDOOR_OPEN_SPACE, Shelter

TAGS = {"emergency": ["assembly_point"], "leisure": ["park"]}
MIN_PARK_AREA_M2 = 2000  # small pocket parks are not useful open space


def fetch(place: str, country: str) -> list[Shelter]:
    gdf = ox.features_from_place(place, TAGS)
    projected = gdf.to_crs(gdf.estimate_utm_crs())
    area = projected.geometry.area
    centroid = projected.geometry.centroid.to_crs(4326)

    shelters = []
    for (osm_type, osm_id), row in gdf.iterrows():
        if row.get("emergency") == "assembly_point":
            facility = ASSEMBLY_POINT
        elif area.loc[(osm_type, osm_id)] >= MIN_PARK_AREA_M2:
            facility = OUTDOOR_OPEN_SPACE
        else:
            continue
        c = centroid.loc[(osm_type, osm_id)]
        # Missing tags come back as NaN, which is truthy, so test for str explicitly.
        name = next((v for v in (row.get("name:ko"), row.get("name")) if isinstance(v, str)), "")
        shelters.append(
            Shelter(
                id=f"{country}:osm:{osm_type}/{osm_id}",
                country=country,
                name=name,
                lat=c.y,
                lon=c.x,
                facility=facility,
                source="OSM",
            )
        )
    return shelters
