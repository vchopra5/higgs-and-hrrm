<p><a href="../README.md"><img src="assets/logo.png" alt="HIGGS HRRM" width="64" /></a></p>

# HIGGS HRRM · Playtest checklist

[← HIGGS HRRM](../README.md) · [Setup](setup.md) · [Memory evaluation](memory-evaluation.md)

One pass through every feature in a running game. Everything here has unit or integration tests, and some parts have live Boson probes, but **none of it has been verified end to end in-game** until this list is run. Tick each item, and note anything that differs from **Expect** in the results table at the end.

## Before you start

- Rebuild and install the jar on the **server and client**, then restart the Node backend, the server and the client.
- Turn on fast chatter for the run, then remove these settings afterwards: `HIGGS_CHATTER_INTERVAL_SECONDS=10`, `HIGGS_CHATTER_CHANCE_PERCENT=100`, `HIGGS_CHATTER_COOLDOWN_SECONDS=30`.
- Keep the backend terminal and `server-cardboard/logs/latest.log` open. **F6** shows the conversation and Boson, **F7** the context, **F8** audio and connection, and **F9** your quests, earned deals, errand and villager standings.
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
| 6.2a | Interrupt the goodnight before it begins, then `/time set day` and reconnect | The bedtime session ends; a fresh daytime conversation answers normally | No connected-but-silent session |
| 6.2b | Switch to day while the goodnight is playing, including with a NoAI villager | The old bedtime session ends within the server check interval; targeting reconnects | No stale REST refusal |
| 6.3 | `/summon zombie` near a villager | The villager screams for help in its own voice; repeats every 8–14 s | Backend `shout_finished` |

## 7. Long-term memory

| # | Do | Expect | Check |
| --- | --- | --- | --- |
| 7.1 | Trade, gift, and promise something; walk away; wait 10 s | — | Backend `consolidation ok:true` |
| 7.2 | Return to the same villager | It brings up one shared moment; F7 shows `relationship.summary` and `recent` | F7 |
| 7.3 | Ask "what did I buy last time?" and "did you deal with…?" | Answers from what happened; no invented history | — |
| 7.4 | Restart the server and ask again | Still remembered | `data/higgs-journal.json` |
| 7.5 | Ask a witness who saw you hit someone | It knows ("I saw you hit Iris") | F7 `relationship.recent` |

## 8. Walking together

| # | Do | Expect | Check |
| --- | --- | --- | --- |
| 8.1 | Ask a friendly villager to walk with you; walk around the village | It follows and keeps chatting; every ~45 s it remarks on what is nearby | Backend `game_event walk_scenery` |
| 8.2 | Sprint ahead | "Wait for me!"; past 16 blocks for 10 s the walk ends | Backend `walk_lagging` |
| 8.3 | Walk to the village edge | It says it will go no further and heads home | Backend `walk_boundary` |
| 8.4 | Do the same with a NoAI demo villager | It walks, then returns to its exact spot with AI off again | `/data get entity @e[type=villager,limit=1,sort=nearest] NoAI` → `1b` |
| 8.5 | Ask an annoyed villager to walk, or any villager at night | It declines | Server log `Tool=follow_player status=refused` |
| 8.6 | After a walk, talk to it again | It may recall the walk | F7 `relationship.recent` has the outing |

## 9. Directions

| # | Do | Expect | Check |
| --- | --- | --- | --- |
| 9.1 | Ask "take me to the librarian" | "Follow me"; it walks there while chatting | Server log `Tool=guide_player status=guiding` |
| 9.2 | Hang back more than 8 blocks | It stops, waits and calls "this way!" | Backend `walk_lagging` |
| 9.3 | Arrive | Conversation switches to the librarian, who greets you and thanks the guide; the guide is outlined as companion | Server log `guided … to …` |
| 9.4 | Ask "show me the bell" | It leads you to the bell and says you're here, then heads home after you part | Backend `guide_arrived` |
| 9.5 | Ask for something the village lacks ("the cartographer" in a village without one) | It says it doesn't know of one and suggests someone else | `status=not_found` |
| 9.6 | Ask a NoAI demo villager | It guides, then returns to its exact spot with AI off | `/data get entity … NoAI` |
| 9.7 | Ask a stranger "where's the nearest forest?" | Direction and rough distance as a rumour; grey chat note | Server log `Rumour is_forest asked of …` |
| 9.8 | Ask about something absent ("an igloo" far from snow) | It says it has never heard of one; no invented direction | `Rumour igloo … none nearby` |
| 9.9 | As a friend, ask "take me to the nearest pyramid" (within ~320 blocks) | It leads you out, comments on the way, points it out, then heads home | Backend `expedition_arrived` |
| 9.10 | Walk away from it on the way home | Once nobody is within 32 blocks it is back in the village | Find it at home |

## 10. Growing closer

| # | Do | Expect | Check |
| --- | --- | --- | --- |
| 10.1 | Meet a new villager, then trade, gift and chat with it over a few conversations | Action bar "… now considers you a friend", hearts and a cheer | Server log `Relationship milestone … friend`; F7 `relationship.stage` |
| 10.2 | Promise something ("I'll deal with the wolves"), end the conversation, come back | It asks how the promise is going | F7 `relationship.openPromises` |
| 10.3 | Say you did it, then end the conversation | After consolidation, standing rises by 1 | Backend `consolidation` log; journal `promise_kept` |
| 10.4 | Keep going to close friend | It gives you a nickname once and uses it from then on | Server log `Tool=set_nickname status=named` |
| 10.5 | Ask a close friend to "write down our story" | A signed written book, *Our story*, with milestones and shared moments | `Tool=write_our_story status=given` |
| 10.6 | Ask again | It says you already have it | `status=already_given` |
| 10.7 | Reach confidant, then greet it on two different in-game days | You get one of its wares each day, and it mentions the gift | Journal `gave_gift` |
| 10.8 | Restart the server | Stage, nickname and open promises persist | F7 after restart |

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

### Companion earshot regression

- Keep the main villager nearby; move beyond 10 blocks from the companion. Within half a second, its outline and queued speech should stop and the main conversation should stay connected.
- Repeat while the companion is answering a direct question, before its first audio arrives. The main villager should answer the outstanding turn once.
- Return within 8 blocks after five seconds. The companion may join again, without knowing dialogue spoken during its absence.
- Walk repeatedly between 8 and 10 blocks: an existing companion stays; a departed one cannot rejoin until within 8 blocks.
- Repeat departure by putting the companion to sleep or removing it. No late captions/audio should appear. Normal voluntary goodbyes should still finish playing.
- B retains its existing speaking and partner-selection behavior.

### Gossip-aware conversation start

- Look directly at gossiping villagers without pressing B: their exchange continues and no player voice session starts.
- Press B while looking at a gossiping adult villager within 8 blocks: the conversation starts; release and press again to speak after the greeting.
- Look at a non-gossiping adult villager: it connects automatically as before. With no active partner, B can still select the nearest available villager when looking away.
- During an active conversation, looking away does not disconnect it; B still speaks to the selected partner. A fresh B press aimed at another villager switches partners.
