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

# filename  sha512  url
SERVER_FILES=(
  "fabric-server-launch.jar
fcf5e27755afe8e6c0d61833d57c08971e906dfdcad0d9f27bd3cf7dee944ef359413c819a0dec86d245425ed66192d9bbdf90924d9f9e3ffc66ea2e9c947d58
https://meta.fabricmc.net/v2/versions/loader/26.1.2/0.20.1/1.1.2/server/jar"
  "mods/Cardboard-26.1.2.jar
3976d55be72044417703143665972392d0c342d8992a218d4319d97491f35d665df13ef6907aed4a7ffbbe41ddd1d0e911962dc35efff55590f4e26237f9c35d
https://cdn.modrinth.com/data/MLYQ9VGP/versions/bdR8CLgE/Cardboard-26.1.2.jar"
  "mods/$FABRIC_API
fa0308b42e9cd0dc3ff946f246d199d74547e0c946d7e3c9780a2537155307baf25129f69bd1349273babf879ffb4608b7e4af0740938f9c0f7b371b1c67d6ca
https://cdn.modrinth.com/data/P7dR8mSH/versions/3dM0X6ou/fabric-api-0.155.3%2B26.1.2.jar"
  "mods/iCommon-Fabric-bundle.jar
56db73de5a9ebc74135f32b33f8a65d6a34014f708c7f28184abea7e916b93edca7e1bc2fab333ade8b3325c4bcd5657b6a47b62ea37e9b6ceb8b593898001f6
https://cdn.modrinth.com/data/SVKv1SZo/versions/jhgdmI2N/iCommon-Fabric-bundle.jar"
)
GRADLE_WRAPPER_SHA256=3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98

die() { echo "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null || die "Missing $1: install it with your package manager."; }

# Download into place only if the SHA-512 matches; reuse a verified existing file.
fetch() {
  local dest="$1" sha="$2" url="$3" tmp
  if [[ -f "$dest" ]] && sha512sum --check --status <<<"$sha  $dest"; then
    echo "ok       ${dest#"$ROOT/"}"; return
  fi
  mkdir -p "$(dirname "$dest")"
  tmp="$(mktemp "$dest.XXXXXX")"
  curl --fail --silent --show-error --location --output "$tmp" "$url"
  if ! sha512sum --check --status <<<"$sha  $tmp"; then
    rm -f "$tmp"; die "Checksum mismatch for $url"
  fi
  mv "$tmp" "$dest"
  echo "fetched  ${dest#"$ROOT/"}"
}

for tool in curl sha512sum sha256sum npm node; do need "$tool"; done
source "$ROOT/scripts/java-runtime.sh"
higgs_select_java "$ROOT" true
(( $(node -p 'process.versions.node.split(".")[0]') >= 22 )) || die 'Node.js 22+ is required.'

echo '== Server mods'
for entry in "${SERVER_FILES[@]}"; do
  { read -r name; read -r sha; read -r url; } <<<"$entry"
  fetch "$SERVER/$name" "$sha" "$url"
done
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
