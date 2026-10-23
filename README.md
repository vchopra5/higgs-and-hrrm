# Higgs and Hrrm

Local Minecraft Java Edition **26.1.2** server with **Fabric 0.20.1** and
**Cardboard 26.1-15** for the villager conversation project.
Fabric API and iCommon are installed. Higgs integration is not installed yet.
Exact mod versions, download URLs, and checksums are in
`server-cardboard/components.json`.

## Planned villager mod

See the [build architecture and diagram](docs/architecture.md) for the custom
Fabric client/server mod and Higgs backend we need to implement. It separates
our code from existing Minecraft capabilities and describes the first voice
conversation and trading flow. The mod is planned, not installed yet.

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
