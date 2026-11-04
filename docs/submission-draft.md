# Submission — final answers (Cerebral Valley form)

> Numbers: docs/VALIDATION.md (V24–V40). Seoul figures use the official 지진옥외대피소 + 침수흔적도 and WorldPop 2020.

| # | Field | Answer |
|---|---|---|
| 1 | Team Name | SafeRoute |
| 3 | Track | Indie Developer |
| 4 | Problem statement | Build for Your World |
| 5 | Most viral X post | (left empty — not posting on X) |
| 7 | Public GitHub repository | https://github.com/yhyh4420/saferoute |
| 8 | 3-minute demo video | https://youtu.be/nZYXbii89hI (YouTube, unlisted) |

## 6. Project Description
I commute into central Seoul every morning. If an earthquake or flood alert went off on the way, the mobile network might be jammed and I wouldn't know where to go. The right place also depends on the disaster. For an earthquake you want open space away from buildings. For a flood you want higher ground and nothing underground. Korean authorities tell people to learn the shelters near their home, work and school ahead of time, but very few people actually do.

SafeRoute does that part for you. On ordinary days it asks nothing of you. It learns, on the phone, the places you actually spend time, and prepares for each of them in the background. A Muse Spark agent writes a plan for each disaster (backup shelter, family meeting point, step-free needs), and the app looks for extra safe spots that fit the official guidance: open ground for earthquakes, and high ground away from streams and past flood areas for floods. It only downloads map tiles around your own places, not the whole city.

When an alert comes, it answers in about a millisecond without a network connection. You get an arrow, the walking distance, what not to do, and the plan prepared earlier. Routes to the official shelters are computed ahead of time for all of Seoul, one shortest-path tree per disaster, in a 10 MB file. The phone just follows pointers, so no search, server or model is involved at that moment. At night it warns that school yards may be locked and shows the nearest place that isn't a school. Questions people ask in a hurry, like "can I move my car out of the underground garage?", have answers written in advance with Muse Glimmer (open weights) and checked for safety. Everything is in Korean and English, including shelter names.

Some numbers for Seoul (9.3 M people). For 35% of residents, the first direction to walk is different for an earthquake and a flood. For 32%, the nearest shelter in a straight line is not the nearest on foot. 45% would be sent to a school yard that may be locked at night, and reaching the nearest alternative adds a median of 606 m. While building this I also found that the official earthquake-shelter dataset covers only 18 of Seoul's 25 districts, so the app labels any place that isn't officially designated. The same pipeline produced a five-hazard pack for Kochi, Japan, with only a new data adapter and new rule rows. I tested the app on a Galaxy S23 Ultra in airplane mode, and the API key is not shipped in the APK.

## 9. Describe your use of Meta products
- Muse Spark (muse-spark-1.3, tool calling) is the planning agent. For each place the user actually goes (learned on the device), it calls route_oracle (the walking-nearest shelter from our precomputed trees, plus a night alternative when a school yard may be locked), get_shelters, get_guideline (official guidance), get_profile (step-free, with a child) and save_plan, and writes notes in Korean and English. A validator rejects any place id that didn't come from a tool. In 40 runs at random Seoul locations, 87.5% of plans were valid and none used an invented shelter id. A plan costs about $0.02. It runs in the background when the phone is online, and the whole hackathon used $1.60 of credits.
- Muse Glimmer 30B (open weights, run locally with llama.cpp) generates all the offline answers at build time: 36 answers covering intent × disaster × language. Each one is checked automatically (required and forbidden content, no made-up distances, polite Korean) and then by me, and the approved answers ship inside the app. Nothing is generated on the phone during an alert.
- Muse Voice Transcribe handles spoken questions when your hands are busy (online only). The transcript goes through the same offline intent matching, so the answer is still one of the pre-checked ones.
- Muse Code, run headless with muse exec, wrote the Japan adapter (GSI designated refuges with 8 hazard flags), the tsunami, landslide and fire rule rows, and their tests. I reviewed and adjusted what it produced.

## 10. Why are Meta's models important to your solution?
An evacuation app has to give correct guidance, work without a network, and fit the person using it. I used a different Meta model for each.

Open weights are what make offline answers possible. I tried the obvious option first, a 1B model running on the phone, on a 23-question safety set. It got 8 right and told one user it was fine to drive during an earthquake. Muse Glimmer got all 23. Because Glimmer's weights are open, I can run it on my own machine at build time, generate and check every answer the app needs, and ship them with the app. The phone then gives correct answers with no network and no model.

Tool calling is what makes the personal plans trustworthy. Muse Spark picks places by calling verified tools instead of guessing, and the validator throws out anything the tools didn't return. Each user gets a backup shelter and a family meeting point chosen for their own places and situation, and no location is ever made up.

Voice Transcribe lets people ask questions while they're moving, and Muse Code helped me, as a solo developer, add a second country and three more hazards in a short time.

## 11. Feedback on Meta products
- Reasoning tokens count toward `max_tokens`; with small limits Muse Spark and Glimmer return empty `content` with `finish_reason=length`. A note in the quickstart would save time.
- `tool_choice` only accepts `"auto"`; a `"required"` option would remove our "use the tools" retry.
- Occasional HTTP 400s mid-conversation succeeded unchanged on retry; an error body explaining the cause would help.
- Glimmer via llama.cpp: `enable_thinking: false` and `reasoning_budget: 0` did not disable reasoning; `reasoning_effort: "low"` worked best. Sustained local generation of a 30B model runs a laptop very hot.
- `muse usage` reports "No subscription allowance" for API-key users, so spend was only visible in the dashboard; a per-key usage endpoint would help budget agents that run in the background.
- The dashboard notes that inputs may be used for product improvement while free credits are in use — clear, and worth surfacing in the API docs for apps that send location.
- Voice Transcribe's multipart API was straightforward; `keywords` biasing is useful for domain terms.

## 12. Feedback on partner technologies
I didn't use Fireworks, Modal or Baseten. The demo narration used a separate TTS service that isn't one of the listed partners.

## X post (field 5) — draft
> When a disaster alert goes off, would you know where to walk? 🧭
> The answer depends on the disaster — in Seoul, 1 in 3 people would have to walk a different way for an earthquake than for a flood.
> SafeRoute learns your own places, prepares a plan in the background, and answers in ~1 ms with no network.
> Built with Muse Spark (agent), Muse Glimmer (open weights) and Muse Code for the Meta Global AI Developer Hackathon.
> (add the organisers' official handles/hashtag if the event page gives them)
> 🎥 https://youtu.be/nZYXbii89hI  💻 github.com/yhyh4420/saferoute

## Before submitting
- [x] Team name: SafeRoute
- [x] YouTube (Unlisted): https://youtu.be/nZYXbii89hI
- [ ] Paste 6, 9, 10, 11 (and 12) from this file
- [ ] Tick the promotion consent; submit before 11/5 05:00 KST (deadline 11/5 11:00 KST)
