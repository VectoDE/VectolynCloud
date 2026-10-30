#!/usr/bin/env bash
# Renders the release banner for a tag of the git repository in the current directory:
#   version = the tag, title = first line of the annotated tag's message (an empty message or one
#   that is only a PGP/SSH/X.509 signature gives no title), date = tagger date in UTC (commit date
#   for a lightweight tag, which has no title either).
# Writes <out-dir>/VectolynCloud-<version>-banner.png and -banner-2x.png.
#
# Usage: tools/release-banner/banner-from-tag.sh <tag> <out-dir> [gradle options...]
# release.yml runs it on the pushed tag, operations.yml on a local tag as a dry run.
# For a preview without a tag use the Gradle task directly (docs/cloud/operations.md#release-banner).
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "usage: $0 <tag> <out-dir> [gradle options...]" >&2
  exit 2
fi
tag=$1
out=$2
shift 2

ref="refs/tags/${tag}"
if ! git rev-parse --verify --quiet "${ref}" > /dev/null; then
  echo "error: no tag ${tag} in $(pwd)" >&2
  exit 1
fi

title=""
if [ "$(git cat-file -t "${ref}")" = tag ]; then
  # Header lines, an empty line, then the message; a signature is appended to the message.
  raw=$(git cat-file tag "${ref}")
  if [[ "${raw}" == *$'\n\n'* ]]; then
    body=${raw#*$'\n\n'}
    title=${body%%$'\n'*}
    title=${title%$'\r'}
    case "${title}" in
      "-----BEGIN PGP SIGNATURE-----" | "-----BEGIN SSH SIGNATURE-----" | "-----BEGIN SIGNED MESSAGE-----")
        title="" ;;
    esac
  fi
  date=$(TZ=UTC git for-each-ref --format='%(taggerdate:format-local:%Y-%m-%d)' "${ref}")
else
  date=$(TZ=UTC git log -1 --format=%cd --date=format-local:%Y-%m-%d "${ref}^{commit}")
fi
if [ -z "${date}" ]; then
  echo "error: tag ${tag} has no date" >&2
  exit 1
fi

mkdir -p "${out}"
# Absolute, and in Windows form under Git Bash (pwd -W), since Gradle reads it as a plain string.
out=$(cd "${out}" && { pwd -W 2> /dev/null || pwd; })
root=$(cd "$(dirname "$0")/../.." && pwd)

# Values go through the environment: the title is never parsed by a shell or a command line.
BANNER_VERSION="${tag}" BANNER_TITLE="${title}" BANNER_DATE="${date}" BANNER_OUT="${out}" \
  bash "${root}/gradlew" -p "${root}" :tools:release-banner:releaseBanner "$@"
