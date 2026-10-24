<p><a href="../README.md"><img src="assets/logo.png" alt="HIGGS HRRM" width="64" /></a></p>

# HIGGS HRRM · Setup

[← HIGGS HRRM](../README.md) · [Visual identity](brand.md)

Local Minecraft Java Edition **26.1.2** server with **Fabric 0.20.1** and
**Cardboard 26.1-15** for the villager conversation project.
Fabric API and iCommon are installed. The Higgs integration prototype lives in
`mod/` and `backend/`; live Boson and microphone testing is still required.
Every server jar is pinned with its SHA-512 and download source in the lockfile
[`scripts/server.lock`](../scripts/server.lock).

## Linux setup

Install JDK 25, Node.js 22+, and curl with your package manager, then run:

```sh
./scripts/setup-linux.sh ~/.local/share/PrismLauncher/instances/YOUR_INSTANCE/.minecraft/mods
```

The argument is your Fabric 26.1.2 client's `mods` directory; omit it to set up
only the server. The script:

1. Puts every jar in `scripts/server.lock` into `server-cardboard/`, refusing any
   file whose SHA-512 does not match the lock, so the first start downloads nothing.
2. Builds the bridge with `npm ci`, which verifies the lockfile integrity hashes.
3. Builds the mod with Gradle. The wrapper JAR, the Gradle distribution, and every
   dependency (`mod/gradle/verification-metadata.xml`) are checksum-verified.
4. Copies the mod JAR into `server-cardboard/mods/`, and the mod JAR plus Fabric
   API into the client `mods` directory.

