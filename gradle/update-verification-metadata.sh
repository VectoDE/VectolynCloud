#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 VectoDE
# SPDX-License-Identifier: GPL-3.0-or-later
# Rewrites gradle/verification-metadata.xml (Gradle dependency verification, SHA-256) from scratch.
#
# Usage: bash gradle/update-verification-metadata.sh
#
# Run it after every change to dependencies, plugins or repositories, review the diff, commit it.
# Every entry is trust on first use: the script records the checksum of what the repositories
# serve right now. Details and the review checklist: docs/dependency-verification.md
#
# What it does:
# 1. Keeps the <configuration> block and drops every <component>, so entries of dependencies that
#    are no longer used disappear instead of piling up.
# 2. Runs "./gradlew --write-verification-metadata sha256 help --refresh-dependencies". With this
#    flag the help task resolves every resolvable configuration of the build, including
#    build-logic, the plugins, the Jazzer setup, CodeQL/release/e2e classpaths and the bridges'
#    compileOnly APIs. --refresh-dependencies matters: with a warm Gradle cache the parent and BOM
#    .pom files are not read again and would be missing from the file, while a CI runner with an
#    empty cache needs them.
# 3. Works around a Gradle bug: the writer fails with "Multiple entries with same key" for the
#    metadata (.pom/.module) of a unique snapshot version (pandaspigot-api, velocity-brigadier). The
#    script trusts those files during the write and afterwards replaces the trust with their SHA-256,
#    taken from the Gradle cache the write has just filled. Reading such entries works, so they are
#    verified.
#
# Needs bash, awk, sha256sum and find (Git Bash on Windows has them). Gradle and Java processes of
# other builds are left alone.

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

file=gradle/verification-metadata.xml
gradle_home="${GRADLE_USER_HOME:-$HOME/.gradle}"

# Unique snapshots (group:name) that hit the Gradle bug described above. pandaspigot-api is pinned in
# gradle/libs.versions.toml, velocity-brigadier by a constraint in plugins/bridge/velocity.
snapshots="com.hpfxd.pandaspigot:pandaspigot-api com.velocitypowered:velocity-brigadier"
workaround_reason="update-verification-metadata.sh: temporary, replaced by checksums"

if [ ! -f "$file" ]; then
    echo "error: $file is missing. Restore it from git (git checkout -- $file) and run again." >&2
    exit 1
fi

# Step 1: configuration only, plus the temporary trust entry. Line endings are normalised to LF.
tmp="$(mktemp)"
backup="$(mktemp)"
cp "$file" "$backup"
finished=false
# On any failure the original file comes back, so a half-written file with the temporary trust
# entry is never left behind.
cleanup() {
    if [ "$finished" != true ]; then
        cp "$backup" "$file"
        echo "error: update failed, $file restored." >&2
    fi
    rm -f "$tmp" "$backup"
}
trap cleanup EXIT
tr -d '\r' < "$file" | awk -v list="$snapshots" -v r="$workaround_reason" '
    /^   <components>/ { print; skip = 1; next }
    /^   <\/components>/ { skip = 0 }
    skip { next }
    /^      <trusted-artifacts>/ {
        print
        count = split(list, items, " ")
        for (i = 1; i <= count; i++) {
            split(items[i], gn, ":")
            printf "         <trust group=\"%s\" name=\"%s\" file=\"%s-.*[.](module|pom)\" regex=\"true\" reason=\"%s\"/>\n", gn[1], gn[2], gn[2], r
        }
        next
    }
    { print }
' > "$tmp"
if ! grep -qF "$workaround_reason" "$tmp"; then
    echo "error: $file has no <trusted-artifacts> block; the script needs one." >&2
    exit 1
fi
cp "$tmp" "$file"

# Step 2: let Gradle write the checksums.
bash ./gradlew --write-verification-metadata sha256 help --refresh-dependencies --no-configuration-cache --quiet

# Step 3: replace the temporary trust entries with the checksums of the snapshot metadata, one
# snapshot per pass. Each pass reads $tmp and writes $file, which becomes the next pass's input.
tr -d '\r' < "$file" > "$tmp"

checksum_of() {
    local extension="$1" found count base_version name
    local cache="$gradle_home/caches/modules-2/files-2.1/$snapshot_group/$snapshot_name"
    # A unique snapshot that is declared directly (pandaspigot-api) is cached under its unique
    # version. One that is only pinned by a constraint over a transitive X-SNAPSHOT request
    # (velocity-brigadier) is cached under X-SNAPSHOT with X-SNAPSHOT file names.
    name="$snapshot_name-$version.$extension"
    found="$(find "$cache/$version" -type f -name "$name" 2>/dev/null || true)"
    if [ -z "$found" ]; then
        base_version="$(printf '%s' "$version" | sed -E 's/-[0-9]{8}[.][0-9]{6}-[0-9]+$/-SNAPSHOT/')"
        name="$snapshot_name-$base_version.$extension"
        found="$(find "$cache/$base_version" -type f -name "$name" 2>/dev/null || true)"
    fi
    count="$(printf '%s' "$found" | grep -c . || true)"
    if [ "$count" -ne 1 ]; then
        # The cache folders are named after the SHA-1 of the content, so two hits mean two
        # different files, for example an older build of an X-SNAPSHOT. Delete the stale folders
        # instead of guessing.
        echo "error: expected one copy of $name in the Gradle cache ($gradle_home), found $count:" >&2
        printf '%s\n' "$found" >&2
        exit 1
    fi
    sha256sum "$found" | cut -c1-64
}

for snapshot in $snapshots; do
    snapshot_group="${snapshot%%:*}"
    snapshot_name="${snapshot#*:}"
    version="$(awk -v g="$snapshot_group" -v n="$snapshot_name" '
        index($0, "<component group=\"" g "\" name=\"" n "\" version=\"") {
            sub(/.* version="/, ""); sub(/".*/, ""); print; exit
        }' "$tmp")"
    module_sha=""
    pom_sha=""
    if [ -n "$version" ]; then
        module_sha="$(checksum_of module)"
        pom_sha="$(checksum_of pom)"
    fi

    awk -v g="$snapshot_group" -v n="$snapshot_name" -v v="$version" -v r="$workaround_reason" \
        -v msha="$module_sha" -v psha="$pom_sha" '
        index($0, r) { next }
        v != "" && index($0, "<component group=\"" g "\" name=\"" n "\" version=\"" v "\"") { inside = 1 }
        inside && /^      <\/component>/ {
            printf "         <artifact name=\"%s-%s.module\">\n", n, v
            printf "            <sha256 value=\"%s\" origin=\"Generated by gradle/update-verification-metadata.sh\"/>\n", msha
            print  "         </artifact>"
            printf "         <artifact name=\"%s-%s.pom\">\n", n, v
            printf "            <sha256 value=\"%s\" origin=\"Generated by gradle/update-verification-metadata.sh\"/>\n", psha
            print  "         </artifact>"
            inside = 0
        }
        { print }
    ' "$tmp" > "$file"
    cp "$file" "$tmp"
done

if grep -qF "$workaround_reason" "$file"; then
    echo "error: the temporary trust entry is still in $file." >&2
    exit 1
fi

finished=true
echo "Updated $file. Review the diff (git diff -- $file) before you commit it."
