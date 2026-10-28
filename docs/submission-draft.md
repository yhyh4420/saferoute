# Submission draft (Cerebral Valley form) — update numbers before submitting

> Numbers recomputed 2026-10-28 with the official 지진옥외대피소 + 침수흔적도 (`python -m eval.seoul_divergence`, VALIDATION V28).

## 6. Project Description
When a disaster alert goes off, people lose the first minutes deciding where to go — and the right answer depends on the hazard. In an earthquake you go out to open space; in a flood you go up and away from anything underground. Korean authorities tell citizens to memorise shelters and routes around home, work and school in advance because networks may fail; almost nobody does.

SafeRoute does it for them. A single rule table maps each hazard to the official destination types and route penalties, and a walking-network oracle pre-computes, for every street node of the city, the way to the nearest *suitable* shelter. The phone shows an arrow, distance and the official action in milliseconds, fully offline. While online, a Muse Spark agent learns the user's regular places and prepares a personal plan for each (backup shelter, family meeting point, step-free needs), validated against the oracle. Answers to the questions people actually ask ("can I take the elevator?", "can I move my car out of the underground garage?") are pre-generated with Muse Glimmer on open weights, safety-checked, and shipped inside the app.

In Seoul (9.3 M people): for 35% of residents the first direction to walk differs by more than 90° between an earthquake and a flood; for 32% the straight-line "nearest shelter" in a flood is not the walking-nearest one; without the rules, 5% would be routed through underground passages and 5% through areas that flooded before. Almost half of Seoul (45%) would be sent to a school yard whose gates may be locked at night, so after dark the app also shows the nearest non-school alternative (a median 606 m further). Building it also surfaced a data gap: the official earthquake-shelter dataset covers only 18 of Seoul's 25 districts, so the app fills gaps with parks and labels them as not officially designated. The same code built a 5-hazard pack for Kochi, Japan, from a new adapter and rule rows alone.

## 9. Describe your use of Meta products
- **Muse Spark (`muse-spark-1.3`) with tool calling** — the planning agent. Tools: `route_oracle` (verified walking-nearest shelter from our pre-computed trees), `get_shelters`, `get_guideline` (official guidance), `get_profile`, `save_plan`. Every id the agent saves is validated against tool outputs; in 40 evaluation runs over random Seoul locations 87.5% of plans passed validation with **zero invented shelter ids**, at about $0.02 per plan. The remaining failures were transient HTTP 400s, now retried.
- **Muse Glimmer 30B (open weights, local via llama.cpp)** — generates and cross-checks the offline answers at build time (intent × hazard × language). Nothing is generated on the phone at alert time.
- **Muse Voice Transcribe** — spoken questions when hands are busy (online); the transcript goes through the same offline intent matching, so the answer is still a pre-checked one.
- **Muse Code** — wrote the Japan adapter (GSI designated refuges with 8 hazard flags), the tsunami/landslide/fire rule rows and their tests, run headless (`muse exec`); we reviewed and adjusted the result.

## 10. Why are Meta's models important to your solution?
Safety guidance must be right, available offline, and personal — the three needs map to three Meta models:
1. **Open weights make offline safety possible.** We tested the obvious alternative — a 1B on-device model — on a 23-question safety golden set: it scored 8/23 and told a user it was fine to drive during an earthquake. Muse Glimmer scored 23/23. Because Glimmer is open-weight, we can run it locally at build time, generate every answer the app needs, check each one, and ship them inside the app, so the phone gives correct answers with no network and no model.
2. **Tool calling makes personal plans trustworthy.** Muse Spark plans by calling our verified tools instead of guessing places, and our validator rejects any id the tools did not return. The model adds what a static app cannot — a backup and a family meeting point chosen for the user's situation — without ever inventing a location.
3. **Speech in 25 languages** (Voice Transcribe) lets people ask while running, in their own language.

## 11. Feedback on Meta products
- Reasoning tokens count toward `max_tokens`; with small limits Muse Spark and Glimmer return empty `content` and `finish_reason=length`. A note in the quickstart would save time.
- `tool_choice` only accepts `"auto"`. A `"required"` option would remove our "use the tools" retry.
- We saw occasional HTTP 400s mid-conversation that succeeded unchanged on retry; an error body explaining the cause would help.
- Glimmer via llama.cpp: `enable_thinking: false` and `reasoning_budget: 0` did not disable reasoning; `reasoning_effort: "low"` worked best. Sustained local generation of a 30B model runs a laptop very hot — worth mentioning for hackathon participants.
- Voice Transcribe's multipart API was straightforward; `keywords` biasing is useful for domain terms.

## 12. Feedback on partner technologies
(Not used.)
