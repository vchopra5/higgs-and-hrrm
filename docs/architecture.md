# Talking and trading villagers: build architecture

**Status: proposed, not implemented.** The Fabric/Cardboard server is running;
the components below are the application code we still need to build.

Target Minecraft 26.1.2 and Fabric. Install our mod on both the client and server,
with client-only audio code kept out of the dedicated server entry point.
Run one companion backend process locally for the first version.

## What we build

Every box below is custom project code. The three groups are runtime boundaries;
the boxes inside them are modules, not separate services.

```mermaid
flowchart TB
    subgraph clientMod ["Build: Fabric client mod"]
        interaction["Villager selection and push-to-talk UI"]
        capture["Microphone capture and audio streaming"]
        playback["Speech buffering and spatial playback"]
    end

    subgraph serverMod ["Build: Fabric server mod"]
        sessions["Conversation authorization and lifecycle"]
        context["Villager context and persona mapping"]
        trades["Trade tool handlers and merchant UI adapter"]
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
    trades -->|"Open existing trading screen"| interaction
```

## Responsibilities and boundaries

| Component | Custom work |
| --- | --- |
| Client interaction UI | Select a nearby villager, request a conversation, show listening/speaking/error state, and provide push-to-talk and end-conversation controls. Keep normal villager trading available. |
| Microphone capture | Request OS microphone access, capture and convert audio to the API format, send bounded chunks over a dedicated WebSocket, and stop capture when the session ends. Audio does not pass through Minecraft's game packets. |
| Speech playback | Decode and buffer returned audio, play it from the selected villager's location through the client audio facilities, and flush queued speech on interruption or session cancellation. Initially only the speaking player hears the response. |
| Conversation lifecycle | Validate the player and villager on the game server, enforce distance and one active conversation per player, and bind player UUID, villager UUID, and session ID. End the session on walking away, disconnect, death, or villager removal. Issue short-lived, single-use tickets for the matching audio session. |
| Villager context | Read profession, existing offers, and relevant world facts from Minecraft. Supply a configurable personality and voice selection keyed by villager UUID. Refresh trade facts through tools rather than trusting an old prompt snapshot. |
| Trade handlers | Implement `get_trades` and `propose_trade` for the bound villager. Validate offer identifiers and session eligibility on the game thread; open the existing merchant screen for an eligible proposal. Return success or failure to Higgs. |
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
3. Push-to-talk sends microphone audio to the backend and then Higgs. Spoken
   replies stream back into the client's playback buffer.
4. For “What can I buy?”, Higgs calls `get_trades`. The server mod reads the
   villager's current offers and returns them through the backend.
5. For “I'd like that bread trade”, Higgs calls `propose_trade` with an offer
   identifier. The server mod rechecks the session and offer and opens the
   existing merchant screen. This is a proposal, not a purchase.
6. The player completes the trade in Minecraft's screen. Vanilla trading remains
   authoritative. If we want spoken purchase confirmation, add a server-side
   observer of completed trades and send that verified result to the session;
   opening the screen alone must never be reported as a completed purchase.
7. Ending the conversation or losing eligibility revokes the session and stops
   microphone capture, playback, and pending tool requests.

## First implementation scope

Build voice-only conversation with one nearby villager first, then add live
offer lookup and merchant-screen handoff. Test permission denial, interrupted
speech, invalid offers, moving out of range, and disconnects before expanding.

The first version uses existing trades and per-session conversation history.
Negotiated prices, voice-only purchase confirmation, persistent conversation
memory, and speech audible to nearby players are later features with additional
validation and synchronization work. No custom database is needed for this MVP.
