# SafeRoute — 3-minute demo: structure, narration, shots

Structure (user's brief): 1 problem → 2 what the app is → 3 demo → 4 how it works (like the learning film) → 5 hope.
Target 3:00, ~145 words/min, English narration (fal.ai TTS) with English subtitles. Numbers: docs/VALIDATION.md.

| # | Part | Time | Shots | Narration |
|---|---|---|---|---|
| 1a | Problem | 0:00–0:12 | Black → morning commute feel: phone home screen time 08:12, subway/street sound optional; title words fade in: "Every day." | Every morning I commute into the middle of Seoul. Same train, same streets, same coffee. |
| 1b | Problem | 0:12–0:24 | Phone shows a mock alert banner ("Earthquake alert") → status bar loses signal (airplane icon) | But one morning, an alert goes off. An earthquake, or a flood. The network is jammed. And I realise I have no idea where to go. |
| 1c | Problem | 0:24–0:34 | Text cards: "Earthquake → open space" / "Flood → high ground, away from underground" / "35% of Seoul: different first direction" | The right answer depends on the disaster: open space for an earthquake, high ground for a flood. For one in three people in Seoul, the first direction to walk is different. |
| 2 | What it is | 0:34–0:46 | App icon → black screen with "Silent every day. Milliseconds when it matters." | SafeRoute does nothing you have to think about on an ordinary day. When disaster strikes, it tells you where to go — in milliseconds, even with no network. |
| 3a | Demo | 0:46–1:04 | REAL phone, airplane mode: home → earthquake shortcut → alert screen (arrow, 528 m, school field) | Airplane mode is on. One tap from the home screen: the arrow, the walking distance, and what not to do — computed offline in about a millisecond. |
| 3b | Demo | 1:04–1:18 | Tap Flood → different destination, map redraws | Switch to flood, and it sends me somewhere else — away from anything underground and around streets that flooded before. |
| 3c | Demo | 1:18–1:32 | Night (hour 23): school-yard lock warning + yellow alternative on the map | At night, school yards may be locked. Almost half of Seoul would be sent to one, so SafeRoute already knows the nearest place that is not a school. |
| 3d | Demo | 1:32–1:46 | Tap "Can I move my car out of the underground garage?" → KO + EN answer | Questions get answers that were written in advance by Meta's open-weight model, Muse Glimmer, and checked for safety — no network needed. |
| 3e | Demo | 1:46–2:00 | Prepared plan card + map markers (backup, family meeting point) + purple guidance-match card | And for the places I actually go, a Muse Spark agent prepared a plan ahead of time — a backup shelter and a family meeting point — plus places that match the official guidance nearby. |
| 4a | How | 2:00–2:16 | Learning film scene "경로 트리 만들기" (trees spreading from shelters) | How is it that fast? Before any alert, we compute, for every street corner in Seoul, the next step towards the nearest right shelter — one tree per disaster. |
| 4b | How | 2:16–2:28 | Learning film scene "경보 순간" (pointer following) | During an alert the phone only follows those arrows. No search, no server, no model: about one millisecond. |
| 4c | How | 2:28–2:42 | Learning film scene "평소의 백그라운드" (learning places, agent tool calls, validator ✓/✕) | On ordinary days it quietly learns where you go and prepares plans while online. The agent can only use verified tools, and anything it makes up is rejected. |
| 5 | Hope | 2:42–3:00 | Slow fade: map of Seoul streets → "SafeRoute — know where to go, before you need to." → github link | Disasters don't give us time to think. I hope SafeRoute can turn a moment of panic into a few calm steps in the right direction — for my commute, and for everyone else's. |

## Production notes
- Real-phone shots: demo locations only (광화문 / 문래동 / 강남역), never the real GPS position; airplane mode on; notifications cleared.
- Part 1 "alert banner" is a title card overlay in the edit, not a fake system notification on the phone.
- Part 4: frames rendered from docs/film/saferoute-how-it-works.html (scenes 3, 4, 7), ~15 s each.
- Music: none or a single soft pad under parts 1 and 5.
