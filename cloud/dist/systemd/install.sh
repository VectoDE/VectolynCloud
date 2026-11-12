#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 VectoDE
# SPDX-License-Identifier: GPL-3.0-or-later
# Installs, updates and removes VectolynCloud as a systemd service on Linux.
#
#   sudo ./install.sh install   [options]    first installation
#   sudo ./install.sh update    [options]    newer (or other) release, keeps the home
#   sudo ./install.sh uninstall [--purge]    remove the service
#   ./install.sh --help
#
# Options:
#   --version <x.y.z[-dev]>   release to install (default: the newest release, pre-releases included)
#   --archive <file>          a downloaded VectolynCloud-<version>-linux-macos.tar.gz; nothing is downloaded
#   --prefix <dir>            cloud home (default: /opt/vectolyn); also set as VECTOLYN_HOME in the unit
#   --user <name>             service user (default: vectolyn); created if missing
#   --java-home <dir>         Java 21+ outside the PATH; written to a drop-in
#   --no-start                install, but do not enable or start the service
#   --purge                   uninstall only: also delete the home (worlds, configuration, secrets) and the user
#
# What it does
#   install    creates the user, downloads the release archive and checks it against SHA256SUMS (and
#              against the cosign signature when cosign is installed), puts VectolynCloud.jar, start.sh
#              and the unit in place, reloads systemd, enables and starts the service. Needs a setup
#              file (/opt/vectolyn/setup.yml) or a finished setup, see docs/cloud/operations.md.
#   update     the same download and checks, stops the service (the cloud stops every server first),
#              keeps the previous jar as VectolynCloud.jar.previous, replaces jar, start.sh and unit,
#              starts the service again. Configuration, secrets, worlds and drop-ins stay.
#   uninstall  stops and disables the service, removes the unit and the program files. The home with
#              your data stays unless you add --purge. Drop-ins are removed together with the unit.
#
# Environment: VECTOLYN_INSTALL_REPO (default VectoDE/VectolynCloud) for forks and tests.
# Runs with bash 4+ on any systemd distribution. Needs curl (unless --archive), tar, sha256sum.

set -euo pipefail

REPO=${VECTOLYN_INSTALL_REPO:-VectoDE/VectolynCloud}
UNIT_NAME=vectolyn-cloud.service
UNIT_PATH=/etc/systemd/system/${UNIT_NAME}
DROPIN_DIR=/etc/systemd/system/${UNIT_NAME}.d

prefix=/opt/vectolyn
svc_user=vectolyn
version=latest
archive=""
java_home=""
start_service=yes
purge=no
action=""

die() {
    printf 'install.sh: %s\n' "$*" >&2
    exit 1
}

say() {
    printf 'install.sh: %s\n' "$*"
}

usage() {
    sed -n '4,32p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
    case "$1" in
        install|update|uninstall) action=$1 ;;
        --version) [ $# -ge 2 ] || die "--version needs a value"; version=$2; shift ;;
        --archive) [ $# -ge 2 ] || die "--archive needs a file"; archive=$2; shift ;;
        --prefix) [ $# -ge 2 ] || die "--prefix needs a folder"; prefix=$2; shift ;;
        --user) [ $# -ge 2 ] || die "--user needs a name"; svc_user=$2; shift ;;
        --java-home) [ $# -ge 2 ] || die "--java-home needs a folder"; java_home=$2; shift ;;
        --no-start) start_service=no ;;
        --purge) purge=yes ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown argument: $1 (see --help)" ;;
    esac
    shift
done
[ -n "$action" ] || { usage >&2; exit 64; }

