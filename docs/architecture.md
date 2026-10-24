<p><a href="../README.md"><img src="assets/logo.png" alt="HIGGS HRRM" width="64" /></a></p>

# HIGGS HRRM · Architecture

[← HIGGS HRRM](../README.md) · [Visual identity](brand.md)

**Status: voice/trade prototype plus persistent personalities, memory and local gossip implemented; full live in-game validation pending.**
The code is in `mod/` and `backend/`. The diagram remains the target architecture.
The prototype uses default system audio rather than spatial playback; persona
comes from a saved world/villager profile and a shared versioned catalogue. The
bridge compiles trusted character directions and selects a mapped voice after
validating profile identifiers. [Personality implementation](villager-personalities.md). It uses a ten-minute session limit; hands-free sessions use
Higgs server-side voice activity detection. The live API accepts this configuration;
end-to-end microphone and in-game trade validation still need the Fabric client.
See the [setup guide](setup.md) for installation and controls.

Target Minecraft 26.1.2 and Fabric. Install our mod on both the client and server,
with client-only audio code kept out of the dedicated server entry point.
Run one companion backend process locally for the first version.

## What we build

Every box below represents custom project work. The three groups are runtime boundaries;
the boxes inside them are modules, not separate services.

```mermaid
flowchart TB
    subgraph clientMod ["Build: Fabric client mod"]
        interaction["Villager selection and hands-free microphone UI"]
        capture["Microphone capture and audio streaming"]
        playback["Speech buffering and spatial playback"]
    end

    subgraph serverMod ["Build: Fabric server mod"]
        sessions["Conversation authorization and lifecycle"]
        context["Villager context and persona mapping"]
        trades["Trade tools and native transaction adapter"]
    end

    subgraph backend ["Build: companion backend"]
        voice["Authenticated audio gateway and Higgs adapter"]
        tools["Tool routing and result correlation"]
    end

    interaction <-->|"Start or end session"| sessions
    sessions -->|"Short-lived audio ticket"| capture
    capture -->|"Microphone audio"| voice
    voice -->|"Speech and interruption events"| playback
    sessions <-->|"Create or revoke session"| voice
    context -->|"Persona and world snapshot"| voice
    voice <-->|"Tool calls and results"| tools
    tools <-->|"Bound session requests"| trades
    trades -->|"Inventory and trade-status updates"| interaction
```

## Responsibilities and boundaries

| Component | Custom work |
| --- | --- |
| Client interaction UI | Select a nearby villager, request a conversation, show listening/speaking/error state, and provide mute and end-conversation controls. Keep normal villager trading available. |
| Microphone capture | Request OS microphone access, capture and convert audio to the API format, send bounded chunks over a dedicated WebSocket, and stop capture when the session ends. Audio does not pass through Minecraft's game packets. |
| Speech playback | Decode and buffer returned audio, play it from the selected villager's location through the client audio facilities, and flush queued speech on interruption or session cancellation. Initially only the speaking player hears the response. |
| Conversation lifecycle | Validate the player and villager on the game server, enforce distance and one active conversation per player, and bind player UUID, villager UUID, and session ID. End the session on walking away, disconnect, death, or villager removal. Issue short-lived, single-use tickets for the matching audio session. |
| Villager context | Read profession, existing offers, and relevant world facts from Minecraft. Supply a configurable personality and voice selection keyed by villager UUID. Refresh trade facts through tools rather than trusting an old prompt snapshot. |
| Trade handlers | Implement live lookup, previews, bounded negotiation, proposals, confirmation, and cancellation for the bound villager. Quote exact bundles and require a fresh spoken yes before a native server-side exchange. Validate offer identifiers, input turns, stock, payment, and session eligibility on the game thread. Return the actual result to Higgs. |
| Higgs adapter | Keep the Boson API key on the backend, establish a Higgs Realtime session, supply persona/context/tool definitions, relay audio and events, and handle cancellation, timeouts, bounded buffering, and disconnect cleanup. |
| Tool router | Validate tool names and arguments, forward requests over an authenticated server-mod/backend connection, and return results correlated with the Higgs call ID. Deduplicate repeated calls within a session; never trust a model-supplied player or villager identity. |

The backend and server mod share a local credential. The client receives only
the short-lived audio ticket, never that credential or the Boson API key.
Bind backend endpoints to loopback for local development. Before remote use,
add TLS and explicit network configuration for the authenticated connections.

## Existing capabilities we reuse

