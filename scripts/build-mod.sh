#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/java-runtime.sh"
higgs_select_java "$ROOT" true
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.gradle}"
# Loom downloads these Mojang jars outside Gradle's dependency verification and
# splits them into the minecraft-common/clientOnly jars that verification trusts.
# SHA-1s from Mojang's 26.1.2 version manifest.
LOOM_CACHE="$GRADLE_USER_HOME/caches/fabric-loom/26.1.2"
MOJANG_SHA1=(
  "4e618f09a0c649dde3fdf829df443ce0b8831e65  $LOOM_CACHE/minecraft-client.jar"
  "97ccd4c0ed3f81bbb7bfacddd1090b0c56f9bc51  $LOOM_CACHE/minecraft-server.jar"
)
cd "$ROOT/mod"
gradle_cmd=()
if [[ -x ./gradlew ]]; then gradle_cmd=(./gradlew); fi
for candidate in "$ROOT"/.tools/gradle-*/bin/gradle; do
  if [[ ${#gradle_cmd[@]} -eq 0 && -x "$candidate" ]]; then gradle_cmd=("$candidate"); fi
done
if [[ ${#gradle_cmd[@]} -eq 0 ]] && command -v gradle >/dev/null 2>&1; then gradle_cmd=(gradle); fi
if [[ ${#gradle_cmd[@]} -eq 0 ]]; then
  echo 'Install a compatible Gradle distribution or generate/use the Gradle wrapper to build this mod.' >&2
  exit 1
fi
"${gradle_cmd[@]}" build "$@"
if command -v sha1sum >/dev/null; then sha1() { sha1sum "$@"; }; else sha1() { shasum -a 1 "$@"; }; fi
printf '%s\n' "${MOJANG_SHA1[@]}" | sha1 -c - \
  || { echo "Loom's Minecraft jars differ from Mojang's; delete $LOOM_CACHE and rebuild." >&2; exit 1; }
