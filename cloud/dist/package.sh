#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 VectoDE
# SPDX-License-Identifier: GPL-3.0-or-later
# Packs the VectolynCloud release files. Used by .github/workflows/release.yml and, as a dry run,
# by .github/workflows/operations.yml, so both produce exactly the same files.
#
#   cloud/dist/package.sh <version> <VectolynCloud.jar> <output folder>
#
# Writes into <output folder>:
#   VectolynCloud.jar                              the jar, unchanged
#   VectolynCloud-<version>-windows.zip            VectolynCloud.jar, start.bat, start.ps1
#   VectolynCloud-<version>-linux-macos.tar.gz     VectolynCloud.jar, start.sh (executable), the systemd
#                                                  unit and install.sh (install, update, uninstall)
#   VectolynCloud-<version>-templates.tar.gz       the server templates of the repository (templates/),
#                                                  to read, copy and change
#   setup.example.yml                              an annotated setup file for --setup-file
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
    echo "usage: $0 <version> <VectolynCloud.jar> <output folder>" >&2
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

windows="VectolynCloud-${version}-windows"
unix="VectolynCloud-${version}-linux-macos"
templates="VectolynCloud-${version}-templates"
repo_root=$(cd -- "${dist_dir}/../.." && pwd -P)
mkdir -- "${staging}/${windows}" "${staging}/${unix}" "${staging}/${templates}"

install -m 0644 -- "${jar}" "${out}/VectolynCloud.jar"
install -m 0644 -- "${jar}" "${staging}/${windows}/VectolynCloud.jar"
install -m 0644 -- "${dist_dir}/start.bat" "${dist_dir}/start.ps1" "${staging}/${windows}/"
install -m 0644 -- "${jar}" "${staging}/${unix}/VectolynCloud.jar"
install -m 0755 -- "${dist_dir}/start.sh" "${staging}/${unix}/start.sh"
install -m 0644 -- "${dist_dir}/systemd/vectolyn-cloud.service" "${staging}/${unix}/vectolyn-cloud.service"
install -m 0755 -- "${dist_dir}/systemd/install.sh" "${staging}/${unix}/install.sh"
install -m 0644 -- "${dist_dir}/setup.example.yml" "${out}/setup.example.yml"
cp -R -- "${repo_root}/templates" "${staging}/${templates}/templates"
# Fixed modes, so the archive does not depend on the umask of the build machine.
find "${staging}/${templates}" -type d -exec chmod 0755 {} +
find "${staging}/${templates}" -type f -exec chmod 0644 {} +

# The Windows scripts must have CRLF line ends (.gitattributes), start.sh must have LF.
for script in "${staging}/${windows}/start.bat" "${staging}/${windows}/start.ps1"; do
    if [[ $(grep -c $'\r$' "${script}" || true) -ne $(wc -l < "${script}") ]]; then
        echo "${script##*/} does not have CRLF line ends; check .gitattributes" >&2
        exit 65
    fi
done
for script in start.sh install.sh vectolyn-cloud.service; do
    if grep -q $'\r' "${staging}/${unix}/${script}"; then
        echo "${script} has CR characters; check .gitattributes" >&2
        exit 65
    fi
done

find "${staging}" -exec touch -h -d "@${epoch}" {} +

(
    cd -- "${staging}"
    tar --sort=name --owner=0 --group=0 --numeric-owner --mtime="@${epoch}" --format=gnu \
        -cf - "${unix}" | gzip -n -9 > "${out}/${unix}.tar.gz"
    tar --sort=name --owner=0 --group=0 --numeric-owner --mtime="@${epoch}" --format=gnu \
        -cf - "${templates}" | gzip -n -9 > "${out}/${templates}.tar.gz"
    # -X: no extra file attributes (uid/gid, extended time stamps); TZ=UTC: stable DOS times.
    find "${windows}" -print | LC_ALL=C sort | TZ=UTC zip -X -q -@ "${out}/${windows}.zip"
)

ls -l -- "${out}/VectolynCloud.jar" "${out}/${windows}.zip" "${out}/${unix}.tar.gz" \
    "${out}/${templates}.tar.gz" "${out}/setup.example.yml"
