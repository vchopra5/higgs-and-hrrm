<p><a href="../README.md"><img src="assets/logo.png" alt="HIGGS HRRM" width="64" /></a></p>

# HIGGS HRRM · Memory and rumours

[← HIGGS HRRM](../README.md) · [Visual identity](brand.md)

**Original design plan. Implementation and local tests are now in this branch; live in-game rehearsal remains pending.** See the [feature guide](memory-rumours.md) for the current behaviour and setup.

Branch: `codex/villager-memory-rumours`, created from `dev`.

## The experience

**You tell one villager a story. It remembers. The village starts talking.**

The first demo uses spoken conversations as the source of rumours. Nearby villagers exchange stories with a small visual gossip indicator. The player discovers what spread by talking to another villager. Villagers do not need to hold audible conversations with each other for this version.

### The first demo, step by step

1. **Tell the farmer:** “I found emeralds under the old well.” The farmer saves a short memory that this player made that claim. This does not establish that emeralds actually exist there.
2. **Prove memory:** End the conversation with **V**, start a new one, and ask, “What did I tell you?” The farmer recalls the story. Repeat after a server restart to demonstrate persistence.
3. **Let it spread:** The farmer and another villager stand near each other. After a short interval, a brief particle effect above them signals gossip. Passing the story requires no active player conversation.
4. **Discover the rumour:** Talk to the second villager: “Heard any news about me?” It can refer to the story and identify the farmer as its source.
5. **Show the boundary:** Ask a distant villager with no chain of contact the same question. It has not heard that story.

The dialogue above describes the desired behaviour; generated replies need not match a script.

## Behaviour proposed for the first version

| Part | Proposed rule |
| --- | --- |
| Identity | Memories belong to a villager UUID and refer to the player UUID, within one saved world. |
| What is remembered | Short summaries of notable player statements, names or preferences, and stories. No raw audio archive. |
| Persistence | Memories survive ending voice sessions, reconnects, and server restarts. |
| Recall | Supply a small relevant selection when a conversation starts; allow fresh lookup during the conversation. |
| Nearby villagers | Awake adult villagers in loaded chunks, in the same dimension, within a configurable radius. Start with 16 blocks. |
| Gossip timing | Check eligible neighbours about every 10 seconds, with bounded work and a per-pair cooldown. Tune during rehearsal. |
| Visual cue | A brief native particle effect above the participating villagers, visible to nearby players, only after a new story is actually delivered. |
| Attribution | Distinguish “you told me” from “I heard from the farmer.” Preserve the original speaker and the most recent source. |
| Story changes | Preserve the underlying claim for the first version. Villagers may phrase it differently; intentional exaggeration is later work. |
| Secrets | Proposed rule: explicitly private memories are retained but excluded from gossip. Ordinary stories can spread. |
| Limits | Bound memories per villager, story age, propagation hops, context size, and transfers per update. Make limits configurable. |

The radius, intervals, limits, and secret-handling rule are design defaults to review, not existing features.

## How it fits the current build

```mermaid
flowchart LR
    P["Player tells a story"] --> V["Villager A · memory"]
    V --> D["World save"]
    V --> G["Nearby gossip · visual cue"]
    G --> W["Villager B · heard from A"]
    W --> R["Player asks for news"]
    R --> H["Higgs speaks from recalled memories"]

    classDef forest fill:#10251f,color:#f6edcf,stroke:#76d59a,stroke-width:2px
    classDef emerald fill:#76d59a,color:#10251f,stroke:#10251f,stroke-width:2px
    class P,V,D,W,R forest
    class G,H emerald
```

**The Minecraft server owns memory and gossip.** It already knows the player, villager, world, and positions. It can therefore decide which villager learns a story and save that knowledge with the world. Idle gossip needs no model calls.

**The existing bridge carries two new tools:** `remember_conversation` to propose a bounded summary and `recall_memories` to retrieve relevant entries. Extend both the tool definitions in [`backend/lib/protocol.js`](../backend/lib/protocol.js) and argument validation in [`backend/lib/tools.js`](../backend/lib/tools.js). Add handlers on the server side alongside the existing trade handlers.

**The voice prompt receives memory as data.** A summary from the voice model remains an attributed player claim or interpretation, not a verified world event. A villager should acknowledge saving a memory only after a successful tool result. Existing trade lookup remains authoritative for stock and prices.

**Storage stays separate from voice sessions.** Add dedicated memory-store and gossip components under [`mod/src/main/java/dev/higgs/`](../mod/src/main/java/dev/higgs/). Keep [`HiggsVillagers.java`](../mod/src/main/java/dev/higgs/HiggsVillagers.java) responsible for connecting them to sessions and server lifecycle hooks. Load world-scoped state, save bounded snapshots without blocking ticks on disk writes, and flush pending changes on shutdown.

Each memory needs an ID, a root story ID, the holding villager, the subject player, a short summary, original source, most recent source, creation time, expiry, privacy flag, and hop count. Keep the root ID when passing stories along. Receiving the same root story twice must not create a new copy or another gossip indicator.

## Build order

1. **Persistent memory:** Add world-scoped storage, bounded entries, the remember/recall tools, and context loading. Prove recall across two conversations and a restart before adding spreading.
2. **Local gossip:** Add proximity checks, cooldowns, source tracking, expiry, and deduplication. Process a snapshot so a story cannot cross the entire village in one update. Recheck eligibility before delivery.
3. **Visual feedback:** Emit the small gossip effect after a successful transfer. Use existing Minecraft particles first, avoiding a new audio or rendering subsystem.
4. **Demo polish:** Prepare two nearby named villagers and one isolated control villager. Make the story, transfer, and recall easy to reproduce. Update the README and demo script only after live validation.

## What must work before calling it done

- A villager remembers a player statement after the voice session ends and after restarting the server.
- Two players have distinct memory records; recalling a player-specific story uses the correct UUID.
- A nearby eligible villager learns a story and can identify its source. An isolated villager cannot retrieve stories it never received.
- Distance, dimension, sleep, unloading, expiry, privacy, and hop limits prevent transfers as designed.
- Retries, repeated meetings, and A → B → A loops do not duplicate stories or produce endless indicators.
- The indicator appears only for a delivered story; cooldowns and bounded work keep gossip from overwhelming the server.
- Invalid memory tools and failed saves return errors without false “I remembered it” success results.
- Voice interruption, existing offer lookup, and native trading still work. Run backend tests, the mod build and relevant memory/gossip tests, then rehearse the actual microphone-to-recall flow.

## Later possibilities

Witnessed events, distorted rumours, friendships and rivalries, personality-specific willingness to gossip, and audible villager-to-villager dialogue can follow once persistent memory and local spreading work. They are outside the first implementation.
