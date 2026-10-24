<p><a href="../README.md"><img src="assets/logo.png" alt="HIGGS HRRM" width="64" /></a></p>

# HIGGS HRRM · Playtest checklist

[← HIGGS HRRM](../README.md) · [Setup](setup.md) · [Memory evaluation](memory-evaluation.md)

One pass through every feature in a running game. Everything here has unit or integration tests, and some parts have live Boson probes, but **none of it has been verified end to end in-game** until this list is run. Tick each item, and note anything that differs from **Expect** in the results table at the end.

## Before you start

- Rebuild and install the jar on the **server and client**, then restart the Node backend, the server and the client.
- Turn on fast chatter for the run, then remove these settings afterwards: `HIGGS_CHATTER_INTERVAL_SECONDS=10`, `HIGGS_CHATTER_CHANCE_PERCENT=100`, `HIGGS_CHATTER_COOLDOWN_SECONDS=30`.
- Keep the backend terminal and `server-cardboard/logs/latest.log` open. **F6** shows the conversation and Boson, **F7** the context, and **F8** audio and connection.
- Use a village with at least three adult villagers close together, including one with no profession or a nitwit. Set the time to day (`/time set day`).

## 1. Conversation basics

| # | Do | Expect | Check |
| --- | --- | --- | --- |
| 1.1 | Look at a villager and hold **B** to talk | Outline, greeting, mic HUD; your lines and theirs appear in chat | F8 shows `stale 0` (no microphone backlog) |
| 1.2 | Wait 10 s after Ready, then press **B** | No "Voice connection is too slow"; the villager answers | F8 `dropped 0` |
| 1.3 | Switch to a second villager who was nearby, then back | The second villager does not re-greet; carries on | F7 shows `continuing` and `recentConversation` |

## 2. Trading, haggling and quests

| # | Do | Expect | Check |
| --- | --- | --- | --- |
| 2.1 | Ask a trader what they sell | Only in-stock wares; normal price | F7 `trade.wares` |
| 2.2 | Talk to the jobless villager or nitwit | Never offers wares; says it has nothing to sell if asked | F6 tools exclude `propose_trade` |
| 2.3 | Compliment a villager twice, then offer below price | Accepts at or above its floor, counters below it | Tracker card |
| 2.4 | Offer about half price | A quest is offered; you get a "Quest: Name" book and quill | Book pages list the item and the deal |
| 2.5 | Bring the quest item and say so | Items taken; the deal at your price; a yes buys it once | Server log `Tool=complete_quest` |
| 2.6 | `/effect give @s minecraft:hero_of_the_village 60 0`, then ask for a price | About 30% lower; the villager acknowledges you as the village's hero | F7 `trade.heroOfTheVillage` |

## 3. Gifts and errands

| # | Do | Expect | Check |
| --- | --- | --- | --- |
| 3.1 | Say "here, a diamond" while holding one | The diamond is removed; GIFT GIVEN card; +2 | Server log `Gift … PRECIOUS` |
| 3.2 | Sneak and use an item the villager dislikes, twice | Refused and remembered; the second time costs 1 reputation | Action bar |
| 3.3 | Ask for work | ERRAND card; the recipient glows; an "Errand: A → B" book | Book pages |
| 3.4 | Tell the recipient something unrelated | "Not sure what you mean"; the errand stays open | Server log `Tool=deliver_errand status=errand_mismatch` |
| 3.5 | Pass the message on in your own words (or sneak and use the package on the recipient) | ERRAND COMPLETE; both villagers gain reputation; the recipient never recites the original wording | Action bar |

## 4. Reputation, hostility and villages

| # | Do | Expect | Check |
| --- | --- | --- | --- |
| 4.1 | Enter a village | The village name title with your reputation fades in | `data/higgs-villages.json` |
| 4.2 | Insult a villager until it reaches −5 | Golems attack; no trading; the trade screen is refused | Server log `band=hostile` |
| 4.3 | At −2 to −4, try to talk repeatedly | Sometimes "turns away from you" and walks off | Server log `shunned` |
| 4.4 | Give the hostile villager a loved gift | Reputation rises to about −2; trading returns | Action bar |

## 5. Companions and chatter

| # | Do | Expect | Check |
| --- | --- | --- | --- |
| 5.1 | Talk next to a second villager | It joins (outlined), chimes in sometimes, never reads others' lines aloud | F6 `Companion … ready` |
| 5.2 | Say its name and ask something | It answers; your partner stays quiet | Backend `companion_answer` |
| 5.3 | Ask it to leave | Friendly: says goodbye and walks off; otherwise may stay | Backend `companion_left` |
| 5.4 | Stand near idle villagers without talking | Chatter from each villager's position with grey captions; sometimes three speakers; no repeated lines | Backend `chatter_finished` |
| 5.5 | After hitting or gifting one of them, wait for chatter about you | Mood and bonds show (a hit villager is wary); "…spoke well of you to…" notes | Journal `chatter_heard` |

## 6. Night and danger

| # | Do | Expect | Check |
| --- | --- | --- | --- |
| 6.1 | `/time set night`, then talk to a villager | One line refusing ("off to bed… come back in the morning"); the conversation ends | Backend `bedtime` |
| 6.2 | Try again within 90 s | Action bar "heading to bed"; no new conversation | No new session in the backend |
| 6.3 | `/summon zombie` near a villager | The villager screams for help in its own voice; repeats every 8–14 s | Backend `shout_finished` |

## 7. Long-term memory

| # | Do | Expect | Check |
| --- | --- | --- | --- |
| 7.1 | Trade, gift, and promise something; walk away; wait 10 s | — | Backend `consolidation ok:true` |
| 7.2 | Return to the same villager | It brings up one shared moment; F7 shows `relationship.summary` and `recent` | F7 |
| 7.3 | Ask "what did I buy last time?" and "did you deal with…?" | Answers from what happened; no invented history | — |
| 7.4 | Restart the server and ask again | Still remembered | `data/higgs-journal.json` |
| 7.5 | Ask a witness who saw you hit someone | It knows ("I saw you hit Iris") | F7 `relationship.recent` |

## Results

| Item | Pass / fail | Notes |
| --- | --- | --- |
| | | |

## Known untested or limited areas

- Voice recognition and the voice model's tool choices vary between runs. Repeat any failed item once before filing it.
- Journal search is lexical. "What have others said about me?" finds nothing because "said" is a stop word, and "who did I bring a message from?" ranks the quest first. See `docs/benchmarks/journal-retrieval-v1.json`.
- Villagers with AI disabled cannot walk away, and only turn their back. Zombie screams rely on the zombie targeting that villager.
- Only zombies trigger screams; raids and other mobs do not.
- A gift sneak-used during a conversation gets a spoken reaction. An errand package sneak-used during a conversation completes silently, with an action bar and card only.
