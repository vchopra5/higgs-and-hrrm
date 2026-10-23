#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
JAVA="$PWD/../server/runtime/Contents/Home/bin/java"
if [[ ! -x "$JAVA" ]]; then
  echo "Missing shared Java runtime: $JAVA" >&2
  exit 1
fi
exec "$JAVA" -Xms1G -Xmx2G -jar fabric-server-launch.jar nogui
