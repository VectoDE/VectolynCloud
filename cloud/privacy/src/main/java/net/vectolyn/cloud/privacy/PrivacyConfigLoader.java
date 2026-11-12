// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        if (!errors.isEmpty()) {
            throw new ConfigException(errors);
        }
        return config;
    }
}
