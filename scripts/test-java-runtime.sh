#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/java-runtime.sh"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/higgs-java-test.XXXXXX")
TEST_ROOT=$(cd "$TEST_ROOT" && pwd)
trap 'rm -rf "$TEST_ROOT"' EXIT

make_java() {
  local home="$1" version="$2" compiler="$3"
  mkdir -p "$home/bin"
  cat > "$home/bin/java" <<'JAVA'
#!/bin/bash
if [[ "${1:-}" == -XshowSettings:properties ]]; then
  home="$(cd "$(dirname "$0")/.." && pwd)"
  printf '    java.home = %s\n    java.specification.version = %s\n' "$home" "$(cat "$home/version")" >&2
else
  printf '%s\n' "$PWD" "$@" > "$HIGGS_TEST_LAUNCH_LOG"
fi
JAVA
  printf '%s\n' "$version" > "$home/version"
  chmod +x "$home/bin/java"
  if [[ "$compiler" == true ]]; then cp "$home/bin/java" "$home/bin/javac"; fi
}

# An explicitly selected distro installation wins, including paths with spaces.
make_java "$TEST_ROOT/system jdk" 25 true
(
  export JAVA_HOME="$TEST_ROOT/system jdk"
  higgs_select_java "$TEST_ROOT/empty" true
  [[ "$JAVA_HOME" == "$TEST_ROOT/system jdk" ]]
)
# Old or incomplete explicit JAVA_HOME fails with an actionable diagnostic.
make_java "$TEST_ROOT/old" 21 true
if (export JAVA_HOME="$TEST_ROOT/old"; higgs_select_java "$TEST_ROOT" true) 2> "$TEST_ROOT/error"; then exit 1; fi
[[ $(cat "$TEST_ROOT/error") == *JAVA_HOME* ]]
make_java "$TEST_ROOT/jre" 25 false
if (export JAVA_HOME="$TEST_ROOT/jre"; higgs_select_java "$TEST_ROOT" true) 2> "$TEST_ROOT/error"; then exit 1; fi
(export JAVA_HOME="$TEST_ROOT/jre"; higgs_select_java "$TEST_ROOT" false)

# Linux local layout and the existing macOS bundle layout both work.
make_java "$TEST_ROOT/linux/.tools/jdk-25" 25 true
(unset JAVA_HOME; higgs_select_java "$TEST_ROOT/linux" true; [[ "$JAVA_HOME" == "$TEST_ROOT/linux/.tools/jdk-25" ]])
make_java "$TEST_ROOT/mac/server/runtime/Contents/Home" 25 true
(unset JAVA_HOME; higgs_select_java "$TEST_ROOT/mac" false; [[ "$JAVA_HOME" == "$TEST_ROOT/mac/server/runtime/Contents/Home" ]])
# An unusable downloaded executable is skipped rather than blocking system Java.
mkdir -p "$TEST_ROOT/broken/server/runtime/bin"
printf '#!/bin/bash\nexit 126\n' > "$TEST_ROOT/broken/server/runtime/bin/java"
chmod +x "$TEST_ROOT/broken/server/runtime/bin/java"
(
  unset JAVA_HOME
  export PATH="$TEST_ROOT/system jdk/bin:$PATH"
  higgs_select_java "$TEST_ROOT/broken" false
  [[ "$JAVA_HOME" == "$TEST_ROOT/system jdk" ]]
)

# Exercise the actual launch/build entrypoints without Gradle, Java or a server.
mkdir -p "$TEST_ROOT/repo/scripts" "$TEST_ROOT/repo/server" "$TEST_ROOT/repo/server-cardboard" "$TEST_ROOT/repo/mod" "$TEST_ROOT/bin"
cp "$ROOT/scripts/java-runtime.sh" "$ROOT/scripts/build-mod.sh" "$TEST_ROOT/repo/scripts/"
cp "$ROOT/server/start.command" "$TEST_ROOT/repo/server/"
cp "$ROOT/server-cardboard/start.command" "$TEST_ROOT/repo/server-cardboard/"
cat > "$TEST_ROOT/bin/gradle" <<'GRADLE'
#!/bin/bash
printf '%s\n' "$PWD" "$@" > "$HIGGS_TEST_LAUNCH_LOG"
GRADLE
chmod +x "$TEST_ROOT/bin/gradle"
export HIGGS_TEST_LAUNCH_LOG="$TEST_ROOT/launch.log"
(
  export JAVA_HOME="$TEST_ROOT/system jdk" PATH="$TEST_ROOT/bin:$PATH"
  bash "$TEST_ROOT/repo/scripts/build-mod.sh" --offline
)
[[ $(cat "$HIGGS_TEST_LAUNCH_LOG") == "$TEST_ROOT/repo/mod"$'\nbuild\n--offline' ]]
for server in server server-cardboard; do
  if (export JAVA_HOME="$TEST_ROOT/system jdk"; bash "$TEST_ROOT/repo/$server/start.command") 2> "$TEST_ROOT/error"; then exit 1; fi
  [[ $(cat "$TEST_ROOT/error") == *Missing* ]]
  jar=server.jar
  if [[ "$server" == server-cardboard ]]; then jar=fabric-server-launch.jar; fi
  touch "$TEST_ROOT/repo/$server/$jar"
  (export JAVA_HOME="$TEST_ROOT/system jdk"; bash "$TEST_ROOT/repo/$server/start.command" --port 25566)
  [[ $(cat "$HIGGS_TEST_LAUNCH_LOG") == "$TEST_ROOT/repo/$server"$'\n-Xms1G\n-Xmx2G\n-jar\n'"$jar"$'\nnogui\n--port\n25566' ]]
done
printf 'Java runtime and launcher portability checks passed.\n'
