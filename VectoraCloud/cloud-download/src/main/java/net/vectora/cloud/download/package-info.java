/**
 * Downloads server software, plugins, mods and later JREs over HTTPS from allowlisted hosts and
 * verifies their checksums.
 *
 * <ul>
 *   <li>{@link net.vectora.cloud.download.http} – HTTPS client with host allowlist, retries and limits</li>
 *   <li>{@link net.vectora.cloud.download.provider} – one resolver per download API</li>
 *   <li>{@link net.vectora.cloud.download.cache} – content-addressed cache and the download flow</li>
 *   <li>{@link net.vectora.cloud.download.lock} – the {@code software.lock.yml} lockfile</li>
 *   <li>{@link net.vectora.cloud.download.catalog} – the catalog of standard artifacts</li>
 *   <li>{@link net.vectora.cloud.download.archive} – safe extraction of zip and tar.gz</li>
 * </ul>
 *
 * <p>The format and the trust rules are described in docs/cloud/download.md.
 */
package net.vectora.cloud.download;
