#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 VectoDE
# SPDX-License-Identifier: GPL-3.0-or-later
# Milestone gate of the release (release.yml, job "gate"; docs/cloud/operations.md#release-process).
#
#   .github/scripts/release-gate.sh <channel> [milestones.json]
#
# <channel> is the channel the tag check printed: dev, alpha, beta, rc, lts or stable.
# The milestones come from the GitHub API (needs GH_TOKEN and GITHUB_REPOSITORY, or gh logged in
# for the repository in the current folder), or from a JSON file with the same shape (tests).
#
# What must be closed (zero open issues) before the tag is released:
#   dev      nothing; a -dev release is a snapshot of the work in progress
#   alpha    Alpha
#   beta     Alpha, Beta
#   rc       Alpha, Beta, RC
#   lts      Alpha, Beta, RC and every "Stage ..." milestone
#   stable   the same as lts
# A milestone that does not exist stops the release as well: the gate must not pass because
# somebody renamed or deleted it. Exit codes: 0 gate open, 1 gate closed, 64 usage.

set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo "usage: $0 <dev|alpha|beta|rc|lts|stable> [milestones.json]" >&2
    exit 64
fi
channel=$1

case "${channel}" in
    dev) required=() ; stages=no ;;
    alpha) required=(Alpha) ; stages=no ;;
    beta) required=(Alpha Beta) ; stages=no ;;
    rc) required=(Alpha Beta RC) ; stages=no ;;
    lts|stable) required=(Alpha Beta RC) ; stages=yes ;;
    *) echo "unknown channel: ${channel}" >&2; exit 64 ;;
esac

if [[ "${channel}" == dev ]]; then
    echo "Channel dev: no milestone gate."
    exit 0
fi

if [[ $# -eq 2 ]]; then
    milestones=$(cat -- "$2")
else
    milestones=$(gh api --paginate "repos/${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is not set}/milestones?state=all&per_page=100" \
        | jq -s 'add')
fi

failed=0
check() {  # check <title>
    local title=${1%$'\r'} line
    line=$(jq -r --arg t "${title}" \
        '[.[] | select((.title | ascii_downcase) == ($t | ascii_downcase))][0] // empty | "\(.title)\t\(.open_issues)\t\(.closed_issues)\t\(.html_url // "")"' \
        <<< "${milestones}")
    if [[ -z "${line}" ]]; then
        echo "::error title=Release gate::milestone \"${title}\" does not exist"
        failed=1
        return
    fi
    local name open closed url
    IFS=$'\t' read -r name open closed url <<< "${line}"
    if [[ "${open}" -ne 0 ]]; then
        echo "::error title=Release gate::milestone \"${name}\" has ${open} open issue(s) (${closed} closed) ${url}"
        failed=1
    else
        echo "ok   ${name}: no open issues (${closed} closed)"
    fi
}

for title in "${required[@]}"; do
    check "${title}"
done
if [[ "${stages}" == yes ]]; then
    mapfile -t stage_titles < <(jq -r '.[] | select(.title | test("^Stage "; "i")) | .title' <<< "${milestones}")
    if [[ ${#stage_titles[@]} -eq 0 ]]; then
        echo "::error title=Release gate::no \"Stage ...\" milestones found"
        failed=1
    fi
    for title in "${stage_titles[@]}"; do
        check "${title}"
    done
fi

if [[ "${failed}" -ne 0 ]]; then
    echo "The release gate for ${channel} is closed. Close or move the open issues, or delete the tag." >&2
    exit 1
fi
echo "The release gate for ${channel} is open."
