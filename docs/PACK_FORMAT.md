# Route pack format (v2)

Built by `python -m pipeline.build_pack --city <city>`; read by `app/.../pack/RoutePack.kt` (memory-mapped, so the asset must stay uncompressed — `noCompress` in `app/build.gradle.kts`).

All integers little-endian. `n` = number of walking-graph nodes.

| Section | Type | Notes |
|---|---|---|
| magic | `b"SRPK"` | |
| version | u16 | 2 |
| header_len | u32 | |
| header | UTF-8 JSON | `city`, `nodes`, `shelters`, `coord_scale` (1e6), `grid_cell_deg` (0.002), `grid_key` (1e6), `trees: [{hazard, profile}]` |
| lat, lon | i32[n] each | degrees × 1e6, nodes in coarse spatial order |
| per tree | i32 next[n], u16 dist_m[n], u16 shelter[n] | `next = -1` unreachable, `next = self` at a shelter; `dist_m` real walking metres (penalties excluded), `0xFFFF` = unreachable; `shelter` = `idx` in the sqlite `shelter` table |
| grid | u32 cells, i64 key[cells], i32 start[cells+1], i32 node[n] | `key = floor(lat/0.002)·1e6 + floor(lon/0.002)`, sorted; nodes of cell `c` are `node[start[c] .. start[c+1])` |

Companion `pack_<city>.sqlite`: `shelter(idx, id, country, name, lat, lon, facility, hazards, address, capacity, underground, source, updated_at)` and `hazard_rule(hazard, verb_ko, verb_en, forbid_ko, forbid_en, source, rule_json)`.

Profiles: `default` (1.0 m/s) and `stepfree` (`highway=steps` removed, 0.5 m/s in the app). Flood trees multiply underground/indoor/tunnel edge length by 20 (`UNDERGROUND_PENALTY`).
