#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/java-runtime.sh"
higgs_select_java "$ROOT" false
cd "$ROOT/server"
if [[ ! -f server.jar ]]; then echo 'Missing server/server.jar; download the vanilla server first.' >&2; exit 1; fi
exec "$JAVA_HOME/bin/java" -Xms1G -Xmx2G -jar server.jar nogui "$@"
