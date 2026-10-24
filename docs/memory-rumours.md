<p><a href="../README.md"><img src="assets/logo.png" alt="HIGGS HRRM" width="64" /></a></p>

# HIGGS HRRM · The village remembers

[← HIGGS HRRM](../README.md) · [Setup](setup.md) · [Original plan](memory-rumours-plan.md)

Tell one villager a story. Visit again and ask what it remembers. Let that villager meet a neighbour, then find out what the neighbour heard.

Persistent memory, local gossip, and the particle indicator are implemented and covered by local regression tests. The actual voice-to-memory demo and particles still need live in-game rehearsal.

## Try it

Install the rebuilt mod on **both the server and client**, restart the server, and restart the updated backend. Follow the [setup guide](setup.md) for the existing voice controls.

1. Prepare two named adult villagers within 16 blocks of each other. Keep a third villager isolated, beyond gossip range with no intermediate villagers. All must be awake, with their chunks loaded.
2. Face the first villager, press **V**, wait for **Ready**, hold **B**, and say: **“Remember this: I found emeralds under the old well.”** Release **B** and wait for its response. This stores a player claim; it does not create or verify emeralds in the world.
3. End with **V**. Start another conversation with the same villager and ask: **“What did I tell you about the well?”** Its saved memory is supplied to the new session and can be refreshed through a tool.
4. End the conversation and wait for a brief burst of green particles above the nearby villagers. This indicates a new story was passed and saved. The default gossip check is every 10 seconds, with a 60-second cooldown for each pair.
5. Talk to the second villager: **“Have you heard any news about me?”** It can recall the rumour and name the villager that passed it along. Ask the isolated villager as a control; it has no knowledge of that story.
6. Restart the server cleanly and ask the first villager again to demonstrate persistence. Memories expire after seven real-time days by default, including time while the server is offline.

For a private-memory check, share a **different** statement and explicitly say **“Keep this secret; do not tell anyone.”** The prompt instructs the voice model to save it as private. The server prevents entries marked private from spreading. Secrecy classification is model-driven, so rehearse this too; it is not a guarantee that speech is understood correctly. Marking a story private later does not retract copies already learned by others.

Public stories can spread across players. Asking for village news can return public memories about other players, while player-scoped recall uses the current player's UUID and excludes other players' private entries.

## What the villagers know

- Each villager has its own saved collection. Knowledge is not shared globally.
- A direct memory means **a player told this villager something**. A rumour means **another villager passed it along**. Neither is treated as verified world state.
- Every rumour retains its root story, original villager, most recent source, and hop count. Rephrasing comes from the voice model; deliberate story distortion is not implemented.
- Recipients learn a story once while they retain its root ID. Hop limits prevent circulating stories from travelling indefinitely even after older entries are evicted.
- Stories newly received in a gossip pass can spread only on a later pass. Distance, dimension, eligibility, and pair cooldowns gate each transfer.
- Only loaded, awake, adult villagers participate. The server does not load chunks to spread gossip. It processes at most 32 source villagers, 16 neighbours per source, and 64 transfers per pass.
- Villagers signal gossip visually. There are no autonomous audible conversations or extra idle model calls.

## Save and configuration

The server saves summaries to **`<world>/data/higgs_hrrm_memories.json`**. The default local installation uses `server-cardboard/world/data/higgs_hrrm_memories.json`. This belongs with world backups and is excluded from Git with the world directory.

Writes run on one background writer, with ordered snapshots and atomic file replacement when supported. Tools report `saved` only after the write completes. Pending or failed writes are excluded from recall and further gossip. A bad or unsupported save is preserved and memory is disabled for that server run; voice and trades remain available. Check the server console for storage errors. Stop the server before repairing or restoring its memory file.

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

These checks do not validate microphone capture, the live model's memory decisions, or visible particles in a running client.