# Values that end up in a unit file or a path must be plain.
case "$prefix" in
    /*[!A-Za-z0-9/._-]*|"") die "--prefix must be an absolute path of letters, digits, / . _ -" ;;
    /) die "--prefix must not be /" ;;
    /*) ;;
    *) die "--prefix must be an absolute path" ;;
esac
case "$svc_user" in
    [a-z_][a-z0-9_-]*) ;;
    *) die "--user must be a lowercase user name" ;;
esac
case "$version" in
    latest) ;;
    *[!0-9A-Za-z.+-]*|"") die "invalid --version: $version" ;;
esac

[ "$(id -u)" -eq 0 ] || die "run as root (sudo), the unit and the user are system-wide"
command -v systemctl >/dev/null 2>&1 || die "systemd (systemctl) not found; this installer is for systemd systems"

work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT

# Builds the unit for the chosen prefix and user from the one in the archive.
render_unit() {
    local src=$1 dst=$2
    sed -e "s#/opt/vectolyn#${prefix}#g" -e "s#^User=.*#User=${svc_user}#" -e "s#^Group=.*#Group=${svc_user}#" \
        -- "$src" > "$dst"
}

fetch_archive() {
    # Sets $archive_file to the verified archive in $work.
    if [ -n "$archive" ]; then
        [ -f "$archive" ] || die "archive not found: $archive"
        archive_file=$archive
        say "Using local archive $archive (no download, no checksum list)."
        return
    fi
    command -v curl >/dev/null 2>&1 || die "curl not found (or give --archive)"
    local tag
    if [ "$version" = latest ]; then
        # The newest release including pre-releases: every 0.x release is a pre-release.
        tag=$(curl -fsSL --proto '=https' --tlsv1.2 -H 'Accept: application/vnd.github+json' \
            "https://api.github.com/repos/${REPO}/releases?per_page=20" \
            | sed -n 's/^ *"tag_name": *"\([^"]*\)".*/\1/p' | head -n 1)
        [ -n "$tag" ] || die "no release found for ${REPO}"
    else
        tag="v${version#v}"
    fi
    case "$tag" in v[0-9]*) ;; *) die "not a release tag: $tag" ;; esac
    case "$tag" in *[!0-9A-Za-z.+-]*) die "invalid release tag: $tag" ;; esac
    local name="VectolynCloud-${tag#v}-linux-macos.tar.gz"
    local base="https://github.com/${REPO}/releases/download/${tag}"
    say "Downloading ${tag}"
    curl -fsSL --proto '=https' --tlsv1.2 -o "$work/$name" "${base}/${name}" || die "download failed: ${base}/${name}"
    curl -fsSL --proto '=https' --tlsv1.2 -o "$work/SHA256SUMS" "${base}/SHA256SUMS" || die "download failed: ${base}/SHA256SUMS"
    (cd "$work" && grep -F -- "  ${name}" SHA256SUMS | sha256sum --check --strict -) \
        || die "SHA-256 of ${name} does not match SHA256SUMS; nothing was installed"
    if command -v cosign >/dev/null 2>&1; then
        curl -fsSL --proto '=https' --tlsv1.2 -o "$work/${name}.sigstore.json" "${base}/${name}.sigstore.json" \
            || die "download failed: ${base}/${name}.sigstore.json"
        cosign verify-blob "$work/$name" --bundle "$work/${name}.sigstore.json" \
            --certificate-identity "https://github.com/${REPO}/.github/workflows/release.yml@refs/tags/${tag}" \
            --certificate-oidc-issuer https://token.actions.githubusercontent.com \
            || die "cosign signature of ${name} is not valid; nothing was installed"
    else
        say "cosign not found: only the SHA-256 was checked. See docs/cloud/operations.md#verifying-a-release."
    fi
    archive_file="$work/$name"
}

unpack() {
    # Sets $src to the folder with VectolynCloud.jar, start.sh and the unit.
    mkdir -p "$work/unpacked"
    tar -xzf "$archive_file" -C "$work/unpacked"
    src=$(find "$work/unpacked" -mindepth 1 -maxdepth 1 -type d | head -n 1)
    [ -n "$src" ] || die "the archive has no folder inside"
    for f in VectolynCloud.jar start.sh "$UNIT_NAME" install.sh; do
        [ -f "$src/$f" ] || die "the archive lacks $f (a release older than the installer?)"
    done
}

ensure_user() {
    if id -u "$svc_user" >/dev/null 2>&1; then
        say "User ${svc_user} exists."
    else
        useradd --system --user-group --home-dir "$prefix" --create-home --shell /usr/sbin/nologin "$svc_user"
        say "Created user ${svc_user}."
    fi
    install -d -o "$svc_user" -g "$svc_user" -m 0750 "$prefix"
}

write_java_dropin() {
    [ -n "$java_home" ] || return 0
    [ -x "$java_home/bin/java" ] || die "no bin/java in --java-home $java_home"
    case "$java_home" in *[!A-Za-z0-9/._-]*) die "--java-home must be a plain path" ;; esac
    install -d -m 0755 "$DROPIN_DIR"
    printf '[Service]\nEnvironment=JAVA_HOME=%s\n' "$java_home" > "$DROPIN_DIR/java.conf"
    say "Wrote $DROPIN_DIR/java.conf"
}

