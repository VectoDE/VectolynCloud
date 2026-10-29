package net.vectora.cloud.monitor.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import net.vectora.cloud.config.error.ConfigError;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.error.ErrorCode;
import net.vectora.cloud.config.io.ConfigFormat;
import net.vectora.cloud.config.io.ConfigMappers;
import net.vectora.cloud.config.io.ConfigYaml;
import net.vectora.cloud.config.io.RegularPaths;
import net.vectora.cloud.config.model.TimeSpan;
import net.vectora.cloud.monitor.alert.AlertRule;
import net.vectora.cloud.monitor.alert.RuleParser;
import net.vectora.cloud.monitor.alert.RuleSyntaxException;

/**
 * Reads and checks {@code monitor.yml}. Uses the same strict YAML reader as the other configuration
 * files (unknown fields, duplicate keys and type guessing fail), and reports every problem it finds
 * as {@link ConfigError} with file, field path and code. Error messages never quote a value.
 */
public final class MonitorConfigLoader {

    /** File name in the cloud home. */
    public static final String FILE = "monitor.yml";

    /** Most rules in one file. */
    public static final int MAX_RULES = 100;

    private static final ConfigFormat<MonitorConfig> FORMAT =
            new ConfigFormat<>(MonitorConfig.class, MonitorConfig.SCHEMA_VERSION, List.of());
    private static final Pattern ENV_KEY = Pattern.compile("[A-Z0-9_]{1,128}");
    private static final Pattern IPV4 = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private MonitorConfigLoader() {
    }

    /**
     * A checked configuration with its rules parsed.
     *
     * @param config         the file's content with defaults filled in
     * @param rules          the enabled rules
     * @param prometheusBind address of the Prometheus endpoint (a loopback address)
     */
    public record Settings(MonitorConfig config, List<AlertRule> rules, InetAddress prometheusBind) {

        /** Checks the values. */
        public Settings {
            Objects.requireNonNull(config, "config");
            rules = List.copyOf(rules);
            Objects.requireNonNull(prometheusBind, "prometheusBind");
        }
    }

