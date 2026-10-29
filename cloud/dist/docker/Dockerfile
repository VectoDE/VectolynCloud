# VectoraCloud container image.
#
# Build from the repository root (the build context must contain the whole Gradle project):
#   docker build -f cloud/dist/docker/Dockerfile -t vectora-cloud .
#
# The release workflow does not build the jar a second time: it passes the signed VectoraCloud.jar
# of the release as the named build context "jar" (docker buildx build --build-context jar=dist),
# which replaces the stage of the same name below. The jar in the image then has exactly the
# SHA-256 listed in the release's SHA256SUMS.
#
# Needs BuildKit (the default builder since Docker 23) for COPY --chmod and named contexts.
# There is deliberately no "# syntax=" line: it would pull an unpinned frontend image.
#
# Base images are pinned by digest (OpenSSF Scorecard, Pinned-Dependencies); Dependabot updates
# them. Both are Eclipse Temurin on Ubuntu 24.04 (glibc), so the managed MariaDB mode works on
# x86_64. On arm64 the cloud falls back to H2 for managed mode (see docs/cloud/database.md).

# ---------------------------------------------------------------------------------------------
# Stage 1: build VectoraCloud.jar from source.
FROM eclipse-temurin:25-jdk-noble@sha256:a9c5ddd386f629518bd500ecfabf7d1e5d32d7ffed735d5c3b1604ae92c830f4 AS build
WORKDIR /src
COPY . .
# --no-daemon: one build, no background process left in the layer.
RUN bash ./gradlew --no-daemon --no-build-cache :cloud:launcher:cloudJar \
    && cp cloud/launcher/build/cloud/VectoraCloud.jar /VectoraCloud.jar

# ---------------------------------------------------------------------------------------------
# Stage 2: only the jar. Replaced by --build-context jar=<folder with VectoraCloud.jar>.
FROM scratch AS jar
COPY --from=build /VectoraCloud.jar /VectoraCloud.jar

# ---------------------------------------------------------------------------------------------
# Stage 3: runtime.
FROM eclipse-temurin:25-jre-noble@sha256:ce8543ddd3070ccbeaac2106a74c9387ddeb8d47a1c43348345715c1ef245c7f AS runtime

ARG VERSION=dev
LABEL org.opencontainers.image.title="VectoraCloud" \
      org.opencontainers.image.description="Cloud for a self-hosted Minecraft network (Java and Bedrock)" \
      org.opencontainers.image.source="https://github.com/VectoDE/Vectora-Network" \
      org.opencontainers.image.documentation="https://github.com/VectoDE/Vectora-Network/blob/main/docs/cloud/operations.md" \
      org.opencontainers.image.licenses="GPL-3.0-only" \
      org.opencontainers.image.version="${VERSION}"

# UID and GID 1000. The Ubuntu base image already has a user "ubuntu" with that UID; it is
# replaced by "vectora" so that names in logs and "ps" are clear. The home is the volume /data.
RUN if id -u ubuntu >/dev/null 2>&1; then userdel ubuntu; fi \
    && if getent group ubuntu >/dev/null; then groupdel ubuntu; fi \
    && groupadd --gid 1000 vectora \
    && useradd --uid 1000 --gid 1000 --home-dir /data --no-create-home --shell /usr/sbin/nologin vectora \
    && mkdir -p /opt/vectora /data \
    && chown 1000:1000 /data \
    && chmod 0750 /data

COPY --from=jar --chown=root:root --chmod=0644 /VectoraCloud.jar /opt/vectora/VectoraCloud.jar
COPY --chown=root:root --chmod=0755 cloud/dist/start.sh /opt/vectora/start.sh

# The jar and the start script belong to root and are read-only for the cloud; everything the
# cloud writes (configuration, secrets, servers, JREs, caches, logs) lives in /data.
ENV VECTORA_HOME=/data \
    VECTORA_JAR=/opt/vectora/VectoraCloud.jar \
    VECTORA_XMX=512M \
    VECTORA_HEALTHCHECK_PORT=7010
WORKDIR /data
VOLUME ["/data"]
USER 1000:1000

# Default ports (all configurable in cloud.yml): Java proxy, Bedrock (Geyser, UDP and TCP),
# legacy proxy for 1.8.8. Publish only the ones you use; the cloud channel (7010) stays inside.
EXPOSE 25565/tcp 19132/udp 19132/tcp 25564/tcp

# docker stop sends SIGTERM; the cloud stops every server with its stop command, then itself.
# Give it time: docker stop -t 300, or stop_grace_period in compose.yml.
STOPSIGNAL SIGTERM

# Healthy once the cloud channel accepts connections on loopback. Uses bash's /dev/tcp, so the
# image needs no curl or netcat. The connection is closed right away, before the channel's
# handshake timeout, so the check leaves no log line behind.
HEALTHCHECK --interval=30s --timeout=5s --start-period=2m --retries=3 \
    CMD ["bash", "-c", "exec 3<>\"/dev/tcp/127.0.0.1/${VECTORA_HEALTHCHECK_PORT}\""]

# Arguments after the image name go to VectoraCloud, e.g.
#   docker run ... ghcr.io/vectode/vectora-cloud --non-interactive --setup-file /data/setup.yml
ENTRYPOINT ["/opt/vectora/start.sh"]
