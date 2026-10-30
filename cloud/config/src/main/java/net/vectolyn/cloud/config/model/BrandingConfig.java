package net.vectolyn.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What the network shows of VectolynCloud itself.
 *
 * @param poweredBy whether the last line of the TAB footer reads "Powered by VectolynCloud" and the
 *                  proxies answer {@code /version} with the cloud's version and its source code
 *                  address; off, the footer line is left out and {@code /version} goes to the
 *                  backend as before
 */
public record BrandingConfig(boolean poweredBy) {

    /** Powered-by line and {@code /version} on. */
    public static BrandingConfig defaults() {
        return new BrandingConfig(true);
    }

    /**
     * Reads the section from the file or an API body. A missing {@code powered-by} means on, the
     * same as a missing section ({@code branding: {}} must not switch it off). Jackson passes an
     * absent and an explicit {@code null} alike, so both count as on.
     */
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    static BrandingConfig fromFile(@JsonProperty("powered-by") Boolean poweredBy) {
        return new BrandingConfig(poweredBy == null || poweredBy);
    }
}
