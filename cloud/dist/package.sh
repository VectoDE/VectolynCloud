#!/usr/bin/env bash
# Packs the VectoraCloud release files. Used by .github/workflows/release.yml and, as a dry run,
# by .github/workflows/operations.yml, so both produce exactly the same files.
#
#   cloud/dist/package.sh <version> <VectoraCloud.jar> <output folder>
#
# Writes into <output folder>:
#   VectoraCloud.jar                              the jar, unchanged
#   VectoraCloud-<version>-windows.zip            VectoraCloud.jar, start.bat, start.ps1
#   VectoraCloud-<version>-linux-macos.tar.gz     VectoraCloud.jar, start.sh (executable)
#
# Why archives per operating system and not loose scripts: a downloaded start.sh loses its
# executable bit, while the tar keeps it; the zip keeps the CRLF line ends of the Windows scripts;
# and each archive is one download that already contains the matching jar. The jar is attached on
# its own as well, for Docker, Pterodactyl and anyone who starts it with "java -jar".
#
# The archives are reproducible: fixed order, owner and time stamps (the commit time, or
# SOURCE_DATE_EPOCH if set). Run from the repository root. Needs bash, tar (GNU), gzip and zip.

set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo "usage: $0 <version> <VectoraCloud.jar> <output folder>" >&2
    exit 64
fi
version=$1
jar=$2
out=$3

if [[ ! "${version}" =~ ^[0-9A-Za-z][0-9A-Za-z.+-]*$ ]]; then
    echo "invalid version: ${version}" >&2
    exit 64
fi
if [[ ! -f "${jar}" ]]; then
    echo "missing ${jar}" >&2
    exit 66
fi

dist_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
epoch=${SOURCE_DATE_EPOCH:-$(git log -1 --format=%ct)}

mkdir -p -- "${out}"
out=$(cd -- "${out}" && pwd -P)
staging=$(mktemp -d)
trap 'rm -rf -- "${staging}"' EXIT

windows="VectoraCloud-${version}-windows"
unix="VectoraCloud-${version}-linux-macos"
mkdir -- "${staging}/${windows}" "${staging}/${unix}"

install -m 0644 -- "${jar}" "${out}/VectoraCloud.jar"
install -m 0644 -- "${jar}" "${staging}/${windows}/VectoraCloud.jar"
install -m 0644 -- "${dist_dir}/start.bat" "${dist_dir}/start.ps1" "${staging}/${windows}/"
install -m 0644 -- "${jar}" "${staging}/${unix}/VectoraCloud.jar"
install -m 0755 -- "${dist_dir}/start.sh" "${staging}/${unix}/start.sh"

# The Windows scripts must have CRLF line ends (.gitattributes), start.sh must have LF.
for script in "${staging}/${windows}/start.bat" "${staging}/${windows}/start.ps1"; do
    if [[ $(grep -c $'\r$' "${script}" || true) -ne $(wc -l < "${script}") ]]; then
        echo "${script##*/} does not have CRLF line ends; check .gitattributes" >&2
        exit 65
    fi
done
if grep -q $'\r' "${staging}/${unix}/start.sh"; then
    echo "start.sh has CR characters; check .gitattributes" >&2
    exit 65
fi

find "${staging}" -exec touch -h -d "@${epoch}" {} +

(
    cd -- "${staging}"
    tar --sort=name --owner=0 --group=0 --numeric-owner --mtime="@${epoch}" --format=gnu \
        -cf - "${unix}" | gzip -n -9 > "${out}/${unix}.tar.gz"
    # -X: no extra file attributes (uid/gid, extended time stamps); TZ=UTC: stable DOS times.
    find "${windows}" -print | LC_ALL=C sort | TZ=UTC zip -X -q -@ "${out}/${windows}.zip"
)

ls -l -- "${out}/VectoraCloud.jar" "${out}/${windows}.zip" "${out}/${unix}.tar.gz"
