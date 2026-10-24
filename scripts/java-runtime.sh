#!/bin/bash
# Shared by the build and server launchers; do not change the caller's directory.
higgs_try_java_home() {
  local candidate="$1" need_jdk="$2" settings major
  [[ -x "$candidate/bin/java" ]] || return 1
  if [[ "$need_jdk" == true && ! -x "$candidate/bin/javac" ]]; then return 1; fi
  # Running the binary also rejects a bundled runtime for the wrong OS/CPU.
  settings=$("$candidate/bin/java" -XshowSettings:properties -version 2>&1) || return 1
  major=$(printf '%s\n' "$settings" | sed -n 's/^[[:space:]]*java.specification.version = \([0-9][0-9]*\).*$/\1/p')
  [[ "$major" =~ ^[0-9]+$ ]] && (( major >= 25 )) || return 1
  export JAVA_HOME="$candidate"
  export PATH="$JAVA_HOME/bin:$PATH"
}

higgs_select_java() {
  local root="$1" need_jdk="$2" candidate java_on_path settings detected_home requirement="Java 25 or newer"
  if [[ "$need_jdk" == true ]]; then requirement="JDK 25 or newer (including javac)"; fi
  if [[ -n "${JAVA_HOME:-}" ]]; then
    if higgs_try_java_home "$JAVA_HOME" "$need_jdk"; then return 0; fi
    printf 'JAVA_HOME does not contain a working %s: %s\nSet JAVA_HOME to the correct installation, or unset it to use automatic discovery.\n' "$requirement" "$JAVA_HOME" >&2
    return 1
  fi
  for candidate in "$root/server/runtime" "$root/server/runtime/Contents/Home" \
      "$root"/.tools/jdk-* "$root"/.tools/jdk-*/Contents/Home; do
    if higgs_try_java_home "$candidate" "$need_jdk"; then return 0; fi
  done
  java_on_path=$(command -v java || true)
  if [[ -n "$java_on_path" ]]; then
    settings=$("$java_on_path" -XshowSettings:properties -version 2>&1) || settings=""
    detected_home=$(printf '%s\n' "$settings" | sed -n 's/^[[:space:]]*java.home = //p')
    if [[ -n "$detected_home" ]] && higgs_try_java_home "$detected_home" "$need_jdk"; then return 0; fi
  fi
  printf 'No working %s found. Install one for your OS/CPU and set JAVA_HOME, or put its bin directory on PATH.\n' "$requirement" >&2
  return 1
}
