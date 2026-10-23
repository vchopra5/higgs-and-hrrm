# Higgs voice bridge

Requires Node.js 22+ (24 recommended). From the repository root:

```sh
cp .env.example .env
# Fill BOSON_API_KEY and a random HIGGS_BRIDGE_TOKEN (32+ characters).
npm --prefix backend ci
npm --prefix backend start
```

The Minecraft server mod must use the same bridge token. Keep `.env` private. The bridge binds only to `127.0.0.1:8787` by default; this MVP is for a client and server on the same computer. Run `npm --prefix backend test` for local mock-provider integration tests; these do not use a paid API key.

## Protocol

All REST endpoints require `Authorization: Bearer <HIGGS_BRIDGE_TOKEN>`.

- `POST /sessions` with `{playerId,villagerId,context}` returns `{sessionId,ticket}`.
- `GET /sessions/:id/tools` returns pending `[{callId,name,arguments}]`. Polling repeats unacknowledged calls; the game server must deduplicate in-flight calls.
- `POST /sessions/:id/tools/:callId/result` with `{result}` acknowledges a tool and forwards its result to Higgs.
- `DELETE /sessions/:id` closes both audio sockets.

Connect the client to `ws://127.0.0.1:8787/audio?ticket=...` within 30 seconds. Tickets are single-use and grant access to one voice session, never the server REST API. Wait for `{type:"status",message:"Ready"}` before sending microphone audio.

Client messages are `{type:"audio",audio:"BASE64"}`, `{type:"commit"}`, `{type:"interrupt"}`, or `{type:"end"}`. Both audio directions use raw signed PCM16, little-endian, mono, 24 kHz. Releasing push-to-talk sends `commit`; press sends `interrupt` and clears local playback. Output messages are `audio`, `clear`, `status`, and `error`.

Sessions last at most ten minutes. Individual turns are capped at sixty seconds. Turns under 100 ms are discarded. Unanswered tools fail after fifteen seconds. Pending audio is bounded; slow consumers are disconnected. There is no persistent audio recording or conversation storage.

## Higgs mapping

The implementation follows Boson's [official session configuration](https://github.com/boson-ai/Higgs-Realtime-Tutorial/blob/main/src/realtime/sessionConfig.ts), [event definitions](https://github.com/boson-ai/Higgs-Realtime-Tutorial/blob/main/src/realtime/events.ts), and [tool calling guide](https://github.com/boson-ai/Higgs-Realtime-Tutorial/blob/main/docs/part-4.md):

- `session.update` configures `higgs-realtime`, audio formats, voice and tools.
- Manual push-to-talk sets `audio.input.turn_detection` to `null` and sends `input_audio_buffer.commit`, then `response.create`.
- `response.output_audio.delta` is relayed to the Minecraft client.
- Tool results are serialized into `conversation.item.create` function-call outputs. One `response.create` follows the completed tool batch.

`get_trades` queries authoritative game state. `propose_trade` opens the native trading screen; it never purchases anything or changes prices. The game server validates proximity, villager identity and offer availability.

Live API behavior and Minecraft microphone/playback still require testing with a real Boson key and game client. Interruption cancels generation and clears playback; precise conversation truncation to the last heard sample is not implemented in this MVP.
