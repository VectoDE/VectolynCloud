#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 VectoDE
# SPDX-License-Identifier: GPL-3.0-or-later
# Writes the changelog of a release as Markdown on stdout (release.yml, job "release").
#
#   .github/scripts/release-changelog.sh <tag> [previous tag]
#
# The changelog is the list of commit subjects between the previous release tag and <tag>, grouped
# by Conventional Commit type. The squash merge subjects of this repository are Conventional Commit
# titles with the pull request number, for example "feat(cloud): add X (#123)", so GitHub links
# every entry to its pull request. Without a previous tag the script takes the nearest v* tag
# before <tag>; with none at all, the whole history up to <tag>.
#
# Needs the full history and the tags (actions/checkout with fetch-depth: 0).
# The repository for the compare link comes from GITHUB_REPOSITORY; without it, no link is written.

set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo "usage: $0 <tag> [previous tag]" >&2
    exit 64
fi
tag=$1
previous=${2:-}

git rev-parse --verify --quiet "refs/tags/${tag}^{commit}" > /dev/null || { echo "no such tag: ${tag}" >&2; exit 66; }
if [[ -z "${previous}" ]]; then
    previous=$(git describe --tags --abbrev=0 --match 'v*' "${tag}^" 2> /dev/null || true)
fi
if [[ -n "${previous}" ]]; then
    range="${previous}..${tag}"
else
    range="${tag}"
fi

subjects=$(git log --no-merges --format='%s' "${range}")

section() {  # section <heading> <regex of the types>
    local heading=$1 types=$2 lines
    lines=$(printf '%s\n' "${subjects}" | grep -E "^(${types})(\\([^)]*\\))?!?: " \
        | sed -E 's/^[a-z]+(\(([^)]*)\))?!?: /\2|/' \
        | awk -F'|' '{ if ($1 == "") print "- " $2; else print "- **" $1 ":** " $2 }' || true)
    if [[ -n "${lines}" ]]; then
        printf '### %s\n\n%s\n\n' "${heading}" "${lines}"
    fi
}

breaking=$(printf '%s\n' "${subjects}" | grep -E '^[a-z]+(\([^)]*\))?!: ' | sed -E 's/^[a-z]+(\(([^)]*)\))?!: /- /' || true)
printf '## Changes'
if [[ -n "${previous}" ]]; then printf ' since %s' "${previous}"; fi
printf '\n\n'
if [[ -n "${breaking}" ]]; then
    printf '### Breaking changes\n\n%s\n\n' "${breaking}"
fi
section "Features" "feat"
section "Fixes" "fix"
section "Performance" "perf"
section "Security" "security|sec"
section "Documentation" "docs"
section "Build, CI and maintenance" "build|ci|chore|refactor|test|style|revert"
other=$(printf '%s\n' "${subjects}" | grep -Ev '^(feat|fix|perf|security|sec|docs|build|ci|chore|refactor|test|style|revert)(\([^)]*\))?!?: ' | grep -v '^$' | sed 's/^/- /' || true)
if [[ -n "${other}" ]]; then
    printf '### Other\n\n%s\n\n' "${other}"
fi
if [[ -z "${subjects}" ]]; then
    printf 'No changes.\n\n'
fi
if [[ -n "${previous}" && -n "${GITHUB_REPOSITORY:-}" ]]; then
    printf 'Full comparison: https://github.com/%s/compare/%s...%s\n' "${GITHUB_REPOSITORY}" "${previous}" "${tag}"
fi
