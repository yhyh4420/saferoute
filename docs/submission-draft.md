# Submission — final answers (Cerebral Valley form)

> Numbers: docs/VALIDATION.md (V24–V40). Seoul figures use the official 지진옥외대피소 + 침수흔적도 and WorldPop 2020.

| # | Field | Answer |
|---|---|---|
| 1 | Team Name | **(decide)** |
| 3 | Track | Indie Developer |
| 4 | Problem statement | Build for Your World |
| 5 | Most viral X post | (link after posting — text below) |
| 7 | Public GitHub repository | https://github.com/yhyh4420/saferoute |
| 8 | 3-minute demo video | https://youtu.be/nZYXbii89hI (YouTube, unlisted) |

## 6. Project Description
Every morning I commute into the middle of Seoul. If an earthquake or flood alert went off on the way, the network could be jammed and I would not know where to go — and the right answer depends on the disaster: open space for an earthquake, high ground and nothing underground for a flood. Korean authorities ask citizens to learn the shelters around home, work and school in advance. Almost nobody does.

**SafeRoute does it for you, and stays silent until it matters.** On ordinary days it learns, on the phone only, the places you actually go. In the background, around those places and only there, it prepares a plan for each disaster with a Muse Spark agent (backup shelter, family meeting point, step-free needs) and looks for additional safe places that match the official guidance — open space away from buildings for earthquakes, high ground away from streams and past floods for floods — fetching only the map tiles around your places.

When an alert comes, it answers in about a millisecond with no network: an arrow, the walking distance, what not to do, and the prepared plan. Routes to official shelters are pre-computed for the whole city as one shortest-path tree per disaster (a 10 MB pack), so the phone only follows pointers — no search, no server, no model. At night it warns that school yards may be locked and already knows the nearest non-school place. Questions people ask while running ("can I move my car out of the underground garage?") get answers written in advance by Muse Glimmer on open weights and checked for safety. Everything is shown in Korean and English, including shelter names.

Why it matters, in Seoul (9.3 M people): for **35%** of residents the first direction to walk is different for an earthquake than for a flood; for **32%** the straight-line "nearest shelter" is wrong on foot; **45%** would be sent to a school yard that may be locked at night (+606 m median to the nearest alternative). Building it also exposed a data gap — the official earthquake-shelter dataset covers only 18 of Seoul's 25 districts — so non-designated places are always labelled as such. The same pipeline built a five-hazard pack for Kochi, Japan, from a new adapter and rule rows alone. Tested on a Galaxy S23 Ultra in airplane mode; the API key is never in the APK.

## 9. Describe your use of Meta products
- **Muse Spark (`muse-spark-1.3`, tool calling) — the planning agent.** For each place the user actually goes (learned on the device), it calls `route_oracle` (verified walking-nearest shelter from our pre-computed trees, incl. the night alternative when a school yard may be locked), `get_shelters`, `get_guideline` (official guidance), `get_profile` (step-free, with a child) and `save_plan`, and writes notes in Korean and English. A validator rejects any place id the tools did not return. Evaluation: 40 runs over random Seoul locations, 87.5% valid plans, **zero invented shelter ids**, ~$0.02 per plan; it runs in the background while online, and the whole hackathon used $1.60 of credits.
- **Muse Glimmer 30B (open weights, run locally with llama.cpp)** — generates every offline answer at build time (intent × disaster × language, 36 answers), each checked automatically (required/forbidden content, no invented distances, polite Korean) and by a person, then shipped in the app. Nothing is generated on the phone at alert time.
- **Muse Voice Transcribe** — spoken questions when hands are busy (online); the transcript goes through the same offline intent matching, so the answer is still a pre-checked one.
- **Muse Code** — run headless (`muse exec`) to write the Japan adapter (GSI designated refuges with 8 hazard flags), the tsunami/landslide/fire rule rows and their tests; we reviewed and adjusted the result.

## 10. Why are Meta's models important to your solution?
Safety guidance has to be right, available offline, and personal — three needs, three Meta models:
1. **Open weights make offline safety possible.** We tested the obvious alternative, a 1B on-device model, on a 23-question safety set: 8/23, including telling a user it was fine to drive during an earthquake. Muse Glimmer scored 23/23. Because Glimmer is open-weight we can run it locally at build time, generate and check every answer the app needs, and ship them — so the phone gives correct answers with no network and no model.
2. **Tool calling makes personal plans trustworthy.** Muse Spark plans by calling verified tools instead of guessing places, and our validator rejects anything the tools did not return. It adds what a static app cannot — a backup and a family meeting point chosen for this person's places and situation — without ever inventing a location.
3. **Speech** (Voice Transcribe) lets people ask while moving, and Muse Code let one developer add a second country and three new hazards quickly.

## 11. Feedback on Meta products
- Reasoning tokens count toward `max_tokens`; with small limits Muse Spark and Glimmer return empty `content` with `finish_reason=length`. A note in the quickstart would save time.
- `tool_choice` only accepts `"auto"`; a `"required"` option would remove our "use the tools" retry.
- Occasional HTTP 400s mid-conversation succeeded unchanged on retry; an error body explaining the cause would help.
- Glimmer via llama.cpp: `enable_thinking: false` and `reasoning_budget: 0` did not disable reasoning; `reasoning_effort: "low"` worked best. Sustained local generation of a 30B model runs a laptop very hot.
- `muse usage` reports "No subscription allowance" for API-key users, so spend was only visible in the dashboard; a per-key usage endpoint would help budget agents that run in the background.
- The dashboard notes that inputs may be used for product improvement while free credits are in use — clear, and worth surfacing in the API docs for apps that send location.
- Voice Transcribe's multipart API was straightforward; `keywords` biasing is useful for domain terms.

## 12. Feedback on partner technologies
Not used (Fireworks, Modal, Baseten). The demo narration used a third-party TTS service, not a listed partner.

## X post (field 5) — draft
> When a disaster alert goes off, would you know where to walk? 🧭
> The answer depends on the disaster — in Seoul, 1 in 3 people would have to walk a different way for an earthquake than for a flood.
> SafeRoute learns your own places, prepares a plan in the background, and answers in ~1 ms with no network.
> Built with Muse Spark (agent), Muse Glimmer (open weights) and Muse Code for the Meta Global AI Developer Hackathon.
> (add the organisers' official handles/hashtag if the event page gives them)
> 🎥 https://youtu.be/nZYXbii89hI  💻 github.com/yhyh4420/saferoute

## Before submitting
- [ ] Team name
- [x] YouTube (Unlisted): https://youtu.be/nZYXbii89hI
- [ ] Post on X, paste link in 5
- [ ] Paste 6, 9, 10, 11 (and 12) from this file
- [ ] Tick the promotion consent; submit before 11/5 05:00 KST (deadline 11/5 11:00 KST)
