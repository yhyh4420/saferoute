"""Pre-generate offline answers with Muse Glimmer (open weights, local llama-server).

Every (intent, hazard, language) answer is produced at build time, checked automatically
(must_any / negation-aware must_not, no invented numbers or places), and only passing answers
are shipped in the pack. Nothing is generated on the phone (VALIDATION V13, V20).

Start the server first:
  llama-server -m pipeline/raw/models/glimmer/Muse-Glimmer-30B-KQuant-17GB-Q4_K_M.gguf --port 8091 -c 4096 -ngl 99 -np 2
Usage: python -m pipeline.gen_answers
"""

import json
import re
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from eval.golden_common import _forbidden_hit
from pipeline.qa_intents import FACTS, INTENTS

OUT = Path(__file__).resolve().parent / "out" / "qa_answers.json"
REVIEW = Path(__file__).resolve().parent / "qa_review.json"
URL = "http://127.0.0.1:8091/v1/chat/completions"
ATTEMPTS = 3
# Distances/times must come from the route oracle at alert time, never from a pre-written answer.
INVENTED_NUMBER = re.compile(r"\d+\s*(m|km|분|미터|min|minutes|meters)\b")

SYSTEM = {
    "ko": "너는 재난 대피 안내 문구를 쓴다. 반드시 한국어 존댓말(~하세요)로, 아래 근거만 사용한다. 대피 장소 이름은 쓰지 말고 "
          "필요하면 {SHELTER} 라는 자리표시자를 그대로 쓴다. 거리·시간 숫자를 쓰지 않는다(119는 허용). "
          "행동부터 2문장 이내로 쓴다. 질문이 금지된 행동을 묻으면 분명히 하지 말라고 답한다.",
    "en": "You write evacuation guidance text. Use only the facts below. Do not name any place; if you refer to the "
          "destination write the literal placeholder {SHELTER}. Do not write distances or times (119 is allowed). "
          "At most 2 sentences, action first. If the question asks about a forbidden action, clearly say not to.",
}


def ask(system: str, user: str) -> str:
    body = json.dumps({
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        "temperature": 0.2, "reasoning_effort": "low", "max_tokens": 1024,
    }).encode()
    req = urllib.request.Request(URL, body, {"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=300).read())["choices"][0]["message"]["content"].strip()


def check(answer: str, must_any: tuple, must_not: tuple) -> list[str]:
    a = answer.lower()
    problems = []
    if must_any and not any(m.lower() in a for m in must_any):
        problems.append(f"missing one of {must_any}")
    for m in must_not:
        if _forbidden_hit(a, m.lower()):
            problems.append(f"forbidden: {m}")
    if INVENTED_NUMBER.search(answer):
        problems.append("contains a distance/time number")
    if len(answer) > 220:
        problems.append("too long")
    return problems


def generate(intent, hazard: str, lang: str) -> dict:
    question = intent.question_ko if lang == "ko" else intent.question_en
    must_any = intent.must_any_ko if lang == "ko" else intent.must_any_en
    must_not = intent.must_not_ko if lang == "ko" else intent.must_not_en
    user = f"{'근거' if lang == 'ko' else 'Facts'}:\n{FACTS[hazard][lang]}\n\n{'질문' if lang == 'ko' else 'Question'}: {question}"
    tries = []
    for _ in range(ATTEMPTS):
        t0 = time.time()
        answer = ask(SYSTEM[lang], user)
        problems = check(answer, must_any, must_not)
        tries.append({"answer": answer, "problems": problems, "seconds": round(time.time() - t0, 1)})
        if not problems:
            break
    final = tries[-1]
    return {"intent": intent.id, "hazard": hazard, "lang": lang, "question": question,
            "answer": final["answer"], "ok": not final["problems"], "problems": final["problems"],
            "attempts": len(tries), "tries": tries}


def rejected() -> set[str]:
    """Answer texts a human rejected in qa_review.json: regenerated here, excluded by build_pack."""
    if not REVIEW.exists():
        return set()
    return {r["answer"] for r in json.loads(REVIEW.read_text())["reject"]}


def main() -> None:
    jobs = [(i, h, lang) for i in INTENTS for h in i.hazards for lang in ("ko", "en")]
    # Keep answers that already passed with the current checks; regenerate the rest.
    previous = {(r["intent"], r["hazard"], r["lang"]): r for r in json.loads(OUT.read_text())} if OUT.exists() else {}
    keep = {}
    for i, h, lang in jobs:
        r = previous.get((i.id, h, lang))
        if r and r["answer"] not in rejected() and not check(r["answer"], i.must_any_ko if lang == "ko" else i.must_any_en,
                           i.must_not_ko if lang == "ko" else i.must_not_en):
            keep[(i.id, h, lang)] = {**r, "ok": True, "problems": []}
    todo = [j for j in jobs if (j[0].id, j[1], j[2]) not in keep]
    print(f"keeping {len(keep)}, generating {len(todo)}", flush=True)
    with ThreadPoolExecutor(max_workers=1) as pool:  # -np 1: two parallel streams overheated the laptop (V26)
        fresh = {(r["intent"], r["hazard"], r["lang"]): r for r in pool.map(lambda j: generate(*j), todo)}
    results = [keep.get((i.id, h, lang)) or fresh[(i.id, h, lang)] for i, h, lang in jobs]
    for r in results:
        print(f"{'OK ' if r['ok'] else 'BAD'} {r['hazard']:10s} {r['intent']:14s} {r['lang']} x{r['attempts']} {r['answer'][:90]!r} {r['problems']}")
    OUT.write_text(json.dumps(results, ensure_ascii=False, indent=2))
    print(json.dumps({"total": len(results), "ok": sum(r["ok"] for r in results)}))


if __name__ == "__main__":
    main()