install_files() {
    install -o "$svc_user" -g "$svc_user" -m 0644 -- "$src/VectolynCloud.jar" "$prefix/VectolynCloud.jar.new"
    mv -f -- "$prefix/VectolynCloud.jar.new" "$prefix/VectolynCloud.jar"
    install -o "$svc_user" -g "$svc_user" -m 0755 -- "$src/start.sh" "$prefix/start.sh"
    render_unit "$src/$UNIT_NAME" "$work/unit"
    install -o root -g root -m 0644 -- "$work/unit" "$UNIT_PATH"
    # Keep a copy of this installer next to the program for "update" and "uninstall" later.
    install -o root -g root -m 0755 -- "$src/install.sh" "$prefix/install.sh"
    systemctl daemon-reload
}

case "$action" in
    install)
        if [ -f "$UNIT_PATH" ] || [ -f "$prefix/VectolynCloud.jar" ]; then
            die "VectolynCloud is already installed (${UNIT_PATH} or ${prefix}/VectolynCloud.jar exists); use update"
        fi
        fetch_archive
        unpack
        ensure_user
        install_files
        write_java_dropin
        systemctl daemon-reload
        say "Installed $(sha256sum "$prefix/VectolynCloud.jar" | cut -d' ' -f1) VectolynCloud.jar into ${prefix}."
        if [ "$start_service" = yes ]; then
            if [ -f "$prefix/config/cloud.yml" ] || [ -f "$prefix/setup.yml" ]; then
                systemctl enable --now "$UNIT_NAME"
                say "Started. Logs: journalctl -u ${UNIT_NAME} -f"
            else
                systemctl enable "$UNIT_NAME"
                say "Enabled, not started: there is no setup yet. Write ${prefix}/setup.yml (docs/cloud/setup.md),"
                say "set 'ExecStart' to the --setup-file line in a drop-in (systemctl edit ${UNIT_NAME}), then:"
                say "  systemctl start ${UNIT_NAME}"
            fi
        else
            say "Not enabled (--no-start). Enable with: systemctl enable --now ${UNIT_NAME}"
        fi
        ;;
    update)
        [ -f "$UNIT_PATH" ] && [ -f "$prefix/VectolynCloud.jar" ] || die "VectolynCloud is not installed under ${prefix}; use install"
        fetch_archive
        unpack
        was_active=no
        if systemctl is-active --quiet "$UNIT_NAME"; then was_active=yes; fi
        if [ "$was_active" = yes ]; then
            say "Stopping ${UNIT_NAME} (the cloud stops every server first, up to the unit's TimeoutStopSec)."
            systemctl stop "$UNIT_NAME"
        fi
        cp -p -- "$prefix/VectolynCloud.jar" "$prefix/VectolynCloud.jar.previous"
        install_files
        write_java_dropin
        say "Updated to $(sha256sum "$prefix/VectolynCloud.jar" | cut -d' ' -f1) VectolynCloud.jar. Previous jar: ${prefix}/VectolynCloud.jar.previous"
        if [ "$was_active" = yes ] && [ "$start_service" = yes ]; then
            systemctl start "$UNIT_NAME"
            say "Started again."
        elif [ "$was_active" = no ]; then
            say "The service was not running; it stays stopped."
        fi
        ;;
    uninstall)
        if systemctl list-unit-files "$UNIT_NAME" 2>/dev/null | grep -q "$UNIT_NAME"; then
            systemctl disable --now "$UNIT_NAME" || true
        fi
        rm -f -- "$UNIT_PATH"
        rm -rf -- "$DROPIN_DIR"
        systemctl daemon-reload
        rm -f -- "$prefix/VectolynCloud.jar" "$prefix/VectolynCloud.jar.previous" "$prefix/VectolynCloud.jar.new" \
            "$prefix/start.sh" "$prefix/install.sh"
        say "Removed the service and the program files. Your data in ${prefix} stays."
        if [ "$purge" = yes ]; then
            rm -rf -- "$prefix"
            if id -u "$svc_user" >/dev/null 2>&1; then
                userdel "$svc_user" || true
                getent group "$svc_user" >/dev/null && groupdel "$svc_user" 2>/dev/null || true
            fi
            say "Purged ${prefix} and the user ${svc_user}."
        fi
        ;;
esac
