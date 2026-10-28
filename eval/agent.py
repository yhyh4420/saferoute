"""Reference planning agent (Muse Spark tool calling) over the offline pack — the spec the app ports.

The agent never sees coordinates of anything it did not get from a tool, and every id it saves is
checked by `validate_plan` against what the tools returned (tool_choice is "auto" only, so the
model may also answer without tools: VALIDATION V11).
"""

import json
import os
import sqlite3
import struct
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
from sklearn.neighbors import BallTree

ROOT = Path(__file__).resolve().parents[1]
API_URL = "https://api.meta.ai/v1/chat/completions"
MODEL = "muse-spark-1.3"
MAX_TURNS = 6
MAX_TOKENS = 2048  # reasoning tokens count toward this (VALIDATION V11b)
EARTH_RADIUS_M = 6_371_000
NO_ROUTE = 0xFFFF
WALK_MPS = {"default": 1.0, "stepfree": 0.5}

SYSTEM = (
    "You prepare personal evacuation plans ahead of time for one of the user's regular places. "
    "Call route_oracle first: its shelter is the verified walking-nearest suitable destination. "
    "Use get_shelters only to pick a backup and a family meeting point. Never name a place, id or "
    "distance that a tool did not return. Follow get_guideline for the hazard. When done, call "
    "save_plan exactly once. Notes must be short, in Korean, action-first."
)

TOOLS = [
    {"type": "function", "function": {
        "name": "route_oracle",
        "description": "Verified walking-nearest suitable shelter for a hazard from a point, with real walking distance and minutes. Works offline.",
        "parameters": {"type": "object", "properties": {
            "lat": {"type": "number"}, "lon": {"type": "number"},
            "hazard": {"type": "string", "enum": ["earthquake", "flood"]},
            "step_free": {"type": "boolean"}}, "required": ["lat", "lon", "hazard"]}}},
    {"type": "function", "function": {
        "name": "get_shelters",
        "description": "Up to k suitable shelters for a hazard ordered by straight-line distance (NOT walking distance). Use for backup / meeting point.",
        "parameters": {"type": "object", "properties": {
            "lat": {"type": "number"}, "lon": {"type": "number"},
            "hazard": {"type": "string", "enum": ["earthquake", "flood"]},
            "k": {"type": "integer", "minimum": 1, "maximum": 8}}, "required": ["lat", "lon", "hazard"]}}},
    {"type": "function", "function": {
        "name": "get_guideline",
        "description": "Official action guideline for a hazard (Korean Ministry of the Interior and Safety).",
        "parameters": {"type": "object", "properties": {"hazard": {"type": "string", "enum": ["earthquake", "flood"]}},
                       "required": ["hazard"]}}},
    {"type": "function", "function": {
        "name": "get_profile",
        "description": "The user's situation: who they are with and whether they need step-free routes.",
        "parameters": {"type": "object", "properties": {}}}},
    {"type": "function", "function": {
        "name": "save_plan",
        "description": "Save the final plan for this place and hazard.",
        "parameters": {"type": "object", "properties": {
            "hazard": {"type": "string", "enum": ["earthquake", "flood"]},
            "primary_shelter_id": {"type": "string"},
            "backup_shelter_id": {"type": "string"},
            "meeting_point_id": {"type": "string"},
            "notes_ko": {"type": "string", "description": "At most 3 short sentences."}},
            "required": ["hazard", "primary_shelter_id", "notes_ko"]}}},
]


class Pack:
    """Python reader for pack v2 (docs/PACK_FORMAT.md) + its sqlite."""

    def __init__(self, city: str = "seoul"):
        out = ROOT / "pipeline" / "out"
        with open(out / f"pack_{city}.bin", "rb") as f:
            f.read(4)
            _, hlen = struct.unpack("<HI", f.read(6))
            header = json.loads(f.read(hlen))
            n = header["nodes"]
            self.lat = np.frombuffer(f.read(4 * n), "<i4") / 1e6
            self.lon = np.frombuffer(f.read(4 * n), "<i4") / 1e6
            self.trees = {}
            for t in header["trees"]:
                self.trees[(t["hazard"], t["profile"])] = (
                    np.frombuffer(f.read(4 * n), "<i4"),
                    np.frombuffer(f.read(2 * n), "<u2"),
                    np.frombuffer(f.read(2 * n), "<u2"),
                )
        self.node_tree = BallTree(np.radians(np.c_[self.lat, self.lon]), metric="haversine")
        con = sqlite3.connect(out / f"pack_{city}.sqlite")
        self.shelters = {r[0]: dict(idx=r[0], id=r[1], name=r[2], lat=r[3], lon=r[4], hazards=r[5].split(","))
                         for r in con.execute("SELECT idx, id, name, lat, lon, hazards FROM shelter")}
        self.rules = {r[0]: dict(verb_ko=r[1], forbid_ko=r[2], source=r[3])
                      for r in con.execute("SELECT hazard, verb_ko, forbid_ko, source FROM hazard_rule")}
        con.close()

    def oracle(self, lat, lon, hazard, step_free=False):
        d, i = self.node_tree.query(np.radians([[lat, lon]]), k=1)
        node, snap_m = int(i[0, 0]), float(d[0, 0] * EARTH_RADIUS_M)
        profile = "stepfree" if step_free else "default"
        nxt, dist, shel = self.trees[(hazard, profile)]
        if nxt[node] < 0:
            return None
        s = self.shelters[int(shel[node])]
        walk_m = int(dist[node]) + snap_m
        return dict(shelter_id=s["id"], name=s["name"], walk_m=round(walk_m),
                    walk_min=max(1, round(walk_m / WALK_MPS[profile] / 60)))

    def nearby(self, lat, lon, hazard, k=5):
        cands = [s for s in self.shelters.values() if hazard in s["hazards"]]
        la = np.radians(np.array([c["lat"] for c in cands])); lo = np.radians(np.array([c["lon"] for c in cands]))
        p, q = np.radians(lat), np.radians(lon)
        d = 2 * EARTH_RADIUS_M * np.arcsin(np.sqrt(np.sin((la - p) / 2) ** 2 + np.cos(p) * np.cos(la) * np.sin((lo - q) / 2) ** 2))
        return [dict(shelter_id=cands[j]["id"], name=cands[j]["name"], straight_m=round(float(d[j])))
                for j in np.argsort(d)[:k]]


