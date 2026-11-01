"""English rendering of Korean shelter names, alongside (never replacing) the official name.

The official name is kept unchanged (KOGL type 3); `name_en` is a separate reading aid: the proper
noun is romanized with the Revised Romanization of Korean (common sound changes only) and the facility
type is translated from a fixed dictionary. Deterministic, so it cannot invent a place.
"""

import re

INITIALS = ["g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h"]
VOWELS = ["a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi",
          "yu", "eu", "ui", "i"]
# Final consonant as pronounced at the end of a syllable (7 representative sounds).
FINALS = ["", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l", "m", "p", "p", "t", "t",
          "ng", "t", "t", "k", "t", "p", "t"]
# Final consonant carried over to a following ㅇ-initial syllable (liaison).
LIAISON = ["", "g", "kk", "ks", "n", "nj", "nh", "d", "r", "lg", "lm", "lb", "ls", "lt", "lp", "lh", "m", "b", "bs",
           "s", "ss", "", "j", "ch", "k", "t", "p", "h"]

# Longest first; applied repeatedly from the end of each space-separated token.
SUFFIXES = [
    ("지진옥외대피소", "Earthquake Outdoor Shelter"), ("옥외대피소", "Outdoor Shelter"), ("대피소", "Shelter"),
    ("여자고등학교", "Girls' High School"), ("공업고등학교", "Technical High School"), ("중고등학교", "Middle & High School"),
    ("여자중학교", "Girls' Middle School"), ("초등학교", "Elementary School"), ("고등학교", "High School"),
    ("중학교", "Middle School"), ("대학교", "University"), ("유치원", "Kindergarten"), ("학교", "School"),
    ("운동장", "Field"),
    ("도시자연공원", "Urban Nature Park"), ("어린이공원", "Children's Park"), ("근린공원", "Neighborhood Park"),
    ("생태공원", "Ecological Park"), ("문화공원", "Culture Park"), ("자전거공원", "Bicycle Park"),
    ("철도공원", "Railway Park"), ("소공원", "Pocket Park"), ("공원", "Park"),
    ("동주민센터", "-dong Community Center"), ("주민센터", "Community Center"),
    ("동자치회관", "-dong Community Hall"), ("자치회관", "Community Hall"), ("마을공원", "Village Park"),
    ("문화체육센터", "Culture & Sports Center"), ("체육센터", "Sports Center"), ("문화센터", "Culture Center"),
    ("센터", "Center"), ("체육관", "Gymnasium"),
    ("종합사회복지관", "Community Welfare Center"), ("사회복지관", "Social Welfare Center"), ("복지관", "Welfare Center"),
    ("경로당", "Senior Center"), ("교회", "Church"), ("성당", "Catholic Church"), ("회관", "Hall"), ("호텔", "Hotel"),
    ("마당", "Yard"), ("강당", "Auditorium"), ("구청", "Gu Office"), ("주차장", "Parking Lot"), ("쉼터", "Rest Area"),
    ("도서관", "Library"), ("마을숲", "Village Grove"), ("시장", "Market"), ("아파트", "Apartments"),
]
_BY_LENGTH = sorted(SUFFIXES, key=lambda kv: -len(kv[0]))  # longest match wins regardless of list order
PREFIXES = [("구립", "Public "), ("시립", "City "), ("국립", "National ")]
SPECIAL = {"이름 없는 공원": "Unnamed park"}


def _syllables(word: str):
    for ch in word:
        code = ord(ch) - 0xAC00
        if 0 <= code < 11172:
            yield code // 588, (code % 588) // 28, code % 28
        else:
            yield ch


def romanize(word: str) -> str:
    """Revised Romanization with liaison, nasalisation and ㄴ/ㄹ → ll (enough for place names)."""
    syl = list(_syllables(word))
    out = []
    for i, s in enumerate(syl):
        if isinstance(s, str):
            out.append(s)
            continue
        ini, vow, fin = s
        nxt = syl[i + 1] if i + 1 < len(syl) and not isinstance(syl[i + 1], str) else None
        prev = syl[i - 1] if i > 0 and not isinstance(syl[i - 1], str) else None
        # initial (may have been changed by the previous final)
        if prev is not None and prev[2] not in (0, 21) and ini == 11 and LIAISON[prev[2]]:
            head = ""  # consonant already emitted by the previous syllable
        elif prev is not None and ini == 5 and prev[2] in (4, 8):  # ㄴ/ㄹ + ㄹ → l-l
            head = "l"
        elif prev is not None and ini == 2 and prev[2] == 8:  # ㄹ + ㄴ → l-l
            head = "l"
        elif prev is not None and ini == 5 and prev[2] not in (0, 8, 4):  # 받침 + ㄹ → n (e.g. 종로 Jongno)
            head = "n"
        else:
            head = INITIALS[ini]
        tail = FINALS[fin]
        if nxt is not None and fin:
            if nxt[0] == 11 and fin != 21:  # ㅇ initial: liaison (an ㅇ final stays "ng")
                tail = LIAISON[fin]
            elif nxt[0] in (2, 5, 6) and tail in ("k", "t", "p"):  # nasalisation before ㄴ/ㄹ/ㅁ
                tail = {"k": "ng", "t": "n", "p": "m"}[tail]
            elif nxt[0] == 5 and fin == 4:  # ㄴ + ㄹ → l
                tail = "l"
        out.append(head + VOWELS[vow] + tail)
    return "".join(out)


def _stem_en(stem: str) -> str:
    stem = re.sub(r"제(\d+)", r" No.\1 ", stem)
    parts = re.split(r"(\d+|No\.\d+|\s+)", stem)
    words = []
    for p in parts:
        if not p or p.isspace():
            continue
        words.append(p if re.fullmatch(r"\d+|No\.\d+", p) else romanize(p).capitalize())
    return " ".join(words)


def name_en(name: str) -> str:
    if name in SPECIAL:
        return SPECIAL[name]
    out = []
    for token in name.split():
        prefix = ""
        for ko, en in PREFIXES:
            if token.startswith(ko) and len(token) > len(ko):
                prefix, token = en, token[len(ko):]
                break
        types = []
        changed = True
        while changed and token:
            changed = False
            for ko, en in _BY_LENGTH:
                if token.endswith(ko):
                    types.insert(0, en)
                    token = token[: -len(ko)]
                    changed = True
                    break
        stem = _stem_en(token) if token else ""
        words = (prefix + stem).strip()
        for t in types:
            words = words + t if t.startswith("-") else (words + " " + t).strip()
        out.append(words)
    return " ".join(w for w in out if w)
