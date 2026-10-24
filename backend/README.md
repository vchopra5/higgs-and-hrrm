<p><a href="../README.md"><img src="../docs/assets/logo.png" alt="HIGGS HRRM" width="64" /></a></p>

# HIGGS HRRM · Voice bridge

[← HIGGS HRRM](../README.md) · [Visual identity](../docs/brand.md)

Requires Node.js 22+ (24 recommended). From the repository root:

```sh
cp .env.example .env
# Fill BOSON_API_KEY and a random HIGGS_BRIDGE_TOKEN (32+ characters).
npm --prefix backend ci
npm --prefix backend start
```

The Minecraft server mod must use the same bridge token. Keep `.env` private. The bridge binds only to `127.0.0.1:8787` by default; this MVP is for a client and server on the same computer. Run `npm --prefix backend test` for local mock-provider integration tests; these do not use a paid API key.

## Code layout

`src.js` loads configuration and starts the process. It continues to export
`createBridge` and `sessionConfig` for existing tests and integrations.

| Module | Responsibility |
| --- | --- |
| `lib/bridge.js` | Authenticated HTTP routes, audio-ticket upgrades, session registry, and process cleanup. |
| `lib/session.js` | One conversation's provider connection, lifecycle, response events, and diagnostics. |
| `lib/audio.js` | PCM validation, push-to-talk commits, VAD events, interruptions, and reply forwarding. |
| `lib/tools.js` | Trade-call validation, deduplication, batch completion, and timeouts. |
| `lib/protocol.js` | Higgs session configuration, shared limits, JSON transport, and safe diagnostic codes. |

Audio and tool events with an older response ID are ignored once a newer response
starts. Provider errors preserve the sanitized error type when no code is supplied;
provider-supplied message text remains excluded from client errors and logs.

## Protocol

All REST endpoints require `Authorization: Bearer <HIGGS_BRIDGE_TOKEN>`.

- `POST /sessions` with `{playerId,villagerId,context}` returns `{sessionId,ticket}`.
- `GET /sessions/:id/tools` returns pending `[{callId,name,arguments}]`. Polling repeats unacknowledged calls; the game server must deduplicate in-flight calls.
- `POST /sessions/:id/tools/:callId/result` with `{result}` acknowledges a tool and forwards its result to Higgs.
- `DELETE /sessions/:id` closes both audio sockets.

Connect the client to `ws://127.0.0.1:8787/audio?ticket=...` within 30 seconds. Tickets are single-use and grant access to one voice session, never the server REST API. Wait for `{type:"status",message:"Ready"}` before sending microphone audio.

Client messages are `{type:"audio",audio:"BASE64"}`, `{type:"commit"}`, `{type:"interrupt"}`, or `{type:"end"}`. Both audio directions use raw signed PCM16, little-endian, mono, 24 kHz. The default is hands-free: stream microphone audio continuously after `Ready`. Higgs detects turn boundaries and starts responses automatically. The bridge emits `{type:"speech",active:true/false}` on speech start/stop and `clear` on interruption to flush local playback. Output messages are `audio`, `speech`, `clear`, `status`, and `error`. Recoverable errors have `fatal:false` and a sanitized `code`; the client should display them without closing the session. Transport failure has `fatal:true`.

The Minecraft client uses push-to-talk and connects with `&mode=ptt`: send `interrupt` on press and `commit` on release. If an interrupted response is still cancelling, the next response request waits for its completion acknowledgement; stale tool calls are discarded. In default hands-free mode, `commit` is ignored to avoid duplicate responses.

Sessions last at most ten minutes. Hands-free streaming has no cumulative audio-byte cap: silence and repeated turns do not exhaust an input counter. Legacy push-to-talk turns are capped at sixty seconds; PTT turns under 100 ms are discarded. The ten-minute session limit is reported explicitly. Sessions never restart automatically. Unanswered tools fail after fifteen seconds. Pending audio is bounded; slow consumers are disconnected. The bridge stores no audio recordings or transcripts; the game server separately persists short conversation summaries for villager memory.

