package net.vectolyn.cloud.download;

/**
 * Downloads server software, plugins and JREs over HTTPS from allowlisted hosts and verifies checksums.
 *
 * <p>Overview of the module: docs/cloud/download.md. Its place among the cloud modules:
 * docs/cloud/architecture.md.
 */
public final class CloudDownloadModule {

    /** Gradle project name of this module. */
    public static final String NAME = "cloud-download";

    private CloudDownloadModule() {
    }
}
