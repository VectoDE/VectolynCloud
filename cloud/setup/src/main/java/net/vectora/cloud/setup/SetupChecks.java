package net.vectora.cloud.setup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.vectora.cloud.config.error.ConfigError;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.validation.ConfigNames;
import net.vectora.cloud.config.validation.ConfigValidator;
import net.vectora.cloud.console.Text;

/**
 * The checks every set of answers goes through, from the console and from a setup file alike. The
 * single-value checks are also what the questions use, so a wrong answer is caught right where it is
 * typed. After them {@link ConfigValidator} checks the resulting configuration as a whole, exactly
 * as for a hand-edited file.
 *
 * <p>Messages never contain the value that was wrong.
 */
public final class SetupChecks {

    private SetupChecks() {
    }

    /** A network name for {@code cloud.yml}, or a problem. */
    public static Text networkName(String value) {
        return value != null && value.matches(ConfigNames.NETWORK_NAME) ? null : SetupMessages.CHECK_NETWORK_NAME;
    }

    /** A bind address or host name, or a problem. */
    public static Text host(String value) {
        return value != null && value.matches(ConfigNames.HOST) ? null : SetupMessages.CHECK_HOST;
    }

    /** A database or user name, or a problem. */
    public static Text identifier(String value) {
        return value != null && value.matches(ConfigNames.DATABASE_IDENTIFIER) ? null : SetupMessages.CHECK_IDENTIFIER;
    }

    /** A port, or a problem. */
    public static Text port(int value) {
        return value >= ConfigValidator.MIN_PORT && value <= ConfigValidator.MAX_PORT ? null : SetupMessages.CHECK_PORT;
    }

    /** A heap size for one service, or a problem. */
    public static Text heap(MemorySize value) {
        return value != null && value.megabytes() >= SetupPlan.MIN_HEAP.megabytes() ? null : SetupMessages.CHECK_HEAP;
    }

    /**
     * Everything that is wrong with {@code answers} on top of {@code current}: the single values,
     * the rules between them, and every error {@link ConfigValidator} finds in the result.
     *
     * @param passwordStored whether {@code secrets/vectora.env} already holds {@code VECTORA_DB_PASSWORD}
     * @return the problems, empty if the answers can be written
     */
    public static List<Text> check(ConfigSnapshot current, SetupAnswers answers, SetupEnvironment env, boolean passwordStored) {
        List<Text> problems = new ArrayList<>();
        add(problems, networkName(answers.networkName()));
        add(problems, host(answers.bind()));
        add(problems, port(answers.javaPort()));
        add(problems, port(answers.bedrockPort()));
        add(problems, port(answers.legacyPort()));
        Map<Integer, String> ports = new LinkedHashMap<>();
        if (answers.wants(NetworkServer.PROXY)) {
            ports.put(answers.javaPort(), "java");
            // Geyser's NetherNet takes the port after the Bedrock port (ProxyConfig.netherNetPort).
            if (ports.putIfAbsent(answers.bedrockPort(), "bedrock") != null
                    || ports.putIfAbsent(answers.bedrockPort() + 1, "nethernet") != null) {
                problems.add(SetupMessages.CHECK_PORTS_DIFFER);
            }
        }
        if (answers.wants(NetworkServer.LEGACY_PROXY) && ports.putIfAbsent(answers.legacyPort(), "legacy") != null) {
            problems.add(SetupMessages.CHECK_PORTS_DIFFER);
        }
        if (answers.wants(NetworkServer.LEGACY_SERVER) && !answers.wants(NetworkServer.LEGACY_PROXY)) {
            problems.add(SetupMessages.CHECK_LEGACY_NEEDS_PROXY);
        }
        answers.heap().forEach((group, size) -> {
            if (heap(size) != null) {
                problems.add(SetupMessages.fill(SetupMessages.CHECK_HEAP_OF, group));
            }
        });
        answers.services().forEach((task, counts) -> {
            if (counts.problem() != null) {
                problems.add(SetupMessages.fill(SetupMessages.CHECK_SERVICES_OF, ConfigNames.safeArgument(task)));
            }
        });
        answers.groupSwitches().keySet().forEach(group -> {
            if (!current.groups().containsKey(group)) {
                problems.add(SetupMessages.fill(SetupMessages.CHECK_UNKNOWN_GROUP, ConfigNames.safeArgument(group)));
            }
        });
        DatabaseConfig db = answers.database();
        if (db.mode() == DatabaseMode.DOCKER && env.pterodactyl()) {
            problems.add(SetupMessages.CHECK_DOCKER_ON_PTERODACTYL);
        }
        if (db.mode() == DatabaseMode.EXTERNAL) {
            add(problems, host(db.host()));
            add(problems, port(db.port()));
            add(problems, identifier(db.name()));
            add(problems, identifier(db.user()));
            if (answers.databasePassword().isEmpty() && !passwordStored) {
                problems.add(SetupMessages.CHECK_PASSWORD_MISSING);
            }
        }
        if (problems.isEmpty()) {
            try {
                ConfigSnapshot after = SetupPlan.preview(current, answers);
                for (ConfigError error : ConfigValidator.validate(after)) {
                    problems.add(Text.of(error.describe(Language.EN), error.describe(Language.DE)));
                }
            } catch (ConfigException e) {
                for (ConfigError error : e.errors()) {
                    problems.add(Text.of(error.describe(Language.EN), error.describe(Language.DE)));
                }
            }
        }
        return List.copyOf(problems);
    }

    private static void add(List<Text> problems, Text problem) {
        if (problem != null && !problems.contains(problem)) {
            problems.add(problem);
        }
    }
}
