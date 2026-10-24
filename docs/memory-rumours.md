<p><a href="../README.md"><img src="assets/logo.png" alt="HIGGS HRRM" width="64" /></a></p>

# HIGGS HRRM · The village remembers

[← HIGGS HRRM](../README.md) · [Setup](setup.md) · [Original plan](memory-rumours-plan.md)

Mention something surprising in normal conversation. The villager decides whether it is worth remembering and spreading. Let it meet a neighbour, then start a conversation with that neighbour and hear its reaction.

Persistent memory, local gossip, and the particle indicator are implemented and covered by local regression tests. The actual voice-to-memory demo and particles still need live in-game rehearsal.

## Try it

Install the rebuilt mod on **both the server and client**, restart the server, and restart the updated backend. Follow the [setup guide](setup.md) for the existing voice controls.

1. Prepare two named adult villagers within 16 blocks of each other. Keep a third villager isolated, beyond gossip range with no intermediate villagers. All must be awake, with their chunks loaded.
2. Look at the first villager, wait for the automatic connection, and let it greet you. Hold **B** and casually say: **“I found a hidden library under the old well. There were enchanted books everywhere.”** Release **B**. The model decides what to save and how newsworthy it is; no “remember this” command is required. The story remains a player claim, not a verified discovery.
3. Walk more than 12 blocks away to end the session. The saved summary is available across visits. For an explicit persistence check, start another conversation and refer naturally to the well or ask what you discussed earlier.
4. End the conversation and wait for a brief burst of green particles above the nearby villagers. This indicates a new story was passed and saved. The default gossip check is every 10 seconds. [Personalities](villager-personalities.md) add a 5–60 second delay after hearing a story and scale the default 60-second pair cooldown.
5. Look at the second villager to connect. If the first session is still active, tap **B** while facing the neighbour to switch; release it and let the neighbour speak first. When it has a fresh noteworthy rumour, it can lead with a curious or surprised reaction and credit the source. You do not need to ask for news. A librarian prioritises the library story. The isolated control villager should offer a normal greeting because it has not heard the story.
6. Restart the server cleanly and ask the first villager again to demonstrate persistence. Memories expire after seven real-time days by default, including time while the server is offline.

For a private-memory check, share a **different** statement and explicitly say **“Keep this secret; do not tell anyone.”** The prompt instructs the voice model to save it as private. The server prevents entries marked private from spreading. Secrecy classification is model-driven, so rehearse this too; it is not a guarantee that speech is understood correctly. Marking a story private later does not retract copies already learned by others.

Public stories can spread across players. Asking for village news can return public memories about other players, while player-scoped recall uses the current player's UUID and excludes other players' private entries.

## What the villagers know

- Each villager has its own saved collection. Knowledge is not shared globally.
- The voice model assesses meaning, surprise, danger, scandal, discoveries, and consequences during normal conversation. It supplies an interest assessment, a short reason, and relevant professions. This is model judgment, not a keyword trigger or a hardcoded list of lines.
- Low-interest information can remain a personal memory. Public entries rated at least 55/100 qualify as gossip. Professions, freshness, connection to the current player, and hop count affect ranking. Internal scores are not spoken to the player.
- Looking at a nearby villager starts a session automatically. Its interruptible greeting begins after the voice configuration is confirmed; a hit-triggered session prioritises its reaction instead. Context includes known public rumours and, when available, a selected fresh opening topic. The villager is instructed to follow the current conversation rather than force gossip into every reply.
- An opening topic has a ten-minute cooldown per villager/player/story. This recent-topic tracking is held in memory and resets on server restart; stories remain persistent. A topic is marked offered only after the configured voice is ready and the bridge requests its greeting. Failed setup and a hit reaction that replaces the greeting do not consume the cooldown. A player interruption or the model choosing a different opening can still consume it; the story remains available for explicit recall.
- A direct memory means **a player told this villager something**. A rumour means **another villager passed it along**. Neither is treated as verified world state.
- Every rumour retains its root story, original villager, most recent source, and hop count. Rephrasing comes from the voice model; deliberate story distortion is not implemented.
- Recipients learn a story once while they retain its root ID. Hop limits prevent circulating stories from travelling indefinitely even after older entries are evicted.
- Stories newly received in a gossip pass can spread only on a later pass. Distance, dimension, eligibility, and pair cooldowns gate each transfer.
- Only loaded, awake, adult villagers participate. The server does not load chunks to spread gossip. It processes at most 32 source villagers, 16 neighbours per source, and 64 transfers per pass.
- Villagers signal gossip visually. There are no background audible conversations between villagers or extra idle model calls.

## Save and configuration

The server saves summaries to **`<world>/data/higgs_hrrm_memories.json`**. The default local installation uses `server-cardboard/world/data/higgs_hrrm_memories.json`. This belongs with world backups and is excluded from Git with the world directory.

Writes run on one background writer, with ordered snapshots and atomic file replacement when supported. Tools report `saved` only after the write completes. Pending or failed writes are excluded from recall and further gossip. Existing schema-1 and schema-2 saves are migrated to schema 3 without changing story identities. Schema-1 stories receive a neutral interest assessment until reassessed. A new per-holder `learnedAt` starts conservatively at load time for old saves and persists thereafter; personality delays use it instead of the original story timestamp. A bad or unsupported save is preserved and memory is disabled for that server run; voice and trades remain available. Check the server console for storage errors. Stop the server before repairing or restoring its memory file.

Optional root `.env` settings, read by the server mod at startup:

| Setting | Default | Allowed |
| --- | --- | --- |
| `HIGGS_MEMORY_MAX_PER_VILLAGER` | `64` | 1–256 entries |
| `HIGGS_MEMORY_TTL_HOURS` | `168` | 1–8760 real-time hours for new entries |
| `HIGGS_GOSSIP_RADIUS` | `16` | 1–64 blocks |
| `HIGGS_GOSSIP_INTERVAL_SECONDS` | `10` | 1–3600 seconds of server ticks at 20 TPS |
| `HIGGS_GOSSIP_COOLDOWN_SECONDS` | `60` | 1–3600 real-time seconds per pair |
| `HIGGS_GOSSIP_MAX_HOPS` | `3` | 0–8; 0 disables spreading |

The store also caps total entries at 4096, summary length at 320 characters, recall queries at 160 characters, and each returned recall at eight entries. Invalid settings fall back to their defaults. Restart the server after changing settings. Existing expiry timestamps remain unchanged when the lifetime setting changes.

## Developer checks

```sh
npm --prefix backend test
./scripts/build-mod.sh
```

The build includes standalone `testMemory` checks for persistence, player separation, gossip boundaries, source attribution, privacy, deduplication, cooldowns, hop limits, expiry, failed writes, and unreadable saves. Backend tests exercise memory-tool validation, result batching, duplicate calls, and stale calls after interruption.

Tests also cover interest metadata, routine-story filtering, profession relevance, opening-topic cooldowns, old-save migration, and interruptible automatic greetings. They do not validate microphone capture, the live model's judgment of a real utterance, or visible particles in a running client. Story selection and the wording of reactions need live rehearsal; generated dialogue is not a fixed script.
