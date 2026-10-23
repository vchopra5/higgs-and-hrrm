#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in "$ROOT"/.tools/jdk-*/Contents/Home; do
    if [[ -x "$candidate/bin/javac" ]]; then export JAVA_HOME="$candidate"; break; fi
  done
fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.gradle}"
cd "$ROOT/mod"
if [[ -x ./gradlew ]]; then
  exec ./gradlew build "$@"
fi
for candidate in "$ROOT"/.tools/gradle-*/bin/gradle; do
  if [[ -x "$candidate" ]]; then exec "$candidate" build "$@"; fi
done
echo 'Install JDK 25 and generate/use the Gradle wrapper to build this mod.' >&2
exit 1