## Higgs mapping

The implementation follows Boson's [official session configuration](https://github.com/boson-ai/Higgs-Realtime-Tutorial/blob/main/src/realtime/sessionConfig.ts), [event definitions](https://github.com/boson-ai/Higgs-Realtime-Tutorial/blob/main/src/realtime/events.ts), and [tool calling guide](https://github.com/boson-ai/Higgs-Realtime-Tutorial/blob/main/docs/part-4.md):

- `session.update` configures `higgs-realtime`, audio formats, voice and tools.
- Hands-free mode sets `audio.input.turn_detection` to `{type:"server_vad",silence_duration_ms:900}`. It sends only `input_audio_buffer.append`; Higgs owns commits, automatic replies and interruption of active generation. See Boson’s [turn-detection guide](https://github.com/boson-ai/Higgs-Realtime-Tutorial/blob/main/docs/part-2.md).
- Optional manual push-to-talk sets `audio.input.turn_detection` to `null` and sends `input_audio_buffer.commit`, then `response.create`.
- `response.output_audio.delta` is relayed to the Minecraft client.
- Input transcription uses `higgs-stt-3.1`. Completed user transcripts and streaming villager audio transcripts are relayed as `{type:"transcript",speaker:"you"|"villager",id,text,replace}` for transient captions. User captions arrive after speech processing; final events replace accumulated text.
- Tool results are serialized into `conversation.item.create` function-call outputs. One `response.create` follows the completed tool batch.

`get_trades` queries authoritative game state. `propose_trade` returns an exact
player-specific quote without opening any screen. `confirm_trade` requires one
spoken confirmation after the quote; the server guards a new input
turn ID, expiry and single-use purchase. `cancel_trade` clears the quote when
the player refuses. The final native trade happens entirely server-side, then
its result is spoken by Higgs. The model interprets affirmative speech; the
server enforces turn separation and transaction validation.

`remember_conversation` accepts `{summary, private, interest, reason, audiences}`. The voice model judges what normal speech is worth saving, using a 0–100 newsworthiness assessment and relevant professions. The server persists the statement and gates public gossip at 55; routine memories can still be recalled. `recall_memories` accepts `{query, scope}` where scope is `player` or `village`. The server supplies UUIDs, sources, storage, and gossip eligibility. Private entries cannot spread; village scope can include public stories about other players. The bridge validates bounded strings and booleans and preserves the existing batch, duplicate-call, and interruption handling. A memory write returns `saved` only after disk persistence completes. See the [memory and rumours guide](../docs/memory-rumours.md).

Live API behavior and Minecraft microphone/playback still require testing with a real Boson key and game client. Interruption cancels generation and clears playback; precise conversation truncation to the last heard sample is not implemented in this MVP.

Closure logs include a session ID, stable reason and WebSocket code, never tickets, credentials, audio, transcripts or provider-supplied close text. Nonfatal provider errors log only a sanitized code. For hands-free use, headphones prevent speaker audio being recaptured as user speech; this native audio path does not yet implement echo cancellation.

The client can send `{type:"mute",muted:true/false}` even before Ready. Muting pauses capture, clears the upstream input buffer and discards any unfinished utterance. Unmuting clears stale input before the next speech turn. Neither action cancels an existing assistant reply. The bridge sends `{type:"end",code,message}` on closure; session setup rejection is fatal, while ordinary provider errors after Ready are recoverable.

Normal game sessions set `context.autoGreeting=true`; sessions started by a hit skip the normal greeting. After confirmation of the configured voice session, the bridge sends one `response.create` without waiting for player audio. A provided `openingRumour` lets the villager naturally lead with relevant news. Initial default-session events and duplicate Ready events cannot trigger extra greetings. The player can interrupt the opening through the normal push-to-talk path. A queued hit reaction takes priority, and early player speech waits for cancellation before starting its reply.

## Live voice diagnostics

While connected the bridge sends a `diagnostics` snapshot every second: forwarded PCM `inputBytes`/`outputBytes`, `speechStarts`/`speechStops`, `responses`, `firstAudioLatencyMs` (speech end, or PTT commit, to first audio forwarded, null until available), `lastEvent`, `lastError`, `pendingTools`, and `upstreamReady`. Additional `suppressedAudio` byte counters distinguish `speaking`, `cancelled`, `staleResponse`, and `backpressure`; `speaking`/`cancelled` flags expose the current gate. Counters reset on a new conversation. These are transport/provider diagnostics, not proof of audible playback.

If input bytes stay flat, inspect microphone capture or mute. If bytes grow without speech events, Higgs has not detected speech. Speech stops without responses points to provider turn handling; responses without output points to provider generation or suppression counters. Output bytes growing without audible sound points to client playback. Nonzero pending tools can delay the response until the Minecraft server responds. The backend console records sanitized `voice_stage` timestamps for Ready, speech start/stop, response start/end and first audio, without logging audio, transcripts, context, tickets or API keys.

### Investigation evidence (2026-10-23)

No provider bug is established by the available logs. The configured model is
`higgs-realtime`; the captured logs do not identify its underlying model version
or confirm account access to Higgs 1.5.

- Direct Higgs synthetic-speech test: first audio approximately 418 ms after
  speech stopped; response completed.
- Synthetic speech through the updated bridge, with a simulated game-server
  trade lookup: speech stopped at 3510 ms, first audio at 4332 ms (822 ms latency),
  final response completed at 5312 ms. Received 426240 PCM bytes; no provider
  errors, suppressed audio, or pending tools remained.
- A probe without a simulated game-server tool handler waited on one pending
  trade lookup. This was an incomplete test setup, not evidence of a provider stall.
- The bridge had a cancellation-state bug: if `response.created` preceded
  `speech_stopped`, the cancelled flag could remain set and discard the new reply.
  This is fixed and regression-tested with simulated event ordering. That ordering
  was not observed in the successful live probe, and is not established as the
  cause of the user's original symptom.
- The available original game log shows the session ending when the player died;
  it predates the microphone counters and cannot establish whether speech reached
  Higgs. The synthetic probes do not validate the actual game microphone or speakers.

## Negotiation tools

`propose_trade` accepts optional integer `quantity` (output count) and `payment`
(primary input count), each 1–64, and optional boolean `discount`. Omitting quantity
with payment requests a budget-based bundle. Omitting both requests the default
bundle; `discount:true` can increase output for the same budget when the price
cannot be reduced by a whole payment item. Always read the returned quote or
counteroffer and obtain a fresh spoken confirmation.

`report_treatment` accepts `polite`, `neutral`, or `rude` for explicit treatment in
the latest utterance. Its trusted input-turn ID prevents duplicate assessments;
the game server bounds mood, persistent relationship changes, and pricing. Polite
requests or thanks qualify; bargaining, refusals, accents and speaking style do not
qualify as rudeness. Relationship data stores scores and timestamps, not transcripts.


## Persistent characters

Game sessions can supply `context.personality` with exactly `catalogueVersion`, `archetypeId`, `styleId` and integer `familiarity` (0–20). The bridge resolves these against the shared versioned catalogue; names, jobs and memories remain quoted context data. Approved mappings select `chloe`, `oliver` or `marcus` by default, with backend-only `HIGGS_VOICE_WARM`, `HIGGS_VOICE_THOUGHTFUL` and `HIGGS_VOICE_STEADY` overrides.

Character readiness requires acknowledgement of the exact instructions and selected voice. A structured voice-parameter rejection permits one default-voice retry, retaining character instructions. Diagnostics expose `voice` and `voiceFallback`; unrelated provider errors keep their existing handling. An internal `_personality_ready` queue entry informs the bound game session of a confirmed visit. It never appears in provider tools, does not generate a function output and survives audio interruption. See [setup, persistence and validation limits](../docs/villager-personalities.md).
