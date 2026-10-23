#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
JAVA="$PWD/runtime/Contents/Home/bin/java"
if [[ ! -x "$JAVA" ]]; then
  echo "Project-local Java runtime is missing: $JAVA" >&2
  exit 1
fi
exec "$JAVA" -Xms1G -Xmx2G -jar server.jar nogui
