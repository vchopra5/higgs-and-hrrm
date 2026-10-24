#!/usr/bin/env bash
# Linux setup: checksummed server downloads, bridge install, mod build, and
# copying the built mod into the server and client.
# Usage: ./scripts/setup-linux.sh [CLIENT_MODS_DIR]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER="$ROOT/server-cardboard"
CLIENT_MODS="${1:-${HIGGS_CLIENT_MODS:-}}"
MOD_JAR="$ROOT/mod/build/libs/higgs-villagers-0.1.0.jar"

FABRIC_API=fabric-api-0.155.3+26.1.2.jar

# Every server jar with its SHA-512 and source; see the header of that file.
LOCK="$ROOT/scripts/server.lock"
# The lock's "bundled:" entries are extracted from this verified vanilla server jar.
VANILLA="$SERVER/.fabric/server/26.1.2-server.jar"
LAUNCH_JAR=.fabric/server/fabric-loader-server-0.20.1-minecraft-26.1.2.jar
GRADLE_WRAPPER_SHA256=3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98

die() { echo "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null || die "Missing $1: install it with your package manager."; }

# Put a locked file into place only if its SHA-512 matches; reuse a verified existing file.
fetch() {
  local dest="$1" sha="$2" src="$3" tmp
  if [[ -f "$dest" ]] && sha512sum --check --status <<<"$sha  $dest"; then return; fi
  mkdir -p "$(dirname "$dest")"
  tmp="$(mktemp "$dest.XXXXXX")"
  if [[ "$src" == bundled:* ]]; then
    unzip -p "$VANILLA" "${src#bundled:}" >"$tmp"
  else
    curl --fail --silent --show-error --location --output "$tmp" "$src"
  fi
  if ! sha512sum --check --status <<<"$sha  $tmp"; then
    rm -f "$tmp"; die "Checksum mismatch for $src"
  fi
  mv "$tmp" "$dest"
  echo "fetched  ${dest#"$ROOT/"}"
}

for tool in curl unzip sha512sum sha256sum npm node; do need "$tool"; done
source "$ROOT/scripts/java-runtime.sh"
higgs_select_java "$ROOT" true
(( $(node -p 'process.versions.node.split(".")[0]') >= 22 )) || die 'Node.js 22+ is required.'

echo '== Server files'
while IFS=$'\t' read -r path sha src; do
  [[ -z "$path" || "$path" == \#* ]] && continue
  fetch "$SERVER/$path" "$sha" "$src"
done <"$LOCK"
echo "verified $(grep -vc '^#' "$LOCK") files from scripts/server.lock"
# Without this jar the Fabric launcher fetches its profile from meta.fabricmc.net on
# first start; it only holds the classpath manifest kept in scripts/fabric-launch/.
"$JAVA_HOME/bin/jar" --create --no-manifest --file "$SERVER/$LAUNCH_JAR" -C "$ROOT/scripts/fabric-launch" .
[[ -f "$SERVER/server.properties" ]] || cp "$SERVER/server.properties.example" "$SERVER/server.properties"

echo '== Bridge'
npm --prefix "$ROOT/backend" ci   # package-lock.json integrity hashes are verified by npm

echo '== Mod'
# The wrapper checks the Gradle distribution (distributionSha256Sum) and
# dependencies (gradle/verification-metadata.xml); check the wrapper itself here.
sha256sum --check --status <<<"$GRADLE_WRAPPER_SHA256  $ROOT/mod/gradle/wrapper/gradle-wrapper.jar" \
  || die 'gradle-wrapper.jar checksum mismatch'
"$ROOT/scripts/build-mod.sh"
cp "$MOD_JAR" "$SERVER/mods/"
echo "copied   $(basename "$MOD_JAR") -> server-cardboard/mods/"

if [[ -n "$CLIENT_MODS" ]]; then
  mkdir -p "$CLIENT_MODS"
  cp "$MOD_JAR" "$SERVER/mods/$FABRIC_API" "$CLIENT_MODS/"
  echo "copied   $(basename "$MOD_JAR") and $FABRIC_API -> $CLIENT_MODS"
else
  echo 'No client mods directory given; pass it as the first argument to install the client mod.'
fi

grep -qx 'eula=true' "$SERVER/eula.txt" 2>/dev/null \
  || echo 'Read https://aka.ms/MinecraftEULA, then put eula=true in server-cardboard/eula.txt.'
[[ -f "$ROOT/.env" ]] || echo 'Copy .env.example to .env and fill in BOSON_API_KEY and HIGGS_BRIDGE_TOKEN.'
echo 'Done. Start the bridge with: npm --prefix backend start'
echo 'Start the server with:     ./server-cardboard/start.command'
