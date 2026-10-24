#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/java-runtime.sh"
higgs_select_java "$ROOT" true
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.gradle}"
cd "$ROOT/mod"
if [[ -x ./gradlew ]]; then
  exec ./gradlew build "$@"
fi
for candidate in "$ROOT"/.tools/gradle-*/bin/gradle; do
  if [[ -x "$candidate" ]]; then exec "$candidate" build "$@"; fi
done
if command -v gradle >/dev/null 2>&1; then exec gradle build "$@"; fi
echo 'Install a compatible Gradle distribution or generate/use the Gradle wrapper to build this mod.' >&2
exit 1
