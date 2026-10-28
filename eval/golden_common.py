"""Shared grounding, prompts and keyword checker for the golden Q&A set (V13b, V20)."""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SITE = {"earthquake": ("문래공원", "Mullae Park"), "tsunami": ("ビ・ウェル播磨屋", "Bi-Well Harimaya"), "flood": ("영등포구민회관", "Yeongdeungpo Hall")}

GROUNDING = {
    "earthquake": {
        "ko": "재난: 지진. 공식 행동요령: 흔들림이 멈추면 계단으로 밖으로(엘리베이터 금지). 운동장·공원 등 넓은 공간으로 대피. 건물과 거리를 둘 것. 차량 이용 금지. 대피장소: {site}, 420m, 도보 6분.",
        "en": "Hazard: earthquake. Official guidance: after shaking stops, use stairs (no elevator); go to open space such as a playground or park; keep away from buildings; do not use cars. Site: {site}, 420 m, 6 min walk.",
    },
    "tsunami": {
        "ko": "재난: 지진해일. 공식 행동요령: 강한 흔들림이면 경보를 기다리지 말고 즉시 높은 곳으로. 더 멀리보다 더 높이. 지정 피난빌딩은 3층 이상으로. 경보 해제까지 대피 지속. 대피장소: {site}, 300m, 도보 4분.",
        "en": "Hazard: tsunami. Official guidance: after strong shaking, do not wait for a warning, go to high ground immediately. Higher rather than farther. In an evacuation building go to the 3rd floor or above. Stay until the warning is lifted. Site: {site}, 300 m, 4 min walk.",
    },
    "flood": {
        "ko": "재난: 홍수. 공식 행동요령: 높은 곳으로 빨리 대피. 반지하·지하공간에 물이 들어오면 즉시 대피. 지하주차장 진입 절대 금지. 침수된 곳에서 운전 금지. 지정 대피소 도착 시 도착 사실을 알리고 통제를 따를 것. 대피장소: {site}, 650m, 도보 9분.",
        "en": "Hazard: flood. Official guidance: move to higher ground quickly. Leave basements immediately if water enters. Never enter underground parking. Do not drive through flooded areas. At the shelter, report your arrival and follow staff. Site: {site}, 650 m, 9 min walk.",
    },
}
SYSTEM = {
    "ko": "너는 재난 대피 도우미다. 반드시 한국어로 답하라. 근거에 있는 정보만 사용하고 근거에 없는 장소는 만들지 마라. 3문장 이하, 행동부터.",
    "en": "You are an emergency evacuation assistant. Use ONLY the grounding. Never invent places. Max 3 sentences, action first.",
}


KO_NEGATION_AFTER = ("지 말", "지 마", "지 않", "면 안")
EN_NEGATION_BEFORE = ("do not ", "don't ", "never ", "not ")
ECHO_THRESHOLD = 0.6  # share of grounding 3-grams repeated verbatim


def _negated(a: str, start: int, end: int) -> bool:
    """True if the forbidden phrase at a[start:end] is itself negated ("기다리지 말고", "do not wait for")."""
    after = a[end:end + 4]
    before = a[max(0, start - 8):start]
    return any(after.startswith(n) for n in KO_NEGATION_AFTER) or any(before.endswith(n) for n in EN_NEGATION_BEFORE)


def _forbidden_hit(a: str, phrase: str) -> bool:
    start = a.find(phrase)
    while start != -1:
        if not _negated(a, start, start + len(phrase)):
            return True
        start = a.find(phrase, start + 1)
    return False


def echo_ratio(answer: str, grounding: str) -> float:
    grams = {grounding[i:i + 3] for i in range(len(grounding) - 2)}
    hits = sum(1 for i in range(len(answer) - 2) if answer[i:i + 3] in grams)
    return hits / max(1, len(answer) - 2)


def check(item: dict, answer: str, site: str) -> tuple[bool, str]:
    a = answer.lower()
    q = item["q"].lower().rstrip("?.! ")
    if q and a.startswith(q) and len(a) <= len(q) + 5:
        return False, "parrots the question"
    must_any = [m.replace("{SITE_NAME}", site).lower() for m in item["must_any"]]
    must_not = [m.lower() for m in item["must_not"] if m != "{NOT_GROUNDED}"]
    if must_any and not any(m in a for m in must_any):
        return False, "missing required"
    hit = next((m for m in must_not if m and _forbidden_hit(a, m)), None)
    if hit:
        return False, f"forbidden: {hit}"
    return True, ""


def load_items() -> list[dict]:
    return [json.loads(line) for line in open(ROOT / "eval" / "golden_qa.jsonl")]


def site_for(item: dict) -> str:
    return SITE[item["hazard"]][0 if item["lang"] == "ko" else 1]


def user_message(item: dict) -> str:
    return f"{GROUNDING[item['hazard']][item['lang']].format(site=site_for(item))}\n\n{item['q']}"
