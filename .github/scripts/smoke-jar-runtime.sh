#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 VectoDE
# SPDX-License-Identifier: GPL-3.0-or-later
# Runs VectolynCloud.jar with "java -jar" on the Java that is first on the PATH (operations.yml,
# job "runtime"): --version, then a real start in a fresh home that must draw the banner, open the
# cloud channel and end cleanly on SIGTERM.
#
#   .github/scripts/smoke-jar-runtime.sh <VectolynCloud.jar>
#
# Windows (Git Bash) only runs --version here; the full start of start.bat and start.ps1 is
# covered by smoke-start-windows.ps1. Needs bash 3.2 or later and a Java 21+ on the PATH.

set -euo pipefail

jar=$(cd -- "$(dirname -- "$1")" && pwd -P)/$(basename -- "$1")
[[ -f "${jar}" ]] || { echo "::error::no jar: ${jar}"; exit 66; }

java -version 2>&1 | head -n 1
arch=$(uname -m)
os=$(uname -s)
echo "Platform: ${os} ${arch}"

extra=()
case "${os}" in
    MINGW*|MSYS*|CYGWIN*) extra=(--allow-root) ;;  # GitHub's Windows runners are elevated
esac

version=$(java -jar "${jar}" ${extra[@]+"${extra[@]}"} --version)
echo "${version}"
case "${version}" in
    VectolynCloud\ *"(Java "*")") ;;
    *) echo "::error::unexpected --version output"; exit 1 ;;
esac

case "${os}" in
    MINGW*|MSYS*|CYGWIN*) echo "Windows: full start is covered by smoke-start-windows.ps1."; exit 0 ;;
esac

home=$(mktemp -d)
log="${home}/run.log"
pid=""
cleanup() {
    if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
        kill -KILL "${pid}" 2>/dev/null || true
    fi
    rm -rf -- "${home}"
}
trap cleanup EXIT

(cd -- "${home}" && exec java -Xmx512M -jar "${jar}" --non-interactive --lang en < /dev/null > "${log}" 2>&1) &
pid=$!

up=false
for _ in $(seq 1 90); do
    if grep -q 'Cloud channel listens on' "${log}" 2>/dev/null; then
        up=true
        break
    fi
    if ! kill -0 "${pid}" 2>/dev/null; then
        break
    fi
    sleep 2
done
cat -- "${log}"
if [[ "${up}" != true ]]; then
    echo "::error::the cloud channel did not come up"
    exit 1
fi
grep -q 'VectolynCloud .*|  Java ' "${log}" || { echo "::error::no banner info line"; exit 1; }
test -f "${home}/config/cloud.yml" || { echo "::error::no config/cloud.yml in the new home"; exit 1; }

kill -TERM "${pid}"
rc=0
wait "${pid}" || rc=$?
pid=""
echo "Exit code after SIGTERM: ${rc}"
case "${rc}" in 0|143) ;; *) echo "::error::unexpected exit code ${rc}"; exit 1 ;; esac
echo "Jar smoke test passed."
