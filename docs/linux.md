# HIGGS HRRM on Linux and CachyOS

This guide starts with a fresh clone on a CachyOS or Arch Linux desktop. The game,
server and voice bridge run on the same computer. You need a Minecraft Java
account and a Boson API key. Other Linux distributions can use the same build and
launch commands after installing equivalent tools.

## Install the tools

Install the desktop prerequisites using your package manager:

```sh
sudo pacman -Syu --needed git curl openssl jdk25-openjdk nodejs-lts-jod npm alsa-lib prismlauncher
```

This selects Node.js 22 LTS; an existing Node.js 22 or newer installation is also
suitable. If the package manager offers to replace another Node package, keep
your existing supported installation and omit `nodejs-lts-jod` from the command.
The [Arch Java 25 package](https://archlinux.org/packages/extra/x86_64/jdk25-openjdk/)
provides the JDK; its optional `alsa-lib` sound dependency is included above for Java Sound. The [Node.js 22 LTS package](https://archlinux.org/packages/extra/x86_64/nodejs-lts-jod/) supplies the backend runtime. Prism documents its
[Arch package](https://prismlauncher.org/download/linux/#arch-linux--manjaro).

```sh
git clone https://github.com/vchopra5/higgs-and-hrrm.git
cd higgs-and-hrrm
git switch dev
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
"$JAVA_HOME/bin/java" -version
"$JAVA_HOME/bin/javac" -version
node --version
npm --version
```

Both Java commands should report version 25 or newer; this project targets Java
25 bytecode. `JAVA_HOME` selects Java for the build and server in the current
terminal. Configure the client separately in Prism. Do not copy a macOS runtime
onto Linux: use a runtime built for your OS and CPU. If you use a different JDK,
point `JAVA_HOME` at its directory containing `bin/java` and `bin/javac`.

The repository includes the Gradle wrapper, pinned to **9.8.2** with a SHA-256
checksum. You do not need to install system Gradle. The first build needs internet
access to download Gradle, Minecraft and Fabric dependencies. `--offline` works
only after those files have been cached.

## Configure and build

```sh
cp .env.example .env
```

Edit `.env`: fill in `BOSON_API_KEY` and set `HIGGS_BRIDGE_TOKEN` to a random
secret of at least 32 characters. Generate a token with:

```sh
openssl rand -hex 32
```

Keep both values private. The backend and game server read this same root `.env`
when launched using the repository commands; environment variables take priority.
Keep the default loopback addresses for this local setup.

## Install mods, build the bridge and the mod

Open the Prism instance's Minecraft folder (see [client setup](#set-up-the-minecraft-client))
and pass its `mods/` directory to the setup script:

```sh
./scripts/setup-linux.sh ~/.local/share/PrismLauncher/instances/YOUR_INSTANCE/.minecraft/mods
```

The script:

1. Downloads the Fabric server launcher, Cardboard, Fabric API and iCommon into
   `server-cardboard/`, rejecting any file whose SHA-512 does not match the pin in
   the script. Already verified files are not downloaded again.
2. Builds the bridge with `npm ci`, which verifies `package-lock.json` integrity hashes.
3. Checks the Gradle wrapper JAR's SHA-256 and builds the mod. Gradle verifies its
   distribution and every dependency against `mod/gradle/verification-metadata.xml`.
4. Copies `higgs-villagers-0.1.0.jar` into `server-cardboard/mods/`, and the mod
   plus Fabric API into the client `mods/` directory. Cardboard and iCommon stay
   on the server.

Omit the argument to set up only the server. Re-run the script after changing the
mod, with the server and client stopped. It also creates `server.properties` from
the example if missing. `npm --prefix backend test` runs bridge tests without a
Boson key; neither the build nor the tests validate real audio hardware.

## Prepare the server

Server binaries, libraries, worlds and EULA acceptance are not included in Git.
Read the [Minecraft EULA](https://aka.ms/MinecraftEULA). If you agree, create
`server-cardboard/eula.txt` containing `eula=true`. Then start the server:

```sh
./server-cardboard/start.command
```

The `.command` filename also runs on Linux; it is a Bash script. The first launch
downloads additional server libraries and creates a new world. Wait for the
server to finish starting, then enter this in its console:

```text
whitelist add YOUR_MINECRAFT_USERNAME
```

The example configuration enables authentication and a whitelist, and listens
on `127.0.0.1:25565`. Leave the server running. Use `stop` in its console to save
and shut down before replacing JARs. Do not run the original `server/` alongside
it, because both use the same port.

## Set up the Minecraft client

1. Open Prism Launcher, sign in with your Minecraft account and add an instance
   for **Minecraft 26.1.2**.
2. Edit the instance, select **Version → Install Fabric**, and select loader
   **0.20.1**. Minecraft and loader versions must match the server manifest.
3. In the instance's **Settings → Java**, enable the Java installation override
   if needed, and select `/usr/lib/jvm/java-25-openjdk/bin/java`. Use Prism's Java
   test to check it. Native Linux packages may require manual Java selection;
   see [Prism's Java setup](https://prismlauncher.org/wiki/getting-started/installing-java/).
4. Open the instance's Minecraft folder through Prism and note its `mods/` path.
   `./scripts/setup-linux.sh` copies `higgs-villagers-0.1.0.jar` and the verified
   Fabric API there. Cardboard and iCommon belong on the server only.
5. Launch the instance. Select **Multiplayer → Direct Connection** and enter
   `localhost:25565`.

Prism creates and manages the instance; the setup script only writes to the
`mods/` directory you pass it. For a Flatpak installation,
select a Java runtime available inside its sandbox and check its audio
permissions. Native Prism avoids that additional sandbox layer during diagnosis.

## Start the voice conversation

In another terminal at the repository root:

```sh
npm --prefix backend start
```

Face an awake adult villager within eight blocks. Wait for **Ready** and its
greeting. Hold **B**, speak, then release **B** to send the turn. Press **F8** for
diagnostics. Look at another villager and press B to switch; walking away ends
the connection. Ask what it sells, request an available offer, then say yes in
a new turn to buy. See the [full setup guide](setup.md) for controls and limits.

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
