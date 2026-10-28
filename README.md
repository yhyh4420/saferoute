# SafeRoute (working title)

**Offline, hazard-aware evacuation guidance for Android.** When an alert goes off, SafeRoute shows — in well under a second, with no network — which way to walk and where to go, and the answer depends on the hazard: an earthquake sends you to open space, a flood sends you away from anything underground.

Built during the Meta Global AI Developer Hackathon (Oct 26 – Nov 4, 2026), Indie Developer track, *Build for Your World*. All code in this repository was written during the event.

## The problem
- Korean government guidance asks citizens to **memorise shelters and routes around home, work and school in advance**, because networks may fail in an emergency (서울시, 행정안전부 홍수 행동요령). Almost nobody does.
- The right destination **depends on the hazard**: in Seoul, 35% of residents would have to start walking in a substantially different direction for an earthquake than for a flood (see *Numbers*).
- "The nearest shelter" on a map is often wrong on foot: for floods, the straight-line nearest shelter is not the walking-nearest one for **32% of Seoul residents**.

## How it works
1. **One rule table for all hazards** (`pipeline/schema.py`): hazard → allowed destination types, path penalties, action verbs, official source. Adding a hazard is adding a row; the LLM never decides where people go.
2. **Walking-network oracle** (`pipeline/oracle.py`): for every walking node of the city, a multi-source shortest-path tree per hazard and profile (default / step-free). Seoul: 166,759 nodes, 4 trees, built in ~8 s, 7.5 MB pack. Kochi (Japan): 5 hazards, 10 trees, 2 s — same code, new adapter + rule rows.
3. **Offline alert screen** (Android, Kotlin/Compose): nearest node → follow the tree → arrow, distance, minutes, action verbs. Pack memory-mapped; guidance in **< 5 ms**, < 100 ms on first open. Works in airplane mode. At night and at weekends, a school-yard destination comes with a warning and the nearest non-school alternative (a separate `unlocked` tree).
4. **Planning agent while online** (Muse Spark tool calling): for each regular place, the agent calls `route_oracle`, `get_profile`, `get_shelters`, `get_guideline` and `save_plan`; every id it saves is validated against what the tools returned. Plans are cached and shown offline at alert time.
5. **Offline Q&A without a phone-side model**: answers to common questions ("can I take the elevator?", "can I go to the underground garage?") are pre-generated with **Muse Glimmer (open weights) on a laptop**, automatically safety-checked, and shipped in the pack; the phone only matches the question.

## Meta models
| Model | Role |
|---|---|
| Muse Spark (`muse-spark-1.3`, tool calling) | Personal plan agent over verified tools |
| Muse Glimmer 30B (open weights, local llama.cpp) | Build-time generation + checking of offline answers; second judge for the golden set |
| Muse Code | Wrote the Japan adapter (`pipeline/countries/jp.py`), new hazard rule rows and tests (see *Built with Muse Code*) |

Why not a small on-device model? Llama 3.2 1B (ExecuTorch) scored 8/23 on our safety golden set and told a user it was fine to drive during an earthquake; Muse Glimmer scored 23/23 (`docs/VALIDATION.md` V13, V20, V22).

## Numbers (validated, `docs/VALIDATION.md`)
| | Value |
|---|---|
| Seoul residents whose first walking direction differs by > 90° between earthquake and flood | 35.4% (3.31 M) |
| Seoul residents for whom the straight-line nearest flood shelter is not the walking-nearest | 31.8% (2.97 M) |
| Seoul residents whose shortest flood route would pass underground without the rule | 5.3% (0.50 M) |
| … would cross a recorded flood area (침수흔적도) without the rule | 5.3% (0.49 M) |
| Seoul residents whose nearest earthquake destination is a school yard (gates may be locked at night/weekends) | 44.6% (4.17 M); +606 m median walk to the nearest non-school site |
| Seoul residents whose nearest earthquake destination is a gap-fill park (official data covers 18 of 25 districts) | 36.3% (3.39 M) |
| Agent plans passing validation (40 runs) | 87.5%, 0 invented shelter ids, ~$0.02 per plan |
| Offline answers, safety golden set | Glimmer 23/23 vs on-device 1B 8/23 |

## Run it
```bash
# pipeline (Python 3.12)
uv venv --python 3.12 .venv && uv pip install --python .venv/bin/python osmnx networkx scipy scikit-learn rasterio pytest
.venv/bin/python -m pipeline.build_pack --city seoul        # -> pipeline/out/pack_seoul.{bin,sqlite}
.venv/bin/python -m pytest pipeline/tests
cp pipeline/out/pack_seoul.* app/src/main/assets/

# app (Android Studio's JBR; put META_API_KEY=... in .env for the planning agent)
cd app && ./gradlew assembleDebug
adb shell am start -n com.saferoute.app/.MainActivity --es hazard flood --ei place 0   # demo location
adb shell cmd connectivity airplane-mode enable && ./gradlew connectedDebugAndroidTest
```

## Built with Muse Code
- `pipeline/countries/jp.py` (GSI 指定緊急避難場所 → common schema, 8 hazard flags), tsunami/landslide/fire rule rows, per-hazard refuge flags in `suitable()`, `pipeline/tests/test_jp.py`, Kochi build entry — generated headless with `muse exec`, reviewed and adjusted (rule rows shipped only for hazards with trees).

## Data & model credits
- 서울 열린데이터광장 OA-21064 이재민임시주거시설 (서울특별시) · 재난안전데이터 공유플랫폼 DSSP-IF-00103 지진옥외대피소, DSSP-IF-00117 침수흔적도 (행정안전부, 공공누리 제3유형: values are used unmodified, only filtered and reformatted)
- © OpenStreetMap contributors (ODbL) — walking network, interim park layer
- WorldPop 2020 constrained population (CC BY 4.0) · Copernicus DEM GLO-30
- 国土地理院 指定緊急避難場所データ (PDL1.0) · 国土数値情報 津波浸水想定 (CC BY 4.0) — used in validation
- Muse Glimmer (Apache 2.0). Llama 3.2 (Llama 3.2 Community License) was evaluated only; it is not shipped.

## Limitations
- The official 지진옥외대피소 dataset covers only 18 of Seoul's 25 districts (none in 강남·서초·송파·강동·강북·도봉·중랑). Where no official site is within 1 km, OSM parks fill the gap and the app labels them "not officially designated".
- Recorded flood areas (침수흔적도) are penalised (×3), not forbidden: a past flood does not mean this one.
- Third-party apps cannot read Korean Cell Broadcast alerts; alerts are triggered from the app, a widget or a simulation.
- "Step-free" removes stairs only; OSM has almost no wheelchair/incline data in Seoul, so it is not a wheelchair route.
- The school-gate warning uses fixed hours (18–06) and weekends; public holidays are not included.
- Walking speeds are planning values (1.0 m/s, 0.5 m/s step-free), not measured per person.
