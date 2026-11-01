from pipeline.romanize import name_en, romanize


def test_romanization_sound_changes():
    assert romanize("문래") == "mullae"  # ㄴ+ㄹ
    assert romanize("청운") == "cheongun"  # ㅇ final is not carried over
    assert romanize("독립문") == "dongnimmun"  # nasalisation
    assert romanize("종로") == "jongno"
    assert romanize("신림") == "sillim"
    assert romanize("선릉") == "seolleung"


def test_shelter_names_keep_type_and_numbers():
    assert name_en("덕수초등학교 운동장") == "Deoksu Elementary School Field"
    assert name_en("구립문래제1경로당") == "Public Mullae No.1 Senior Center"
    assert name_en("역삼1동주민센터") == "Yeoksam 1-dong Community Center"
    assert name_en("이름 없는 공원") == "Unnamed park"
