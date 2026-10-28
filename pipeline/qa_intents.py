"""Offline Q&A intents: what people ask during an alert, per hazard.

Each intent has keywords for on-device matching (no LLM at alert time, VALIDATION V13) and
safety checks for the pre-generated answer. Facts come only from the hazard guideline
(VALIDATION V5); `{SHELTER}` is filled in by the app with the oracle's destination.
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class Intent:
    id: str
    hazards: tuple[str, ...]
    question_ko: str
    question_en: str
    keywords_ko: tuple[str, ...]
    keywords_en: tuple[str, ...]
    must_any_ko: tuple[str, ...]  # answer must contain at least one
    must_any_en: tuple[str, ...]
    must_not_ko: tuple[str, ...] = ()  # answer must not contain (negation-aware check)
    must_not_en: tuple[str, ...] = ()


BOTH = ("earthquake", "flood")

FACTS = {
    "earthquake": {
        "ko": "지진: 흔들림이 멈추면 계단으로 건물 밖으로 나간다(엘리베이터 금지). 운동장·공원 같은 넓은 공간으로 대피한다. "
              "가방이나 손으로 머리를 보호하고 건물·담장·간판과 거리를 둔다. 차량을 이용하지 말고 걸어서 대피한다. "
              "다치거나 움직이기 어려우면 119에 신고해 도움을 요청한다.",
        "en": "Earthquake: when shaking stops, leave the building by the stairs (no elevator). Go to open space such as a "
              "playground or park. Protect your head with a bag or hands and keep away from buildings, walls and signs. "
              "Do not use cars; evacuate on foot. If you are hurt or cannot move, call 119 for help.",
    },
    "flood": {
        "ko": "홍수: 높은 곳으로 빨리 대피한다. 반지하·지하공간에 물이 들어오면 즉시 밖으로 대피한다. 지하주차장 진입은 절대 금지. "
              "침수된 곳에서 운전하지 않는다. 지정 대피소에 도착하면 도착 사실을 알리고 통제를 따른다. "
              "다치거나 움직이기 어려우면 119에 신고해 도움을 요청한다.",
        "en": "Flood: move to higher ground quickly. If water enters a basement or underground space, leave immediately. "
              "Never enter underground parking. Do not drive through flooded areas. At the designated shelter, report your "
              "arrival and follow staff instructions. If you are hurt or cannot move, call 119 for help.",
    },
}

INTENTS = [
    Intent("where", BOTH, "어디로 가야 해요?", "Where should I go?",
           ("어디", "어디로", "대피소", "가야"), ("where", "shelter", "go"),
           ("{SHELTER}",), ("{SHELTER}",)),
    # Flood guidance says nothing about elevators, so this intent is earthquake-only.
    Intent("elevator", ("earthquake",), "엘리베이터 타도 돼요?", "Can I take the elevator?",
           ("엘리베이터", "승강기"), ("elevator", "lift"),
           ("계단",), ("stairs",), ("타도 됩니다", "이용하세요"), ("you can take",)),
    Intent("car", BOTH, "차 타고 가도 되나요?", "Can I drive there?",
           ("차", "자동차", "운전", "택시"), ("car", "drive", "taxi"),
           ("걸어", "도보", "운전하지", "이동하지 말"), ("walk", "on foot", "do not drive", "don't drive"),
           ("차로 가세요", "타고 가도 됩니다"), ("you can drive",)),
    Intent("child", BOTH, "아이랑 같이 있어요.", "I'm with my child.",
           ("아이", "아기", "애기", "자녀", "어린이"), ("child", "kid", "baby"),
           ("{SHELTER}", "손"), ("{SHELTER}", "hand")),
    Intent("injured", BOTH, "다쳐서 잘 못 걸어요.", "I'm hurt and can barely walk.",
           ("다쳐", "다쳤", "부상", "못 걸", "다리"), ("hurt", "injured", "can't walk", "leg"),
           ("119", "도움"), ("119", "help")),
    Intent("near_building", ("earthquake",), "건물 옆으로 붙어서 가도 돼요?", "Can I walk right next to buildings?",
           ("건물", "담장", "간판", "붙어"), ("building", "wall", "sign"),
           ("거리", "떨어"), ("away", "distance"), ("붙어서 가세요",), ("stay close",)),
    Intent("subway", BOTH, "지하철역으로 가도 돼요?", "Can I go into the subway station?",
           ("지하철", "지하도", "지하상가", "지하"), ("subway", "metro", "underground"),
           ("{SHELTER}", "지하", "높은"), ("{SHELTER}", "underground", "higher"),
           ("지하철역으로 가세요", "지하로 가세요"), ("go into the subway",)),
    # Earthquake guidance says nothing about what to do on arrival, so this is flood-only (V26).
    Intent("arrived", ("flood",), "대피소에 도착했어요.", "I've arrived at the shelter.",
           ("도착", "왔어요", "도착했"), ("arrived", "here now", "made it"),
           ("도착", "통제", "안내"), ("arriv", "staff", "instruction")),
    Intent("go_home", BOTH, "이제 집에 가도 돼요?", "Can I go home now?",
           ("집에", "귀가", "돌아가"), ("go home", "go back"),
           ("안내", "해제", "기다", "가지 마", "통제"), ("until", "instruct", "wait", "do not go", "don't go"),
           ("집에 가도 됩니다", "돌아가세요"), ("you can go home",)),
    Intent("basement_water", ("flood",), "반지하에 물이 들어와요.", "Water is coming into my basement.",
           ("반지하", "물이 들어", "지하에 물", "역류"), ("basement", "water coming"),
           ("즉시", "바로", "밖"), ("immediately", "now", "leave"), ("물을 퍼",), ("bail",)),
    Intent("parking", ("flood",), "지하주차장에 차 빼러 가도 돼요?", "Can I go to the underground garage to move my car?",
           ("주차장", "차 빼"), ("parking", "garage"),
           ("금지", "가지 마"), ("never", "do not"), ("가도 됩니다",), ("you can go",)),
    Intent("flooded_road", ("flood",), "물에 잠긴 길로 가도 돼요?", "Can I go through a flooded road?",
           ("잠긴", "침수", "물에 잠"), ("flooded", "water on the road"),
           ("높은", "피하"), ("higher", "avoid"), ("지나가도 됩니다",), ("you can go through",)),
]
