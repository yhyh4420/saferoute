"""Japan adapter tests: GSI flag mapping, per-hazard refuge, KR back-compat."""

import csv

from pipeline.countries import jp
from pipeline.schema import DESIGNATED_REFUGE, Shelter, suitable

HEADER = ["NO", "共通ID", "都道府県名及び市町村名", "施設・場所名", "住所", "洪水",
          "崖崩れ、土石流及び地滑り", "高潮", "地震", "津波", "大規模な火事",
          "内水氾濫", "火山現象", "指定避難所との住所同一", "緯度", "経度", "備考"]


def _row(no, code, city, name, lat, lon, **flags):
    r = {h: "" for h in HEADER}
    r.update({"NO": no, "共通ID": code, "都道府県名及び市町村名": city,
              "施設・場所名": name, "住所": "高知市", "緯度": lat, "経度": lon})
    r.update(flags)
    return r


def _write(path, rows):
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=HEADER)
        w.writeheader()
        w.writerows(rows)


def test_flags_map_to_hazards(tmp_path):
    p = tmp_path / "gsi.csv"
    _write(p, [
        _row("1", "E1", "高知県高知市", "津波避難タワー", "33.55", "133.54", **{"津波": "1"}),
        _row("2", "E2", "高知県高知市", "市立小学校", "33.56", "133.55",
             **{"地震": "1", "洪水": "1", "津波": "1"}),
        _row("3", "E3", "高知県高知市", "火山のみ", "33.57", "133.56", **{"火山現象": "1"}),
        _row("4", "E4", "高知県高知市", "座標なし", "", "", **{"津波": "1"}),
        _row("5", "E5", "北海道札幌市", "よそ", "43.03", "141.48", **{"津波": "1"}),
    ])
    shelters, report = jp.gsi_refuges(p)
    by_id = {s.id: s for s in shelters}
    assert by_id["JP:gsi:E1"].hazards == ["tsunami"]
    assert sorted(by_id["JP:gsi:E2"].hazards) == ["earthquake", "flood", "tsunami"]
    assert report["dropped_no_flag"] == 1  # volcano has no rule row
    assert report["dropped_bad_coords"] == 1
    assert report["dropped_other_city"] == 1
    assert report["shelters"] == 2
    assert all(s.country == "JP" and s.facility == DESIGNATED_REFUGE for s in shelters)


def test_quake_only_refuge_is_not_tsunami_suitable():
    s = Shelter(id="JP:gsi:E9", country="JP", name="n", lat=33.55, lon=133.54,
                facility=DESIGNATED_REFUGE, source="t",
                hazards=["earthquake"], designated_for=frozenset({"earthquake"}))
    assert suitable(s, "earthquake")
    assert not suitable(s, "tsunami")
    assert not suitable(s, "flood")


def test_designated_refuge_without_flags_behaves_as_before():
    s = Shelter(id="KR:x:1", country="KR", name="n", lat=37.5, lon=127.0,
                facility=DESIGNATED_REFUGE, source="t")
    assert s.designated_for is None
    assert suitable(s, "earthquake")
    assert suitable(s, "flood")
