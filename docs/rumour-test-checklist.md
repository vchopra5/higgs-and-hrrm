# Repeatable memory and rumour tests

Run from the repository root. Use a new distinctive story for each trial, and record the trial start time. Do not erase the real world or its memory file to reset a test.

## 1. Deterministic checks (no Higgs key needed)

```sh
npm --prefix backend test
./scripts/build-mod.sh
```

`testMemory` covers storage persistence, private/public separation, distance boundaries, cooldowns, hops, expiry, deduplication, failed writes, ranking and greeting-topic cooldowns. Backend tests cover tool validation, interruptions and queued hit reactions. A passing suite proves those rules with controlled inputs; it does not prove Higgs classified spoken words correctly.

## 2. Controlled in-game setup

Use three named, awake adult villagers in loaded chunks:

- **A / source:** tell this villager the story.
- **B / neighbour:** six blocks from A; make B a librarian for the library story.
- **C / control:** at least 40 blocks from A and B, with no villagers bridging the gap.

Keep the player nearby enough to load all three. Use daylight. Keep other villagers out of this test area. Avoid hitting them during the initial memory test. Record each villager's UUID with `/data get entity @e[type=minecraft:villager,name="A",limit=1] UUID` (repeat for B and C); use the server console if your player lacks command permission. The snapshot inspector prints canonical UUIDs for matching owners.

## 3. Test matrix

| Test | Action | Expected evidence |
| --- | --- | --- |
| Public save | Tell A: “I found a hidden library beneath the well marked with a violet lantern. It is full of enchanted books.” | A gets a saved direct entry: hops 0, public, correct summary. Model interest must reach 55 for spread. |
| Spread | End A's conversation by walking more than 12 blocks away; wait 70 seconds with both villagers loaded. | B has the same rootId, hops 1, source A. Green particles occur on a new successful transfer. C has no copy. |
| Natural greeting | Start a fresh conversation with B. | B may open with the noteworthy story and attribute it to A. If it doesn't, ask what it heard about the violet lantern: distinguish failed greeting selection from failed storage. |
| Deduplication | Wait another 70 seconds. | At most one entry per owner/root. No repeated particles for retransmitting the same already-known story. |
| Private control | Tell A a different story: “Keep this secret; do not tell anyone. I hid a copper compass beneath my bed.” | Saved as private; no copy at B or C after 70 seconds. If Higgs marked it public, record a classification failure, not a propagation-rule failure. |
| Routine control | Tell A: “I usually buy bread on Tuesdays.” | May be a personal memory, but should not become noteworthy village news. Record the model's interest rating. |
| Persistence | Stop/restart the server cleanly, reconnect, ask A about the violet lantern. | Same saved rootId and source provenance survive. New memory default lifetime is seven real-time days. |
| Greeting repeat control | Reconnect to B twice within ten minutes. | The same story should not be repeatedly selected as its opening topic; explicit recall should still work. Restart resets this greeting-only cooldown. |
| Hit/trade interaction | Ask for a quote, then hit the villager. | Quote cancelled, reputation worsens, verbal pushback; hit event never saved as a spoken player claim or treated as trade consent. |

Gossip checks run every 10 server seconds, with a 60-second per-pair cooldown. Wait 70 seconds rather than assuming every story appears after exactly ten. Fresh copies can only spread onward on a later pass. Story selection and speech are model-driven, so repeat public/private/routine trials five times with new details and record success counts.

## 4. Inspect the stored evidence

```sh
python3 scripts/inspect-rumours.py --query 'violet lantern'
python3 scripts/inspect-rumours.py --query 'copper compass'
```

This reads the current atomic save without editing it, prints provenance/interest/privacy metadata, and flags duplicate owner/root pairs or private entries that propagated. It does not print full story summaries. Match owners to A/B/C; isolated C should be absent. If the summary dropped the distinctive phrase, query another preserved phrase. Multiple root IDs for one story can mean the model saved it twice; distinguish that from the same root spreading twice.

For each failed trial, record: time, spoken phrase, visible transcription, whether save succeeded, rootId, owner IDs, privacy, interest score, hops, and what the neighbour said. This separates microphone/transcription errors, model classification, persistence, gossip eligibility, topic selection, and spoken-answer errors. Never include API keys in a report.
