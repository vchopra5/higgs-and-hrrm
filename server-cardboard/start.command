#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/java-runtime.sh"
higgs_select_java "$ROOT" false
cd "$ROOT/server-cardboard"
if [[ ! -f fabric-server-launch.jar ]]; then echo 'Missing server-cardboard/fabric-server-launch.jar; download the Fabric server launcher first.' >&2; exit 1; fi
exec "$JAVA_HOME/bin/java" -Xms1G -Xmx2G -jar fabric-server-launch.jar nogui "$@"
