#!/usr/bin/env bash
# Starts VectoraCloud on Linux and macOS.
#
#   ./start.sh [launcher arguments]      for example: ./start.sh --non-interactive --setup-file setup.yml
#
# Environment:
#   VECTORA_HOME    cloud home and working directory (default: the folder of this script)
#   VECTORA_JAR     path of VectoraCloud.jar (default: next to this script)
#   VECTORA_XMX     maximum heap of the cloud itself, e.g. 512M or 1G (default: 512M)
#   JAVA_HOME       Java installation to use; otherwise "java" from the PATH
#   JDK_JAVA_OPTIONS further JVM options, read by the java launcher itself (Java 9 and later)
#
# Every argument is passed to VectoraCloud unchanged (--allow-root, --setup-file, --lang, ...).
# The Java version (21 or later) is checked by VectoraCloud.jar itself, so an older Java prints a
# readable message instead of a class file error. Exit codes: see docs/cloud/operations.md.
#
# Compatible with bash 3.2 (macOS). Never call this script with sudo: VectoraCloud refuses to run
# as root unless --allow-root is given, and a dedicated user is the safer choice.

set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)

fail() {
    # $1 exit code, $2 English text, $3 German text
    printf 'VectoraCloud: %s\nVectoraCloud: %s\n' "$2" "$3" >&2
    exit "$1"
}

jar=${VECTORA_JAR:-"$script_dir/VectoraCloud.jar"}
if [ ! -f "$jar" ]; then
    fail 66 "VectoraCloud.jar not found: $jar" "VectoraCloud.jar nicht gefunden: $jar"
fi
case "$jar" in
    /*) ;;
    *) jar="$PWD/$jar" ;;
esac

home=${VECTORA_HOME:-"$script_dir"}
if ! cd -- "$home"; then
    fail 66 "Cannot change to the cloud home: $home" "Kann nicht in das Cloud-Home wechseln: $home"
fi

xmx=${VECTORA_XMX:-512M}
if ! printf '%s' "$xmx" | grep -Eq '^[0-9]{1,7}[kKmMgG]?$'; then
    fail 64 "VECTORA_XMX must be a size such as 512M or 2G, not: $xmx" \
        "VECTORA_XMX muss eine Größe wie 512M oder 2G sein, nicht: $xmx"
fi

java_cmd=""
if [ -n "${JAVA_HOME:-}" ]; then
    if [ -x "$JAVA_HOME/bin/java" ]; then
        java_cmd="$JAVA_HOME/bin/java"
    else
        printf 'VectoraCloud: %s\nVectoraCloud: %s\n' \
            "JAVA_HOME ($JAVA_HOME) has no bin/java, trying java from the PATH." \
            "JAVA_HOME ($JAVA_HOME) enthält kein bin/java, versuche java aus dem PATH." >&2
    fi
fi
if [ -z "$java_cmd" ]; then
    if command -v java >/dev/null 2>&1; then
        java_cmd=$(command -v java)
    else
        fail 3 "No Java found. Install Java 21 or later (e.g. https://adoptium.net/temurin/releases/) and put it on the PATH or set JAVA_HOME." \
            "Kein Java gefunden. Installiere Java 21 oder neuer (z. B. https://adoptium.net/de/temurin/releases/) und nimm es in den PATH auf oder setze JAVA_HOME."
    fi
fi

# ${1+"$@"} instead of "$@": bash 3.2 with set -u treats an empty "$@" as unset.
exec "$java_cmd" "-Xmx$xmx" -jar "$jar" ${1+"$@"}
