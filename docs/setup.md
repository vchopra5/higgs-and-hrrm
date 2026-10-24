<p><a href="../README.md"><img src="assets/logo.png" alt="HIGGS HRRM" width="64" /></a></p>

# HIGGS HRRM · Setup

[← HIGGS HRRM](../README.md) · [Visual identity](brand.md)

Local Minecraft Java Edition **26.1.2** server with **Fabric 0.20.1** and
**Cardboard 26.1-15** for the villager conversation project.
Fabric API and iCommon are installed. The Higgs integration prototype lives in
`mod/` and `backend/`; live Boson and microphone testing is still required.
Exact mod versions, download URLs, and checksums are in
[`server-cardboard/components.json`](../server-cardboard/components.json).

## Villager voice prototype

See the [build architecture and diagram](architecture.md). The first pass
implements Fabric client/server code, hands-free realtime audio, a Higgs backend,
live trade lookup, and the native merchant-screen handoff.

1. Put your Boson key in the root `.env` as `BOSON_API_KEY=...`. Keep the generated
   `HIGGS_BRIDGE_TOKEN`; the server mod and backend must share it. For a fresh
   clone, copy `.env.example` to `.env` and generate a token with
   `openssl rand -hex 32`. Never commit `.env`.
2. Install Node.js 22+ and run `npm --prefix backend ci`, then
   `npm --prefix backend start` in a terminal. Restart the backend after changing
   the key; restart the game server if you change the bridge token.
3. Build using JDK 25 and `./scripts/build-mod.sh`. The JAR is
   `mod/build/libs/higgs-villagers-0.1.0.jar`.
4. With the game server stopped, copy that JAR into `server-cardboard/mods/`.
   Install the same JAR plus Fabric API in your **Fabric 26.1.2 client** mods
   directory. Cardboard and iCommon are server-side dependencies, not client
   requirements for this mod.
5. Start the server and join. Look at an adult villager and press **V**.
   Wait for **Ready**, then **hold B to speak and release B to send**.
   Press **V** again to leave. Key bindings can be changed in Minecraft controls.

Allow microphone access for the Minecraft/Java process when macOS asks. On macOS,
use Prism Launcher: the official launcher may block microphone permission requests.
The microphone sends audio only while B is held in the focused game window.
Releasing B, opening a menu, or switching away ends the turn. Pressing B during a
reply interrupts it. The top-right HUD shows microphone strength while you speak.
Speech plays through the default system audio device, not Minecraft's positional
sound or volume sliders. Turns shorter than 100 ms are discarded; keep each turn
under 60 seconds.

Live diagnostics appear at the top left during conversations; **F8** toggles them.
The microphone meter should move and sent bytes should increase while B is held.
Release B to request a reply; VAD counters remain zero in push-to-talk mode.
First-audio latency measures release to audio forwarded by the bridge. Received
bytes and queued milliseconds show playback progress. Errors and ended-session
diagnostics remain for 15 seconds. The mod dismisses Minecraft's first-time
movement tutorial on entering a world.

Ask “What can I buy?” or “Show me the bread trade.” The model can open the native
trading screen; you choose and complete the trade there. It cannot change prices
or spend items by voice. Start within 8 blocks; conversations stay active within 12 blocks. Opening a
trade still requires being within 8 blocks. Sessions have a ten-minute limit,
with an explicit message when they expire.

Validation: `npm --prefix backend test` tests against a local fake Higgs endpoint;
`./scripts/build-mod.sh` compiles both mod entrypoints. These checks do not prove
real API access, microphone compatibility, or a successful in-game conversation.

## Memory and nearby gossip

The mod also saves conversation summaries per villager and spreads public stories between nearby, awake adult villagers. Green particles mark a newly saved transfer. To try recall across visits or configure gossip distance and timing, follow the [memory and rumours guide](memory-rumours.md). Update both mod installations and restart the backend for the new voice tools.

## Run locally

These launch commands use the already-provisioned local installation. A fresh
clone does not include Java, server JARs, mods, worlds, or EULA acceptance.
For a fresh setup, install an Apple Silicon Java 25 runtime at
`server/runtime/Contents/Home`, download the mods listed in
`server-cardboard/components.json` into `server-cardboard/mods`, and download
the [pinned Fabric launcher](https://meta.fabricmc.net/v2/versions/loader/26.1.2/0.20.1/1.1.2/server/jar)
as `server-cardboard/fabric-server-launch.jar`. Copy
`server-cardboard/server.properties.example` to `server-cardboard/server.properties`.
Read and accept the [Minecraft EULA](https://aka.ms/MinecraftEULA) before setting
`eula=true` in `server-cardboard/eula.txt`. With the whitelist enabled, add your
account using `whitelist add YOUR_USERNAME` in the server console.

Double-click `server-cardboard/start.command`, or run:

```sh
./server-cardboard/start.command
```

Use Minecraft **Java Edition 26.1.2**, then select Multiplayer → Direct
Connection and enter `localhost:25565`. Install/select that version in your
Minecraft Launcher if needed. The server listens only on this computer and
requires an authenticated Minecraft account.

Your Minecraft EULA acceptance is recorded in the local server directory.
The launcher uses the shared Apple Silicon Java 25 runtime in `server/runtime`.
The server uses up to 2 GB of heap.

Type `stop` in the server console to save and shut down cleanly. Stop the
server before adding mods or plugins, then restart it:

- Fabric mods: `server-cardboard/mods/`
- Bukkit/Spigot/Paper plugins: `server-cardboard/plugins/`
- Settings: `server-cardboard/server.properties`
- World: `server-cardboard/world/`
- Logs: `server-cardboard/logs/latest.log`

Cardboard does not support every plugin; check compatibility before adding one.

## Original server

The original vanilla 26.3 server and its world remain in `server/`.
To return to it, stop Cardboard and run `./server/start.command`, then connect
using Minecraft 26.3. Both servers use port 25565, so run only one at a time.
Do not open the 26.3 world in the older server. The Cardboard server has its own
fresh world and copies of the original whitelist, operators, and ban lists.

Runtime downloads, worlds, logs, and local configuration are excluded from Git.
