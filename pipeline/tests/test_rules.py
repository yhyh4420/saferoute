import json

from pipeline.countries import kr
from pipeline.schema import (ASSEMBLY_POINT, FLAG_SHELTER_CAPACITY, OUTDOOR_OPEN_SPACE, TEMPORARY_HOUSING,
                             Shelter, suitable)


def shelter(**kw):
    base = dict(id="x", country="KR", name="n", lat=37.5, lon=127.0, facility=TEMPORARY_HOUSING, source="t")
    return Shelter(**{**base, **kw})


def test_flood_excludes_underground():
    assert suitable(shelter(), "flood")
    assert not suitable(shelter(underground=True), "flood")


def test_earthquake_wants_open_space():
    assert suitable(shelter(facility=OUTDOOR_OPEN_SPACE), "earthquake")
    assert suitable(shelter(facility=ASSEMBLY_POINT), "earthquake")
    assert not suitable(shelter(), "earthquake")


def test_shelter_capacity_flags_are_never_destinations():
    # Taiwan-style "can house evacuees" flags must not become refuges (VALIDATION V21).
    assert not suitable(shelter(flag_semantics=FLAG_SHELTER_CAPACITY), "flood")


def test_seoul_adapter_drops_dummies_and_merges_rooms(tmp_path):
    rows = [
        {"FCLT_NO": "1", "ACTC_FCLT_NM": "A주민센터", "LAT": "37.5", "LOT": "127.0", "DADDR": "1 (지하1층 강당)"},
        {"FCLT_NO": "2", "ACTC_FCLT_NM": "A주민센터", "LAT": "37.50001", "LOT": "127.00001", "DADDR": "1 (3층 강당)"},
        {"FCLT_NO": "3", "ACTC_FCLT_NM": "B주민센터", "LAT": "37.6", "LOT": "127.1", "DADDR": "2 (지하 1층)"},
        {"FCLT_NO": "4", "ACTC_FCLT_NM": "주소 변경 테스트", "LAT": "37.55", "LOT": "127.05", "DADDR": ""},
        {"FCLT_NO": "5", "ACTC_FCLT_NM": "C중학교", "LAT": "35.0", "LOT": "127.0", "DADDR": ""},
    ]
    shelters, report = kr.seoul_temp_housing(rows)
    by_name = {s.name: s for s in shelters}
    assert report["dropped_dummy"] == ["주소 변경 테스트"]
    assert report["dropped_out_of_bounds"] == ["C중학교"]
    assert not by_name["A주민센터"].underground  # one above-ground room keeps the building usable
    assert by_name["B주민센터"].underground
    assert by_name["A주민센터"].id == "KR:seoul-OA-21064:1"


def test_seoul_eq_outdoor_converts_web_mercator_and_filters_region():
    rows = [
        {"THINGS_MNG_NO": "A", "THINGS_NM": "대신고등학교 운동장", "CTPV_NM": "서울특별시", "STDG_SGG_CD": "11110",
         "XMAP_CRTS": 14133323.19, "YMAP_CRTS": 4519291.407, "UDGD_YN": "0", "SGG_NM": "종로구"},
        {"THINGS_MNG_NO": "B", "THINGS_NM": "대구 운동장", "CTPV_NM": "대구광역시", "STDG_SGG_CD": "27110",
         "XMAP_CRTS": 14200000.0, "YMAP_CRTS": 4280000.0, "UDGD_YN": "0"},
        {"THINGS_MNG_NO": "A", "THINGS_NM": "중복", "CTPV_NM": "서울특별시", "STDG_SGG_CD": "11110",
         "XMAP_CRTS": 14133323.19, "YMAP_CRTS": 4519291.407, "UDGD_YN": "0"},
    ]
    shelters, report = kr.seoul_eq_outdoor(rows)
    assert [s.id for s in shelters] == ["KR:DSSP-IF-00103:A"]
    assert abs(shelters[0].lat - 37.5731) < 0.001 and abs(shelters[0].lon - 126.9618) < 0.001
    assert report["other_region"] == 1 and report["duplicate_id"] == 1
    assert suitable(shelters[0], "earthquake") and not suitable(shelters[0], "flood")
