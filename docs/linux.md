# HIGGS HRRM on Linux and CachyOS

From a fresh clone to talking with villagers on a CachyOS or Arch desktop. The
game, server and voice bridge all run on this computer. You need a Minecraft Java
account and a Boson API key. Run every command from the repository root; they
work in bash, zsh and fish.

Every file the setup installs is pinned by SHA-512 in `scripts/server.lock` or
`scripts/client.lock` and checked before use. The server and the client each
refuse to start if a jar changes afterwards.

## Install the tools

```sh
sudo pacman -Syu --needed git curl unzip python openssl jdk25-openjdk nodejs-lts-jod npm alsa-lib prismlauncher
```

Leave out `prismlauncher` if you use the Flatpak build, and `nodejs-lts-jod` if you
already have Node.js 22 or newer.

## 1. Clone and configure

```sh
git clone https://github.com/vchopra5/higgs-and-hrrm.git
cd higgs-and-hrrm
git switch dev
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
cp .env.example .env
sed -i "s/^HIGGS_BRIDGE_TOKEN=.*/HIGGS_BRIDGE_TOKEN=$(openssl rand -hex 32)/" .env
```

Open `.env` and fill in `BOSON_API_KEY=`. Keep `.env` private; never commit it.

## 2. Create the Prism instance

In Prism Launcher, sign in, then:

1. **Add Instance** → Vanilla **26.1.2**.
2. **Edit → Version → Install Fabric** → loader **0.20.1**.
3. Use Java 25: Prism's own runtime, or `/usr/lib/jvm/java-25-openjdk/bin/java`
   for native Prism (the Flatpak cannot see `/usr/lib/jvm`).
4. Close Prism.

Point `INST` at the instance's Minecraft folder (Prism's **Folder** button shows it;
older instances name it `.minecraft`):

```sh
# Native Prism (in fish: set INST ~/.local/share/...)
INST=~/.local/share/PrismLauncher/instances/YOUR_INSTANCE/minecraft
# Flatpak Prism
INST=~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/YOUR_INSTANCE/minecraft
```

## 3. Install everything

```sh
./scripts/setup-linux.sh "$INST/mods"
python3 scripts/install-villager-visuals.py "$INST"
```

The first command installs the server jars, the bridge and the mod, then copies the
mod and Fabric API into the client. The second adds the client-only visuals: EMF,
ETF, Fresh Animations and the generated Higgs reputation overlay. Run it with
Minecraft closed.

## 4. Check the client before each launch

```sh
python3 -I scripts/verify-client.py "$INST"
```

It should print `Verified client mods…`. To run it on every launch, open the
instance's **Edit → Settings → Custom commands**, enable them, and set the
pre-launch command to the absolute path of the checker:

```text
python3 -I /absolute/path/to/higgs-and-hrrm/scripts/verify-client.py
```

For Flatpak Prism, first let it read the repository:

```sh
flatpak override --user --filesystem="$PWD:ro" org.prismlauncher.PrismLauncher
```

## 5. Accept the EULA

