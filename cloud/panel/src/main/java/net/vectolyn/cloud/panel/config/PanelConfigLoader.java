// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.error.ConfigError;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.error.ErrorCode;
import net.vectolyn.cloud.config.io.ConfigFormat;
import net.vectolyn.cloud.config.io.ConfigMappers;
import net.vectolyn.cloud.config.io.ConfigYaml;
import net.vectolyn.cloud.config.io.RegularPaths;

/**
 * Reads and checks {@code config/panel.yml} with the strict YAML reader of the other configuration
 * files (unknown fields, duplicate keys and type guessing fail). Problems come back as
 * {@link ConfigError} with file, field and code; messages never quote a value.
 */
public final class PanelConfigLoader {

    /** File name, relative to {@code config/}. */
    public static final String FILE = "panel.yml";

    private static final ConfigFormat<PanelConfig> FORMAT =
            new ConfigFormat<>(PanelConfig.class, PanelConfig.SCHEMA_VERSION, List.of());

    private PanelConfigLoader() {
    }

    /**
     * A checked configuration.
     *
     * @param config    the file with its defaults
     * @param publicUrl the origin of {@code public-url} ({@code scheme://host[:port]}, lower case,
     *                  default port left out), or empty
     */
    public record Settings(PanelConfig config, Optional<String> publicUrl) {
    }

    /**
     * Reads {@code <home>/config/panel.yml}; a missing file gives the defaults (panel off).
     *
     * @param home cloud home
     * @return the checked settings
     * @throws ConfigException if the file cannot be read, is not a regular file or is invalid
     */
    public static Settings load(Path home) throws ConfigException {
        Path file = CloudHome.of(home).configDir().resolve(FILE);
        byte[] content;
        try {
            RegularPaths.requireFileOrMissing(file);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return check(PanelConfig.defaults());
            }
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                content = in.readNBytes(ConfigMappers.MAX_FILE_BYTES + 1);
            }
        } catch (NoSuchFileException e) {
            return check(PanelConfig.defaults());
        } catch (IOException e) {
            throw new ConfigException(ConfigError.of(FILE, "", ErrorCode.FILE_ACCESS));
        }
        return parse(content);
    }

    /**
     * Parses and checks the content of {@code panel.yml}.
     *
     * @param content raw bytes (UTF-8)
     * @return the checked settings
     * @throws ConfigException if anything is invalid
     */
    public static Settings parse(byte[] content) throws ConfigException {
        return check(ConfigYaml.read(FILE, content, FORMAT).value());
    }

    /**
     * Checks a configuration.
     *
     * @param config the configuration
     * @return the settings
     * @throws ConfigException with every problem found
     */
    public static Settings check(PanelConfig config) throws ConfigException {
        List<ConfigError> errors = new ArrayList<>();
        if (config.port() < 1 || config.port() > 65_535) {
            errors.add(ConfigError.of(FILE, "port", ErrorCode.OUT_OF_RANGE, 1, 65_535));
        }
        if (config.bind().isBlank() || config.bind().length() > 255 || !config.bind().matches("[0-9A-Za-z.:_-]+")) {
            errors.add(ConfigError.of(FILE, "bind", ErrorCode.INVALID_VALUE, "an IP address or host name"));
        }
        Optional<String> origin = Optional.empty();
        if (config.publicUrl() != null) {
            origin = origin(config.publicUrl());
            if (origin.isEmpty()) {
                errors.add(ConfigError.of(FILE, "public-url", ErrorCode.INVALID_VALUE,
                        "https://host[:port] without path, or http://localhost[:port]"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ConfigException(errors);
        }
        return new Settings(config, origin);
    }

    /**
     * The origin of a public URL: {@code https} with any host, or {@code http} only for
     * {@code localhost}, {@code 127.0.0.1} and {@code [::1]}. No user info, path (except {@code /}),
     * query or fragment.
     *
     * @param url the URL
     * @return the origin in lower case without a default port, or empty if the URL is not allowed
     */
    public static Optional<String> origin(String url) {
        if (url == null || url.length() > 255) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))) {
            return Optional.empty();
        }
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
        if (!scheme.equals("https") && !(scheme.equals("http") && loopback)) {
            return Optional.empty();
        }
        int port = uri.getPort();
        boolean defaultPort = port == -1 || (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80);
        return Optional.of(scheme + "://" + host + (defaultPort ? "" : ":" + port));
    }
}
