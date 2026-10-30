/**
 * Downloads server software, plugins, mods and JREs over HTTPS from allowlisted hosts and
 * verifies their checksums.
 *
 * <ul>
 *   <li>{@link net.vectolyn.cloud.download.http} – HTTPS client with host allowlist, retries and limits</li>
 *   <li>{@link net.vectolyn.cloud.download.provider} – one resolver per download API</li>
 *   <li>{@link net.vectolyn.cloud.download.cache} – content-addressed cache and the download flow</li>
 *   <li>{@link net.vectolyn.cloud.download.lock} – the {@code software.lock.yml} lockfile</li>
 *   <li>{@link net.vectolyn.cloud.download.catalog} – the catalog of standard artifacts</li>
 *   <li>{@link net.vectolyn.cloud.download.archive} – safe extraction of zip and tar.gz</li>
 *   <li>{@link net.vectolyn.cloud.download.jre} – JREs per server group: system Java or Temurin from Adoptium</li>
 * </ul>
 *
 * <p>The format and the trust rules are described in docs/cloud/download.md.
 */
package net.vectolyn.cloud.download;