Read the [Minecraft EULA](https://aka.ms/MinecraftEULA). If you agree:

```sh
echo eula=true > server-cardboard/eula.txt
```

## 6. Run

In two terminals:

```sh
npm --prefix backend start          # voice bridge
./server-cardboard/start.command    # game server
```

The first time, enter `whitelist add YOUR_MINECRAFT_USERNAME` in the server
console. Then launch the instance in Prism, choose **Multiplayer → Direct
Connection** and enter `localhost:25565`.

Face an awake adult villager within eight blocks and wait for **Ready** and its
greeting. Hold **B**, speak, and release **B** to send. Press **F8** for
diagnostics. Ask what it sells, request an offer, then say yes in a new turn to
buy. See the [full setup guide](setup.md) for controls and limits.

Type `stop` in the server console to shut down. Run only one server at a time:
the original `server/` uses the same port.

## After changing the code

| Changed | Do this |
| --- | --- |
| Mod (`mod/`) | Stop the server and Minecraft, then run `./scripts/setup-linux.sh "$INST/mods"`, or build and copy by hand (below) |
| Bridge (`backend/`) | Restart `npm --prefix backend start` |
| `.env` | Restart the bridge; also restart the server after changing `HIGGS_BRIDGE_TOKEN` or the memory/gossip settings |
| Overlay code in `install-villager-visuals.py` | `python3 scripts/install-villager-visuals.py --overlay-only "$INST"`, then F3+T in game |

Building and copying the mod by hand:

```sh
./scripts/build-mod.sh
cp mod/build/libs/higgs-villagers-0.1.0.jar server-cardboard/mods/
cp mod/build/libs/higgs-villagers-0.1.0.jar "$INST/mods/"
```

Both copies must match your latest build. If one is stale, the server or the
client refuses to start and says which. Replacing the jar while either is running
can crash it.

To add another mod, check it upstream and add its SHA-512 and source to
`scripts/server.lock` or `scripts/client.lock`; unlisted jars are refused.
`npm --prefix backend test` runs the bridge tests without a Boson key.

## Microphone and playback

The mod uses Java Sound for microphone capture and villager speech. Minecraft's
normal sounds can work while this separate audio path fails. Select your input
and output in the desktop sound settings before starting a conversation.

On a PipeWire desktop, check the current devices with:

```sh
wpctl status
```

WirePlumber's [official `wpctl` reference](https://pipewire.pages.freedesktop.org/wireplumber/man/wpctl.html)
describes the device list and default-device controls. If Java cannot find an
ALSA default device on a PipeWire installation, check whether `pipewire-alsa` is
installed (`pacman -Q pipewire-alsa`). Arch describes this package as
[PipeWire's ALSA configuration](https://archlinux.org/packages/extra/x86_64/pipewire-alsa/).
If missing, install it through your package manager and restart the game. Use
your existing desktop audio stack; this setup does not require replacing it or
changing the system sample rate.

The mod tries the system default and other available Java Sound mixers, including
common 48 kHz and 44.1 kHz mono/stereo formats, while keeping network audio at
24 kHz mono PCM16. F8 diagnostics identify the selected device and format.
To list Java Sound mixer names with the installed JDK, run:

```sh
"$JAVA_HOME/bin/jshell" <<'JAVA'
import javax.sound.sampled.AudioSystem;
for (var mixer : AudioSystem.getMixerInfo()) System.out.println(mixer.getName());
/exit
JAVA
```

Run this with the same Java installation selected in Prism; sandboxed launchers
may expose a different device list. If you need a specific device, add these JVM
arguments to the **Prism instance**, substituting exact mixer names:

```text
-Dhiggs.audio.input="EXACT INPUT MIXER NAME"
-Dhiggs.audio.output="EXACT OUTPUT MIXER NAME"
```

These are Minecraft client JVM arguments, not backend variables or server
arguments. An explicit name must match an available mixer; it will not silently
switch to a different one. Reconnect after changing devices, and restart the
client after changing JVM arguments. Audio-open failures also appear in the
Prism instance's Minecraft `logs/latest.log`; inspect that client log rather
than only the server log.

| Symptom | Check |
| --- | --- |
| Server/build cannot find Java | Check `JAVA_HOME`, OS/CPU compatibility and `java -version`; the build also needs `javac`. |
| Cannot join | Check Minecraft 26.1.2, Fabric 0.20.1, both required client mods, server console and whitelist. |
| Voice bridge unavailable | Keep backend running; check the root `.env`, shared bridge token and backend console. |
| No microphone meter movement | Check desktop input/mute, Java Sound device diagnostics and client audio errors. Hold B in the focused game window. |
| Sent bytes grow but no reply | Check backend errors, Boson access and pending tools; release B to send the turn. |
| Received bytes grow but no audible villager | Check desktop output routing and Java playback diagnostics. Minecraft volume sliders do not control this speech path. |

Real CachyOS microphone, playback and in-game behavior still need testing on the
target machine. Automated tests cover format conversion and session behavior;
they cannot establish hardware compatibility or live Boson access.
