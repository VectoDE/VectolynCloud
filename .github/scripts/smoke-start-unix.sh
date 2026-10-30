#!/usr/bin/env bash
# Smoke test of cloud/dist/start.sh and the linux-macos archive (operations.yml).
#
#   .github/scripts/smoke-start-unix.sh <folder with VectolynCloud.jar and the archives>
#
# Environment: JAVA25 (a Java 21+ home), JAVA17 (a Java home older than 21).
# Runs on Linux and macOS (bash 3.2). Every case prints what it expects and what it got.

set -euo pipefail

dist=$(cd -- "$1" && pwd -P)
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
: "${JAVA25:?JAVA25 must point to a Java 21+ home}"
: "${JAVA17:?JAVA17 must point to a Java home older than 21}"

# A folder with a space, parentheses and a dollar sign: quoting mistakes show up here.
work=$(mktemp -d)
trap 'rm -rf -- "${work}"' EXIT
dir="${work}/vectolyn home (\$test)"
mkdir -p -- "${dir}"
cp -- "${dist}/VectolynCloud.jar" "${dir}/"
cp -- "${repo}/cloud/dist/start.sh" "${dir}/"
chmod 0755 "${dir}/start.sh"

# A PATH without any java, for the "no Java" case.
nojava="${work}/nojava-bin"
mkdir -p -- "${nojava}"
for tool in bash env dirname grep cat uname; do
    tool_path=$(type -P "${tool}" || true)
    if [[ -n "${tool_path}" ]]; then
        ln -s "${tool_path}" "${nojava}/${tool}"
    fi
done

failures=0
# expect <name> <exit code> <text the output must contain or ""> -- <command...>
expect() {
    local name=$1 code=$2 text=$3
    shift 4
    local out rc=0
    out=$("$@" 2>&1) || rc=$?
    if [[ "${rc}" -ne "${code}" ]]; then
        printf '::error::%s: exit %s, expected %s\n%s\n' "${name}" "${rc}" "${code}" "${out}"
        failures=$((failures + 1))
        return
    fi
    if [[ -n "${text}" ]] && ! printf '%s' "${out}" | grep -qF -- "${text}"; then
        printf '::error::%s: output lacks "%s"\n%s\n' "${name}" "${text}" "${out}"
        failures=$((failures + 1))
        return
    fi
    printf 'ok   %s (exit %s)\n' "${name}" "${rc}"
}

sh="${dir}/start.sh"
expect "version" 0 "VectolynCloud" -- env JAVA_HOME="${JAVA25}" "${sh}" --version
expect "help" 0 "--setup-file" -- env JAVA_HOME="${JAVA25}" "${sh}" --help
# shellcheck disable=SC2016 # the $(...) and backticks must reach Java unexpanded
expect "unknown argument is passed through" 64 "--bogus" -- \
    env JAVA_HOME="${JAVA25}" "${sh}" --lang en --bogus 'a b $(touch pwned) `id`'
if [[ -e pwned || -e "${dir}/pwned" ]]; then
    echo "::error::an argument was executed"
    failures=$((failures + 1))
fi
expect "java too old (bootstrap message)" 3 "21" -- env JAVA_HOME="${JAVA17}" "${sh}" --lang en --version
expect "no java" 3 "adoptium.net" -- env -u JAVA_HOME PATH="${nojava}" "${sh}" --version
expect "JAVA_HOME without java falls back to PATH" 0 "trying java from the PATH" -- \
    env JAVA_HOME="${work}/missing" PATH="${JAVA25}/bin:${PATH}" "${sh}" --version
expect "invalid VECTOLYN_XMX" 64 "VECTOLYN_XMX" -- env JAVA_HOME="${JAVA25}" VECTOLYN_XMX='1G; id' "${sh}" --version
expect "missing jar" 66 "not found" -- env JAVA_HOME="${JAVA25}" VECTOLYN_JAR="${work}/none.jar" "${sh}" --version
expect "missing home" 66 "cloud home" -- env JAVA_HOME="${JAVA25}" VECTOLYN_HOME="${work}/none" "${sh}" --version

# Root: refused without --allow-root, allowed with it (needs passwordless sudo, as on GitHub runners).
if [[ "$(uname -s)" == Linux ]] && sudo -n true 2>/dev/null; then
    expect "root refused" 2 "root" -- sudo -n env JAVA_HOME="${JAVA25}" "${sh}" --lang en --version
    expect "root allowed with --allow-root" 0 "WARNING" -- sudo -n env JAVA_HOME="${JAVA25}" "${sh}" --allow-root --lang en --version
    expect "root allowed with VECTOLYN_ALLOW_ROOT" 0 "WARNING" -- \
        sudo -n env JAVA_HOME="${JAVA25}" VECTOLYN_ALLOW_ROOT=true "${sh}" --lang en --version
fi

# The release archive: executable start.sh with LF line ends, the same jar.
archive=$(find "${dist}" -maxdepth 1 -name 'VectolynCloud-*-linux-macos.tar.gz' | head -n 1)
if [[ -z "${archive}" ]]; then
    echo "::error::no linux-macos archive in ${dist}"
    failures=$((failures + 1))
else
    mkdir -p -- "${work}/archive"
    tar -xzf "${archive}" -C "${work}/archive"
    unpacked=$(find "${work}/archive" -name start.sh)
    if [[ ! -x "${unpacked}" ]]; then
        echo "::error::start.sh in the archive is not executable"
        failures=$((failures + 1))
    fi
    if grep -q $'\r' "${unpacked}"; then
        echo "::error::start.sh in the archive has CR characters"
        failures=$((failures + 1))
    fi
    expect "archive start.sh" 0 "VectolynCloud" -- env JAVA_HOME="${JAVA25}" "${unpacked}" --version
fi

if [[ "${failures}" -ne 0 ]]; then
    echo "${failures} case(s) failed"
    exit 1
fi
echo "All start.sh cases passed."