These are dependencies, deliberately omitted from the build diagram:

- **Minecraft:** world generation, villager entities and AI, professions, offers,
  inventory, emeralds, merchant screen, player authentication, and world saving.
- **Minecraft's trading implementation:** validates and performs the actual
  exchange when the player takes the result in the merchant screen, including
  normal offer usage and experience updates. We do not build a second economy.
- **Fabric:** mod loading, lifecycle hooks, and client/server payload support.
- **Higgs Realtime:** speech understanding and generation, conversational model,
  turn detection, interruption events, and tool-call generation. We implement
  the application-side audio handling and tools, not separate speech models.
- **Cardboard:** stays in the current server installation for plugin support,
  but our native Fabric mod does not depend on the Bukkit compatibility layer.

Boson's [Realtime tutorial](https://www.boson.ai/blog/higgs-realtime-tutorial)
describes streaming audio, interruption handling, and executing tool calls.
Its [API documentation](https://docs.boson.ai/) is the source for exact event
schemas, audio formats, and session configuration when implementation begins.

## One conversation, end to end

1. The player targets a villager and starts talking. The server checks proximity
   and creates a conversation bound to that player and villager.
2. The backend creates the Higgs session using the server-provided persona and
   context. The client authenticates its audio connection with its session ticket.
3. An active, unmuted session streams microphone audio to the backend and then Higgs. Spoken
   replies stream back into the client's playback buffer.
4. For “What can I buy?”, Higgs calls `get_trades`. The server mod reads the
   villager's current offers and returns them through the backend.
5. For “I'd like that bread trade”, Higgs calls `propose_trade` with an offer identifier
   and optional bounded quantity, budget, or discount request. The server returns
   the exact bundle, costs, and a proposal ID, without opening a screen.
6. A fresh explicit spoken yes leads to `confirm_trade`. The server checks the
   proposal, trusted input turn, availability, price, stock, payment, and inventory,
   then executes one native server-side exchange. Only a successful receipt may
   be reported as purchased. Declines cancel the proposal. Model interpretation
   supplies consent intent; server validation enforces the transaction boundary.
7. Ending the conversation or losing eligibility revokes the session and stops
   microphone capture, playback, and pending tool requests.

## First implementation scope

Build voice-only conversation with one nearby villager first, then add live
offer lookup and merchant-screen handoff. Test permission denial, interrupted
speech, invalid offers, moving out of range, and disconnects before expanding.

The first version uses existing trades and per-session voice conversation history.
Persistent conversation summaries now live in a separate world-scoped memory store.
Bounded negotiation, relationship-based price ranges, and voice-confirmed purchases
are implemented. Speech audible to nearby players remains later work. No database
service is needed.

## Persistent memory and nearby gossip

`VillagerMemoryStore` keeps bounded summaries keyed by villager and player UUID in
`<world>/data/higgs_hrrm_memories.json`. It writes ordered snapshots on a background
writer and exposes memories for recall and gossip after a successful save. The
`remember_conversation` and `recall_memories` tools reuse the authenticated game-tool
route; the server binds identities rather than accepting UUIDs from the model.
Memory summaries are attributed player claims, not observed world facts.

`VillagerMemoryService` adds the current player's saved memories to session context,
handles fresh recall, and samples loaded villagers for proximity-based transfers.
`VillagerGossip` enforces eligibility, dimension, distance, pair cooldowns, and root
deduplication. A pass uses a snapshot so newly heard stories cannot cascade in the
same pass. Private entries and exhausted hop counts cannot spread. A successful
save triggers native green particles above the participants; there is no autonomous
background voice session between villagers. See the [feature guide](memory-rumours.md) for limits,
configuration, failure behaviour, and live rehearsal steps.

### Automatic story selection and conversation openings

The voice model assesses the meaning of notable statements during normal speech and supplies an interest score, reason, and audience professions to the memory tool. Public stories above the threshold qualify for gossip. The server ranks candidates by interest, recipient profession, freshness, and hop count. Low-interest preferences remain useful memories without becoming rumours. Existing schema-1 stores migrate to schema 2 with their identities and privacy flags preserved.

Session context includes ranked heard rumours and a fresh opening topic when available. `RumourConversation` limits repeated opening topics per player/villager/story. Once the configured provider session is confirmed, the bridge requests an initial response so the villager can greet the player and react to its selected story without requiring a spoken prompt. Relevance and freshness guide the opener; the model chooses the wording. The existing player interruption path remains active.
