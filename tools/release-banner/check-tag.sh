#!/usr/bin/env bash
# Checks a release tag against the version rules (docs/cloud/operations.md#versions-and-tags) with
# the parser that also draws the banner (ReleaseVersion), and prints on stdout:
#   channel=<dev|alpha|beta|rc|lts|stable>
#   prerelease=<true|false>
# Gradle's own output goes to stderr. A tag that breaks the rules exits non-zero, with the reason
# on stderr.
#
# Usage: tools/release-banner/check-tag.sh <tag> [gradle options...]
# release.yml runs it on the pushed tag before the build, operations.yml on every rule as a dry run.
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "usage: $0 <tag> [gradle options...]" >&2
  exit 2
fi
tag=$1
shift

root=$(cd "$(dirname "$0")/../.." && pwd)
dir=$(mktemp -d)
trap 'rm -rf "${dir}"' EXIT
# Absolute, and in Windows form under Git Bash (pwd -W), since Gradle reads it as a plain string.
out="$(cd "${dir}" && { pwd -W 2> /dev/null || pwd; })/result.txt"

# The tag goes through the environment: it is never parsed by a shell or a command line.
RELEASE_TAG="${tag}" RELEASE_TAG_OUT="${out}" \
  bash "${root}/gradlew" -p "${root}" --quiet :tools:release-banner:releaseTag "$@" >&2
cat "${dir}/result.txt"
