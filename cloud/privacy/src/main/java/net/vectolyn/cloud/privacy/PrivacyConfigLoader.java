// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.error.ConfigError;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.error.ErrorCode;
import net.vectolyn.cloud.config.io.ConfigFormat;
import net.vectolyn.cloud.config.io.ConfigMappers;
import net.vectolyn.cloud.config.io.ConfigYaml;
import net.vectolyn.cloud.config.io.RegularPaths;

/** Reads and checks {@code privacy.yml} with the same strict YAML reader as the other configuration files. */
public final class PrivacyConfigLoader {

    /** File name, relative to {@code config/}. */
    public static final String FILE = CloudHome.PRIVACY_FILE;

    private static final Pattern PERSON = Pattern.compile("[\\p{L}0-9][\\p{L}0-9 ._@-]{0,63}");

    private static final ConfigFormat<PrivacyConfig> FORMAT =
            new ConfigFormat<>(PrivacyConfig.class, PrivacyConfig.SCHEMA_VERSION, List.of());

    private PrivacyConfigLoader() {
    }

    /**
     * Reads {@code <home>/config/privacy.yml}; a missing file gives the defaults.
     *
     * @param home cloud home
     * @return the checked configuration
     * @throws ConfigException if the file is unreadable, not a regular file or invalid
     */
    public static PrivacyConfig load(Path home) throws ConfigException {
        Path file = CloudHome.of(home).privacyFile();
        byte[] content;
        try {
            RegularPaths.requireFileOrMissing(file);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return PrivacyConfig.defaults();
            }
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                content = in.readNBytes(ConfigMappers.MAX_FILE_BYTES + 1);
            }
        } catch (NoSuchFileException e) {
            return PrivacyConfig.defaults();
        } catch (IOException e) {
            throw new ConfigException(ConfigError.of(FILE, "", ErrorCode.FILE_ACCESS));
        }
        return parse(content);
    }

    /**
     * Parses and checks the content of {@code privacy.yml}.
     *
     * @param content raw bytes (UTF-8)
     * @return the checked configuration
     * @throws ConfigException if anything is invalid
     */
    public static PrivacyConfig parse(byte[] content) throws ConfigException {
        return check(ConfigYaml.read(FILE, content, FORMAT).value());
    }

    /**
     * Checks a configuration.
     *
     * @param config the configuration
     * @return the same configuration
     * @throws ConfigException with every problem found
     */
    public static PrivacyConfig check(PrivacyConfig config) throws ConfigException {
        List<ConfigError> errors = new ArrayList<>();
        int days = config.ipBans().retentionDays();
        if (days < 1 || days > PrivacyConfig.IpBans.MAX_RETENTION_DAYS) {
            errors.add(ConfigError.of(FILE, "ip-bans.retention-days", ErrorCode.OUT_OF_RANGE, 1,
                    PrivacyConfig.IpBans.MAX_RETENTION_DAYS));
        }
        checkTelemetry(config.telemetry(), errors);
        checkTracking(config.tracking(), errors);
        if (!errors.isEmpty()) {
            throw new ConfigException(errors);
        }
        return config;
    }

    /** Telemetry only runs with a complete record of consent and an HTTPS endpoint. */
    private static void checkTelemetry(PrivacyConfig.Telemetry telemetry, List<ConfigError> errors) {
        if (!telemetry.enabled()) {
            return;
        }
        PrivacyConfig.Consent consent = telemetry.consent();
        if (consent == null) {
            errors.add(ConfigError.of(FILE, "telemetry.consent", ErrorCode.INVALID_VALUE,
                    "the consent (given-by, given-at, text-version) when telemetry is enabled"));
        } else {
            if (consent.givenBy() == null || !PERSON.matcher(consent.givenBy()).matches()) {
                errors.add(ConfigError.of(FILE, "telemetry.consent.given-by", ErrorCode.INVALID_VALUE,
                        "the name of the person who agreed, at most 64 characters"));
            }
            if (!pastInstant(consent.givenAt())) {
                errors.add(ConfigError.of(FILE, "telemetry.consent.given-at", ErrorCode.INVALID_VALUE,
                        "a past time such as 2026-11-12T10:00:00Z"));
            }
            if (consent.textVersion() == null || consent.textVersion() != PrivacyConfig.Consent.CURRENT_TEXT_VERSION) {
                errors.add(ConfigError.of(FILE, "telemetry.consent.text-version", ErrorCode.INVALID_VALUE,
                        "the current text version " + PrivacyConfig.Consent.CURRENT_TEXT_VERSION));
            }
        }
        if (!isHttps(telemetry.endpoint())) {
            errors.add(ConfigError.of(FILE, "telemetry.endpoint", ErrorCode.INVALID_VALUE,
                    "an https:// URL without user name or password"));
        }
    }

    /** Tracking needs a retention period in range and, when on, a named controller. */
    private static void checkTracking(PrivacyConfig.Tracking tracking, List<ConfigError> errors) {
        int days = tracking.retentionDays();
        if (days < 1 || days > PrivacyConfig.IpBans.MAX_RETENTION_DAYS) {
            errors.add(ConfigError.of(FILE, "tracking.retention-days", ErrorCode.OUT_OF_RANGE, 1,
                    PrivacyConfig.IpBans.MAX_RETENTION_DAYS));
        }
        String controller = tracking.controller();
        boolean valid = controller.length() <= 200 && controller.chars().noneMatch(Character::isISOControl);
        if (!valid || (tracking.enabled() && controller.isBlank())) {
            errors.add(ConfigError.of(FILE, "tracking.controller", ErrorCode.INVALID_VALUE,
                    "who is responsible (name and contact), at most 200 characters, required when tracking is enabled"));
        }
    }

    private static boolean pastInstant(String text) {
        try {
            return text != null && !Instant.parse(text).isAfter(Instant.now());
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isHttps(String text) {
        if (text == null || text.length() > 256) {
            return false;
        }
        try {
            URI uri = new URI(text);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getFragment() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