    /**
     * Reads {@code <home>/monitor.yml}; a missing file gives the defaults.
     *
     * @param home cloud home
     * @return the checked settings
     * @throws ConfigException if the file is unreadable, not a regular file or invalid
     */
    public static Settings load(Path home) throws ConfigException {
        Path file = home.resolve(FILE);
        byte[] content;
        try {
            RegularPaths.requireFileOrMissing(file);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return check(MonitorConfig.defaults());
            }
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                content = in.readNBytes(ConfigMappers.MAX_FILE_BYTES + 1);
            }
        } catch (NoSuchFileException e) {
            return check(MonitorConfig.defaults());
        } catch (IOException e) {
            throw new ConfigException(ConfigError.of(FILE, "", ErrorCode.FILE_ACCESS));
        }
        return parse(content);
    }

    /**
     * Parses and checks the content of {@code monitor.yml}.
     *
     * @param content raw bytes (UTF-8)
     * @return the checked settings
     * @throws ConfigException if anything is invalid
     */
    public static Settings parse(byte[] content) throws ConfigException {
        return check(ConfigYaml.read(FILE, content, FORMAT).value());
    }

    /**
     * Checks a configuration and parses its rules.
     *
     * @param config the configuration
     * @return the settings
     * @throws ConfigException with every problem found
     */
    public static Settings check(MonitorConfig config) throws ConfigException {
        List<ConfigError> errors = new ArrayList<>();
        range(errors, "sample-interval", config.sampleInterval(), Duration.ofSeconds(1), Duration.ofMinutes(5));
        if (config.staleAfter().duration().compareTo(config.sampleInterval().duration()) < 0) {
            errors.add(ConfigError.of(FILE, "stale-after", ErrorCode.INVALID_VALUE, "at least sample-interval"));
        }
        range(errors, "stale-after", config.staleAfter(), Duration.ofSeconds(1), Duration.ofHours(1));

        MonitorConfig.History history = config.history();
        if (history.samples() < 10 || history.samples() > 100_000) {
            errors.add(ConfigError.of(FILE, "history.samples", ErrorCode.OUT_OF_RANGE, 10, 100_000));
        }
        range(errors, "history.retention", history.retention(), Duration.ofMinutes(1), Duration.ofDays(7));
        range(errors, "history.persist-interval", history.persistInterval(), Duration.ofMinutes(1), Duration.ofHours(1));

        List<AlertRule> rules = new ArrayList<>();
        if (config.rules().size() > MAX_RULES) {
            errors.add(ConfigError.of(FILE, "rules", ErrorCode.OUT_OF_RANGE, 0, MAX_RULES));
        }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < config.rules().size(); i++) {
            MonitorConfig.Rule rule = config.rules().get(i);
            String at = "rules[" + i + "]";
            boolean nameValid = rule.name() != null && AlertRule.NAME.matcher(rule.name()).matches();
            if (!nameValid) {
                errors.add(ConfigError.of(FILE, at + ".name", ErrorCode.PATTERN_MISMATCH, AlertRule.NAME.pattern()));
            } else if (!names.add(rule.name())) {
                errors.add(ConfigError.of(FILE, at + ".name", ErrorCode.DUPLICATE_ENTRY, rule.name()));
            }
            boolean groupsValid = true;
            for (int g = 0; g < rule.groups().size(); g++) {
                String group = rule.groups().get(g);
                if (group == null || !AlertRule.NAME.matcher(group).matches()) {
                    errors.add(ConfigError.of(FILE, at + ".groups[" + g + "]", ErrorCode.PATTERN_MISMATCH,
                            AlertRule.NAME.pattern()));
                    groupsValid = false;
                }
            }
            try {
                var condition = RuleParser.parse(rule.when());
                if (nameValid && groupsValid && rule.enabled()) {
                    rules.add(new AlertRule(rule.name(), condition, rule.severity(), Set.copyOf(rule.groups())));
                }
            } catch (RuleSyntaxException e) {
                errors.add(ConfigError.of(FILE, at + ".when", ErrorCode.INVALID_VALUE, e.getMessage()));
            }
        }

        MonitorConfig.Webhook webhook = config.notifications().webhook();
        if (!ENV_KEY.matcher(webhook.urlSecret()).matches()) {
            errors.add(ConfigError.of(FILE, "notifications.webhook.url-secret", ErrorCode.PATTERN_MISMATCH,
                    ENV_KEY.pattern()));
        }
        range(errors, "notifications.webhook.timeout", webhook.timeout(), Duration.ofSeconds(1), Duration.ofSeconds(60));

        MonitorConfig.Prometheus prometheus = config.prometheus();
        if (prometheus.port() < 1 || prometheus.port() > 65535) {
            errors.add(ConfigError.of(FILE, "prometheus.port", ErrorCode.OUT_OF_RANGE, 1, 65535));
        }
        InetAddress bind = loopback(prometheus.bind());
        if (bind == null) {
            errors.add(ConfigError.of(FILE, "prometheus.bind", ErrorCode.INVALID_VALUE,
                    "a loopback address such as 127.0.0.1, ::1 or localhost"));
            bind = InetAddress.getLoopbackAddress();
        }
        if (!errors.isEmpty()) {
            throw new ConfigException(errors);
        }
        return new Settings(config, rules, bind);
    }

    /** The address if {@code text} is {@code localhost} or a loopback IP literal; null otherwise. No DNS lookups. */
    static InetAddress loopback(String text) {
        if (text == null) {
            return null;
        }
        if (text.equals("localhost")) {
            return InetAddress.getLoopbackAddress();
        }
        boolean ipv4 = IPV4.matcher(text).matches();
        if (ipv4) {
            for (String part : text.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    // Not a literal; InetAddress would ask DNS for it.
                    return null;
                }
            }
        } else if (text.indexOf(':') < 0 || !IPV6.matcher(text).matches()) {
            return null;
        }
        try {
            InetAddress address = InetAddress.getByName(text);
            return address.isLoopbackAddress() ? address : null;
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static void range(List<ConfigError> errors, String path, TimeSpan value, Duration min, Duration max) {
        Duration d = value.duration();
        if (d.compareTo(min) < 0 || d.compareTo(max) > 0) {
            errors.add(ConfigError.of(FILE, path, ErrorCode.OUT_OF_RANGE, new TimeSpan(min), new TimeSpan(max)));
        }
    }
}
