# Build image of the ClusterFuzzLite fuzzers (.github/workflows/cflite-pr.yml). Pinned by digest;
# Dependabot (docker, /.clusterfuzzlite) proposes newer ones. Why a second fuzzing setup next to
# Jazzer's @FuzzTest tests: docs/openssf.md
FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:bd13a763de1a0fb22099442094e7e0049657a8c47f01e9d7af8bb849e0ad4699

COPY . $SRC/VectolynCloud
COPY .clusterfuzzlite/build.sh $SRC/
WORKDIR $SRC/VectolynCloud