@dataclass
class Run:
    plan: dict | None = None
    calls: list = field(default_factory=list)
    seen_ids: set = field(default_factory=set)
    oracle_id: str | None = None
    usage: dict = field(default_factory=lambda: {"prompt_tokens": 0, "completion_tokens": 0})
    seconds: float = 0.0
    error: str | None = None


def call_api(key: str, messages: list) -> dict:
    body = json.dumps({"model": MODEL, "messages": messages, "tools": TOOLS, "max_tokens": MAX_TOKENS,
                       "temperature": 0}).encode()
    req = urllib.request.Request(API_URL, body, {"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
    for attempt in range(2):
        try:
            return json.loads(urllib.request.urlopen(req, timeout=120).read())
        except urllib.error.HTTPError as e:
            # The API returned transient 400s that did not reproduce on retry (VALIDATION V25).
            if attempt == 0 and (e.code in (400, 429) or e.code >= 500):
                time.sleep(2)
                continue
            raise RuntimeError(f"HTTP {e.code}: {e.read()[:400].decode('utf-8', 'replace')}") from e


def run_agent(pack: Pack, key: str, place: dict, hazard: str, profile: dict) -> Run:
    run = Run()
    step_free = bool(profile.get("step_free"))

    def tool(name: str, args: dict):
        if name == "route_oracle":
            r = pack.oracle(args["lat"], args["lon"], args["hazard"], args.get("step_free", step_free))
            if r:
                run.seen_ids.add(r["shelter_id"])
                if args["hazard"] == hazard:
                    run.oracle_id = r["shelter_id"]
            return r or {"error": "no route from here"}
        if name == "get_shelters":
            r = pack.nearby(args["lat"], args["lon"], args["hazard"], int(args.get("k", 5)))
            run.seen_ids.update(x["shelter_id"] for x in r)
            return {"shelters": r, "note": "straight-line distance, not walking"}
        if name == "get_guideline":
            return pack.rules[args["hazard"]]
        if name == "get_profile":
            return profile
        if name == "save_plan":
            run.plan = args
            return {"saved": True}
        return {"error": f"unknown tool {name}"}

    messages = [
        {"role": "system", "content": SYSTEM},
        {"role": "user", "content": f"Place: {place['name']} at lat {place['lat']}, lon {place['lon']}. "
                                    f"Prepare my {hazard} plan for this place."},
    ]
    t0 = time.time()
    nudged = False
    try:
        for _ in range(MAX_TURNS):
            resp = call_api(key, messages)
            for k in run.usage:
                run.usage[k] += resp["usage"].get(k, 0)
            msg = resp["choices"][0]["message"]
            calls = msg.get("tool_calls") or []
            messages.append({k: v for k, v in msg.items() if k in ("role", "content", "tool_calls")})
            if not calls:
                if run.plan or nudged:
                    break
                # tool_choice is auto-only, so the model sometimes answers in prose: ask once more.
                nudged = True
                messages.append({"role": "user", "content": "Use the tools and finish by calling save_plan."})
                continue
            for c in calls:
                args = json.loads(c["function"]["arguments"] or "{}")
                result = tool(c["function"]["name"], args)
                run.calls.append(c["function"]["name"])
                messages.append({"role": "tool", "tool_call_id": c["id"], "content": json.dumps(result, ensure_ascii=False)})
            if run.plan:
                break
    except Exception as e:  # network / API errors are reported, never crash the batch
        run.error = f"{type(e).__name__}: {e}"
    run.seconds = time.time() - t0
    return run


def validate_plan(run: Run, hazard: str, pack: Pack) -> list[str]:
    """Empty list = valid. Mirrors PlanValidator.kt."""
    p = run.plan
    if p is None:
        return ["no plan saved"]
    problems = []
    if p.get("hazard") != hazard:
        problems.append("wrong hazard")
    if p.get("primary_shelter_id") != run.oracle_id:
        problems.append("primary is not the verified walking-nearest shelter")
    by_id = {s["id"]: s for s in pack.shelters.values()}
    for key in ("primary_shelter_id", "backup_shelter_id", "meeting_point_id"):
        sid = p.get(key)
        if not sid:
            continue
        if sid not in run.seen_ids:
            problems.append(f"{key} {sid} was never returned by a tool")
        elif hazard not in by_id[sid]["hazards"]:
            problems.append(f"{key} {sid} not suitable for {hazard}")
    if len(p.get("notes_ko", "")) > 300:
        problems.append("notes too long")
    return problems


def api_key() -> str:
    for line in open(ROOT / ".env"):
        if line.startswith("META_API_KEY="):
            return line.split("=", 1)[1].strip()
    return os.environ["META_API_KEY"]