Re-run it after changing the mod; verified files are not downloaded again. Then
fill in `.env` (step 1 below), accept the EULA (see [Run locally](#run-locally)),
and start the bridge and server. For packages, Prism and audio troubleshooting,
see **[Linux and CachyOS: from clone to conversation](linux.md)**.

## Required mods and checksums

Use Minecraft **26.1.2**, Fabric Loader **0.20.1**, and Java **25**. Server download
URLs and SHA-512 pins live in [server.lock](../scripts/server.lock); client
download URLs and pins live in [client.lock](../scripts/client.lock).

| Component | Version | Install location | Checksum source |
| --- | --- | --- | --- |
| Cardboard | 26.1-15 | Server `mods/` for this Cardboard setup | `server.lock` |
| iCommon Fabric bundle | 109 | Server `mods/`; Cardboard dependency | `server.lock` |
| Fabric API | 0.155.3+26.1.2 | Server and client `mods/` | `server.lock`, `client.lock` |
| Higgs Villagers | 0.1.0, built from this repository | Server and client `mods/` | Compared with your local build before each start |
| Entity Model Features (EMF) | 3.3.12, 26.1 Fabric | Client `mods/`; required for animated faces | `client.lock` |
| Entity Texture Features (ETF) | 7.2.6, 26.1 Fabric | Client `mods/`; required for animated faces | `client.lock` |
| Fresh Animations | 1.11.1, 26.1+ | Client `resourcepacks/`; required for animated faces | `client.lock` |
| Higgs reputation overlay | Generated locally | Client `resourcepacks/`, above Fresh Animations | Compared with a fresh regeneration |

EMF, ETF, and the resource packs are **client-only**: do not put them on the
dedicated server. They enable the facial expressions; basic voice and trading
require Higgs Villagers and Fabric API on the client. The Linux setup script
installs the server dependencies and, when given a client directory, Higgs Villagers
and Fabric API. Run the separate visual installer below for EMF, ETF and the packs.

Build Higgs Villagers with `./scripts/build-mod.sh` and install the same built JAR
on both sides. There is no published prebuilt mod release; a checksum from one
local build is not a universal checksum for other revisions or rebuilds, so the
server and client checks compare each installed copy with `mod/build/libs/`.

For the visual dependencies, close Minecraft and run:

```sh
python3 scripts/install-villager-visuals.py "/absolute/path/to/your/instance/.minecraft"
```

The installer downloads the pinned assets, verifies their SHA-512 checksums,
generates the Higgs overlay, and enables both resource packs. The generated
overlay has no fixed download checksum; the source assets are checked before
generation. See [villager visuals](villager-visuals.md) for credits and setup.

### Check the client before each launch

`scripts/verify-client.py` refuses to launch when a file in `client.lock` differs,
an unlisted jar is in `mods/`, Higgs Villagers differs from your build, or the
overlay differs from a fresh regeneration. In Prism, open the instance's
**Settings → Custom commands**, enable them, and set the pre-launch command
(Prism provides `$INST_MC_DIR`, and a failing pre-launch command stops the launch):

```sh
python3 -I /absolute/path/to/higgs-and-hrrm/scripts/verify-client.py
```

The Flatpak build of Prism cannot see the repository by default; grant it read-only access:

```sh
flatpak override --user --filesystem="$PWD:ro" org.prismlauncher.PrismLauncher
```

Run this from the repository root. After adding a mod, verify it upstream and add
it to `scripts/client.lock`, or the launch is refused.

## Villager voice prototype

See the [build architecture and diagram](architecture.md). The first pass
implements Fabric client/server code, hands-free realtime audio, a Higgs backend,
live trade lookup, and audio-only purchases with one spoken confirmation.

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
5. Start the server and join. Look at an adult villager to connect automatically.
   Wait for **Ready** and let the villager greet you, then **hold B to speak and release B to send**.
   Look at another villager and press B to switch. Walking away ends the session.
   Key bindings can be changed in Minecraft controls.

A neighbour with a fresh relevant rumour may bring it up in the greeting.

On macOS, allow microphone access for the Minecraft/Java process when asked; use Prism Launcher if the official launcher cannot request access. On Linux, select the microphone and speakers in the desktop sound settings; see [Linux audio troubleshooting](linux.md#microphone-and-playback).

The microphone sends audio only while B is held in the focused game window.
Releasing B, opening a menu, or switching away ends the turn. Pressing B during a
reply interrupts it. The top-right HUD shows microphone strength while you speak.
Speech comes from the villager’s position using Minecraft’s native distance falloff
(16-block range) and directional audio. Master and Friendly Creatures sliders control
its volume; choose output using Minecraft’s Sound Device setting. Turns shorter than 100 ms are discarded; keep each turn
under 60 seconds.

Detailed diagnostics are hidden by default; **F8** toggles them.
The microphone meter should move and sent bytes should increase while B is held.
Release B to request a reply; VAD counters remain zero in push-to-talk mode.
First-audio latency measures release to audio forwarded by the bridge. Received
bytes and queued milliseconds show playback progress. Errors and ended-session
diagnostics remain for 15 seconds. The mod dismisses Minecraft's first-time
movement tutorial on entering a world.

Ask “What can I buy?” or “I would like some bread.” The villager reads the exact
quote. Say yes in a new push-to-talk turn to complete one native trade. Saying no
cancels. Prices, stock, inventory capacity, and proximity are checked before purchase.
Quotes expire after two minutes and cannot be purchased twice. Start within eight
blocks and remain within twelve; purchasing requires eight. Sessions last ten minutes.
Each finished turn appears in Minecraft chat as `<YourName>` or `<VillagerName>`, stacking
like normal chat; press T to scroll back.
User transcripts arrive after speech processing; villager lines post when the reply finishes (or is interrupted).
These lines are local to your client and never sent to the server, but Minecraft
writes chat to the client `logs/latest.log`.

Validation: `npm --prefix backend test` tests against a local fake Higgs endpoint;
`./scripts/build-mod.sh` compiles both mod entrypoints. These checks do not prove
real API access, microphone compatibility, or a successful in-game conversation.

## Memory and nearby gossip

The mod also saves conversation summaries per villager and spreads public stories between nearby, awake adult villagers. Green particles mark a newly saved transfer. To try recall across visits or configure gossip distance and timing, follow the [memory and rumours guide](memory-rumours.md). Update both mod installations and restart the backend for the new voice tools.

## Run locally

These launch commands use the already-provisioned local installation. A fresh
clone does not include Java, server JARs, mods, worlds, or EULA acceptance.
On Linux, use [Linux setup](#linux-setup). Elsewhere, install JDK 25 for your
operating system and CPU and download each source in `scripts/server.lock`
into its path under `server-cardboard/` (extract `bundled:` entries from the
vanilla server jar). Then build the Fabric launch jar with
`jar --create --no-manifest --file server-cardboard/.fabric/server/fabric-loader-server-0.20.1-minecraft-26.1.2.jar -C scripts/fabric-launch .`. Copy
`server-cardboard/server.properties.example` to `server-cardboard/server.properties`.
Read and accept the [Minecraft EULA](https://aka.ms/MinecraftEULA) before setting
`eula=true` in `server-cardboard/eula.txt`. With the whitelist enabled, add your
account using `whitelist add YOUR_USERNAME` in the server console.

On Linux or macOS, run the following from the repository root. On macOS you can also double-click `server-cardboard/start.command`:

```sh
./server-cardboard/start.command
```

Use Minecraft **Java Edition 26.1.2**, then select Multiplayer → Direct
Connection and enter `localhost:25565`. Install/select that version in your
Minecraft Launcher if needed. The server listens only on this computer and
requires an authenticated Minecraft account.

Your Minecraft EULA acceptance is recorded in the local server directory.
The launchers use `JAVA_HOME` when set, otherwise a compatible local runtime or Java on `PATH`. Linux runtimes use `bin/java`; macOS app bundles use `Contents/Home/bin/java`. An explicit `JAVA_HOME` must point to the directory containing `bin/java`, and builds also need `bin/javac`. See the [Linux runtime instructions](linux.md#install-the-tools). The server uses up to 2 GB of heap.

`start.command` refuses to start if any server jar is missing, differs from
`scripts/server.lock`, or is not listed there, and if the installed Higgs
Villagers JAR differs from your latest build.

Type `stop` in the server console to save and shut down cleanly. Stop the
server before adding mods or plugins, then restart it. Add each new jar to
`scripts/server.lock` with its SHA-512 and source after checking it upstream,
or the server will not start:

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
