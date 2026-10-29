package net.vectora.cloud.setup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.ConfigService;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.model.NodeConfig;
import net.vectora.cloud.config.secret.EnvFile;
import net.vectora.cloud.config.secret.EnvFormatException;
import net.vectora.cloud.config.secret.GeneratedSecret;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.cloud.config.secret.SecretStore;
import net.vectora.cloud.console.ConsoleOutput;
import net.vectora.cloud.console.Text;
import net.vectora.cloud.console.prompt.InvalidAnswerException;
import net.vectora.cloud.console.prompt.PromptCancelledException;
import net.vectora.cloud.console.prompt.Prompter;
import net.vectora.cloud.console.prompt.Question;
import net.vectora.cloud.console.terminal.ConsoleTerminal;

/**
 * One run of the assistant on the console. The questions, in order:
 *
 * <ol>
 *   <li>language (de/en), shown at once;</li>
 *   <li>Minecraft EULA with link, only until it is accepted; declining ends the run;</li>
 *   <li>network name;</li>
 *   <li>the wanted switch of every server; the 1.8.8 server only with the legacy proxy, and each
 *       minigame group on its own once there are several;</li>
 *   <li>bind address, Java and Bedrock port (with the proxy), legacy port (with the legacy proxy);</li>
 *   <li>maximum heap of each wanted group, with the total;</li>
 *   <li>static, minimum and maximum services of each wanted server except the proxies, with the
 *       suggestion of {@link NetworkServer#defaultCounts()};</li>
 *   <li>database mode ({@code docker} hidden on Pterodactyl); for {@code external} host, port, name,
 *       user and password;</li>
 *   <li>summary with the files that change, then save, change or cancel.</li>
 * </ol>
 *
 * Nothing is written before "save". Cancelling at any question leaves every file as it was.
 */
final class InteractiveSetup {

    private final ConfigService config;
    private final SecretStore secrets;
    private final SetupEnvironment env;
    private final ConsoleTerminal terminal;
    private final LanguageOutput out;
    private final Prompter ask;
    private final Supplier<Optional<String>> groupList;

    InteractiveSetup(ConfigService config, SecretStore secrets, SetupEnvironment env, ConsoleTerminal terminal,
                     ConsoleOutput output, Supplier<Optional<String>> groupList) {
        this.config = Objects.requireNonNull(config, "config");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.env = Objects.requireNonNull(env, "env");
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.out = new LanguageOutput(output, env.language());
        this.ask = new Prompter(terminal, out);
        this.groupList = Objects.requireNonNull(groupList, "groupList");
    }

    /** Runs the questions and saves on request. */
    SetupResult run() throws IOException {
        ConfigSnapshot current = config.snapshot();
        boolean again = current.cloud().setupComplete();
        SetupAnswers answers = SetupPlan.defaults(current, env);
        out.set(answers.language());
        try {
            out.heading(SetupMessages.TITLE.get(out.language()));
            out.println(again ? SetupMessages.INTRO_AGAIN : SetupMessages.INTRO);
            while (true) {
                answers = questions(current, answers);
                if (answers == null) {
                    return SetupResult.of(SetupResult.Status.EULA_DECLINED);
                }
                current = config.snapshot();
                List<Text> problems = SetupChecks.check(current, answers, env, passwordStored());
                Set<String> files = new TreeSet<>();
                if (problems.isEmpty()) {
                    files.addAll(SetupPlan.changedFiles(current, SetupPlan.preview(current, answers)));
                    if (answers.databasePassword().isPresent() || !secrets.file().toFile().isFile()) {
                        files.add(CloudHome.SECRETS_DIR + "/" + CloudHome.SECRETS_FILE);
                    }
                }
                summary(answers, files);
                if (!problems.isEmpty()) {
                    out.error(SetupMessages.S_PROBLEMS);
                    for (Text problem : problems) {
                        out.error("  " + problem.get(out.language()));
                    }
                    if (ask.ask(Question.choice(SetupMessages.CONFIRM, List.of(
                            Question.option("change", SetupMessages.CONFIRM_CHANGE, Boolean.TRUE),
                            Question.option("cancel", SetupMessages.CONFIRM_CANCEL, Boolean.FALSE)))
                            .defaultValue(Boolean.TRUE))) {
                        continue;
                    }
                    out.warning(SetupMessages.CANCELLED);
                    return SetupResult.of(SetupResult.Status.CANCELLED);
                }
                if (again && files.isEmpty()) {
                    out.success(SetupMessages.S_UNCHANGED);
                    return SetupResult.of(SetupResult.Status.UNCHANGED);
                }
                String choice = ask.ask(Question.choice(SetupMessages.CONFIRM, List.of(
                        Question.option("save", SetupMessages.CONFIRM_SAVE, "save"),
                        Question.option("change", SetupMessages.CONFIRM_CHANGE, "change"),
                        Question.option("cancel", SetupMessages.CONFIRM_CANCEL, "cancel")))
                        .defaultValue("save"));
                switch (choice) {
                    case "change" -> {
                        continue;
                    }
                    case "cancel" -> {
                        out.warning(SetupMessages.CANCELLED);
                        return SetupResult.of(SetupResult.Status.CANCELLED);
                    }
                    default -> {
                        return save(answers);
                    }
                }
            }
        } catch (PromptCancelledException e) {
            out.warning(switch (e.reason()) {
                case END_OF_INPUT -> SetupMessages.INPUT_ENDED;
                case TOO_MANY_ATTEMPTS -> SetupMessages.TOO_MANY_ATTEMPTS;
                case CANCELLED -> SetupMessages.CANCELLED;
            });
            return SetupResult.of(SetupResult.Status.CANCELLED);
        } catch (ConfigException e) {
            // preview() of answers that passed the checks; only a hand edit at the same moment gets here.
            out.error(SetupMessages.S_PROBLEMS);
            for (var error : e.errors()) {
                out.error("  " + error.describe(out.language()));
            }
            return SetupResult.of(SetupResult.Status.CANCELLED);
        }
    }

    private SetupResult save(SetupAnswers answers) throws IOException {
        SetupResult result;
        try {
            result = SetupApplier.apply(config, secrets, answers, env);
        } catch (SetupException e) {
            out.error(SetupMessages.S_PROBLEMS);
            for (Text problem : e.problems()) {
                out.error("  " + problem.get(out.language()));
            }
            return SetupResult.of(SetupResult.Status.CANCELLED);
        } catch (IOException e) {
            out.error(SetupMessages.WRITE_FAILED, String.valueOf(e.getMessage()));
            throw e;
        }
        if (!result.files().isEmpty()) {
            out.success(SetupMessages.SAVED, String.join(", ", result.files()));
        }
        out.success(result.status() == SetupResult.Status.COMPLETED ? SetupMessages.COMPLETE : SetupMessages.CHANGED);
        if (result.status() != SetupResult.Status.COMPLETED) {
            out.println(SetupMessages.WANTED_STARTS_LATER);
        }
        if (secrets.file().toFile().isFile()) {
            out.println(SecretAccess.describe(secrets.file(), env.windows(), groupList).get(out.language()));
        }
        return result;
    }

    // ---- questions --------------------------------------------------------------------------------

    /** All questions once, with {@code answers} as defaults; null if the EULA was declined. */
    private SetupAnswers questions(ConfigSnapshot current, SetupAnswers answers) throws PromptCancelledException {
        Language language = ask.ask(Question.choice(SetupMessages.LANGUAGE, List.of(
                Question.option("de", SetupMessages.LANGUAGE_DE, Language.DE),
                Question.option("en", SetupMessages.LANGUAGE_EN, Language.EN))).defaultValue(answers.language()));
        out.set(language);
        answers = answers.withLanguage(language);

        if (!answers.eulaAccepted()) {
            boolean accepted = ask.ask(Question.yesNo(SetupMessages.EULA).help(SetupMessages.EULA_HELP));
            if (!accepted) {
                out.warning(SetupMessages.EULA_DECLINED);
                return null;
            }
            answers = answers.withEula(true);
        }

        answers = answers.withNetworkName(ask.ask(Question.text(SetupMessages.NETWORK_NAME)
                .defaultValue(answers.networkName()).validate(SetupChecks::networkName)));

        answers = servers(current, answers);
        answers = network(current, answers);
        answers = memory(current, answers);
        answers = services(current, answers);
        return database(current, answers);
    }

    /** Static, minimum and maximum services of every wanted server except the proxies. */
    private SetupAnswers services(ConfigSnapshot current, SetupAnswers answers) throws PromptCancelledException {
        out.heading(SetupMessages.SERVICES.get(out.language()));
        out.println(SetupMessages.SERVICES_HELP);
        for (NetworkServer server : NetworkServer.values()) {
            if (!answers.wants(server) || server.isProxy()) {
                continue;
            }
            for (String task : SetupPlan.serviceTasks(current, server)) {
                if (!answers.groupSwitches().getOrDefault(groupOf(current, task), Boolean.TRUE)) {
                    continue;
                }
                ServiceCounts fallback = answers.services().getOrDefault(task, server.defaultCounts());
                ServiceCounts counts = ask.ask(Question.custom(
                                SetupMessages.fill(SetupMessages.SERVICES_OF, label(server.shortName()), task),
                                InteractiveSetup::serviceCounts, ServiceCounts::toString)
                        .defaultValue(fallback).validate(ServiceCounts::problem));
                answers = answers.withServices(task, counts);
            }
        }
        return answers;
    }

    /** The group of an existing task, or the task name for one the assistant creates (same name). */
    private static String groupOf(ConfigSnapshot current, String task) {
        var existing = current.tasks().get(task);
        return existing == null ? task : existing.group();
    }

    private static ServiceCounts serviceCounts(String text) throws InvalidAnswerException {
        ServiceCounts counts = ServiceCounts.parse(text);
        if (counts == null) {
            throw new InvalidAnswerException(SetupMessages.CHECK_SERVICES);
        }
        return counts;
    }

    private SetupAnswers servers(ConfigSnapshot current, SetupAnswers answers) throws PromptCancelledException {
        out.heading(SetupMessages.SERVERS.get(out.language()));
        for (NetworkServer server : NetworkServer.values()) {
            if (server == NetworkServer.LEGACY_SERVER && !answers.wants(NetworkServer.LEGACY_PROXY)) {
                answers = answers.withServer(server, false);
                continue;
            }
            boolean wanted = ask.ask(Question.yesNo(SetupMessages.fill(SetupMessages.SERVER_WANTED, label(server)))
                    .defaultValue(answers.wants(server)));
            answers = answers.withServer(server, wanted);
            if (server == NetworkServer.PROXY && !wanted) {
                out.warning(SetupMessages.PROXY_OFF);
            }
            List<String> minigames = SetupPlan.groupsOf(current, NetworkServer.MINIGAME);
            if (server == NetworkServer.MINIGAME && wanted && minigames.size() > 1) {
                for (String group : minigames) {
                    boolean on = ask.ask(Question.yesNo(SetupMessages.fill(SetupMessages.MINIGAME_GROUP_WANTED, group))
                            .defaultValue(answers.groupSwitches().getOrDefault(group, Boolean.TRUE)));
                    answers = answers.withGroupSwitch(group, on);
                }
            }
        }
        return answers;
    }

    private SetupAnswers network(ConfigSnapshot current, SetupAnswers answers) throws PromptCancelledException {
        out.heading(SetupMessages.NETWORK.get(out.language()));
        if (env.pterodactyl()) {
            out.println(SetupMessages.PTERODACTYL_PORTS);
        }
        String bind = ask.ask(Question.text(SetupMessages.BIND).defaultValue(answers.bind()).validate(SetupChecks::host));
        Map<Integer, String> taken = reservedPorts(current);
        int javaPort = answers.javaPort();
        int bedrockPort = answers.bedrockPort();
        int legacyPort = answers.legacyPort();
        if (answers.wants(NetworkServer.PROXY)) {
            javaPort = port(SetupMessages.JAVA_PORT, javaPort, taken);
            taken.put(javaPort, "Java");
            bedrockPort = port(SetupMessages.BEDROCK_PORT, bedrockPort, taken);
            taken.put(bedrockPort, "Bedrock");
        }
        if (answers.wants(NetworkServer.LEGACY_PROXY)) {
            legacyPort = port(SetupMessages.LEGACY_PORT, legacyPort, taken);
        }
        return answers.withNetwork(bind, javaPort, bedrockPort, legacyPort);
    }

    private int port(Text prompt, int fallback, Map<Integer, String> taken) throws PromptCancelledException {
        String reserved = describe(taken);
        Question<Integer> question = Question.number(prompt, 1, 65535)
                .validate(port -> taken.containsKey(port) || inServiceRange(port)
                        ? SetupMessages.fill(SetupMessages.PORT_TAKEN, reserved) : null);
        if (!taken.containsKey(fallback) && !inServiceRange(fallback)) {
            question = question.defaultValue(fallback);
        }
        return ask.ask(question);
    }

    private Map<Integer, String> reservedPorts(ConfigSnapshot current) {
        Map<Integer, String> taken = new LinkedHashMap<>();
        node(current).ifPresent(node -> taken.put(node.port(), SetupMessages.PORT_CLOUD_CHANNEL.get(out.language())));
        return taken;
    }

    private boolean inServiceRange(int port) {
        return node(config.snapshot()).map(node -> node.servicePorts() != null && node.servicePorts().contains(port)).orElse(false);
    }

    private String describe(Map<Integer, String> taken) {
        List<String> parts = new ArrayList<>();
        taken.forEach((port, what) -> parts.add(what + " " + port));
        node(config.snapshot()).ifPresent(node -> {
            if (node.servicePorts() != null) {
                parts.add(SetupMessages.PORT_SERVICES.get(out.language()) + " " + node.servicePorts());
            }
        });
        return String.join(", ", parts);
    }

    private static Optional<NodeConfig> node(ConfigSnapshot snapshot) {
        return snapshot.nodes().find(snapshot.cloud().nodeId());
    }

    private SetupAnswers memory(ConfigSnapshot current, SetupAnswers answers) throws PromptCancelledException {
        out.heading(SetupMessages.MEMORY.get(out.language()));
        for (NetworkServer server : NetworkServer.values()) {
            if (!answers.wants(server)) {
                continue;
            }
            for (String group : SetupPlan.heapGroups(current, server)) {
                if (!answers.groupSwitches().getOrDefault(group, Boolean.TRUE)) {
                    continue;
                }
                MemorySize fallback = answers.heap().getOrDefault(group, server.defaultHeap());
                MemorySize size = ask.ask(Question.custom(
                                SetupMessages.fill(SetupMessages.MEMORY_OF, label(server.shortName()), group),
                                InteractiveSetup::memorySize, MemorySize::toString)
                        .defaultValue(fallback).validate(SetupChecks::heap));
                answers = answers.withHeap(group, size);
            }
        }
        long total = totalHeap(current, answers);
        if (total > 0) {
            out.println(SetupMessages.MEMORY_TOTAL, size(total));
            if (env.serverMemory().isPresent() && total > env.serverMemory().getAsLong()) {
                out.warning(SetupMessages.MEMORY_ABOVE_PTERODACTYL, size(env.serverMemory().getAsLong()));
            } else if (env.totalMemory().isPresent() && total > env.totalMemory().getAsLong()) {
                out.warning(SetupMessages.MEMORY_ABOVE_MACHINE, size(env.totalMemory().getAsLong()));
            }
        }
        return answers;
    }

    private static MemorySize memorySize(String text) throws InvalidAnswerException {
        try {
            return MemorySize.parse(text);
        } catch (IllegalArgumentException e) {
            throw new InvalidAnswerException(SetupMessages.CHECK_HEAP);
        }
    }

    /** Heap of one service of every wanted group, in megabytes. */
    static long totalHeap(ConfigSnapshot current, SetupAnswers answers) {
        long total = 0;
        for (NetworkServer server : NetworkServer.values()) {
            if (!answers.wants(server)) {
                continue;
            }
            for (String group : SetupPlan.heapGroups(current, server)) {
                if (answers.groupSwitches().getOrDefault(group, Boolean.TRUE)) {
                    total += answers.heap().getOrDefault(group, server.defaultHeap()).megabytes();
                }
            }
        }
        return total;
    }

    private SetupAnswers database(ConfigSnapshot current, SetupAnswers answers) throws PromptCancelledException {
        out.heading(SetupMessages.DATABASE.get(out.language()));
        List<Question.Option<DatabaseMode>> options = new ArrayList<>();
        options.add(Question.option("managed", env.managedDatabase() ? SetupMessages.MODE_MANAGED
                : SetupMessages.MODE_MANAGED_FALLBACK, DatabaseMode.MANAGED));
        if (!env.pterodactyl()) {
            options.add(Question.option("docker", SetupMessages.MODE_DOCKER, DatabaseMode.DOCKER));
        }
        options.add(Question.option("external", SetupMessages.MODE_EXTERNAL, DatabaseMode.EXTERNAL));
        options.add(Question.option("h2", SetupMessages.MODE_H2, DatabaseMode.H2));
        if (env.pterodactyl()) {
            out.println(SetupMessages.DOCKER_HIDDEN);
        }
        DatabaseConfig db = answers.database();
        Question<DatabaseMode> modeQuestion = Question.choice(SetupMessages.DATABASE_MODE, options);
        if (options.stream().anyMatch(o -> o.value() == db.mode())) {
            modeQuestion = modeQuestion.defaultValue(db.mode());
        }
        DatabaseMode mode = ask.ask(modeQuestion);
        if (mode == DatabaseMode.MANAGED) {
            out.println(SetupMessages.MANAGED_DATABASE_SWITCH);
        }
        DatabaseConfig chosen = mode == db.mode() ? db : SetupPlan.switchMode(db, mode);
        if (mode != DatabaseMode.EXTERNAL) {
            return answers.withDatabase(chosen, Optional.empty());
        }
        String host = ask.ask(Question.text(SetupMessages.DB_HOST).defaultValue(chosen.host()).validate(SetupChecks::host));
        int port = ask.ask(Question.number(SetupMessages.DB_PORT, 1, 65535).defaultValue(chosen.port()));
        String name = ask.ask(Question.text(SetupMessages.DB_NAME).defaultValue(chosen.name()).validate(SetupChecks::identifier));
        String user = ask.ask(Question.text(SetupMessages.DB_USER).defaultValue(chosen.user()).validate(SetupChecks::identifier));
        boolean wasExternal = current.cloud().database() != null && current.cloud().database().mode() == DatabaseMode.EXTERNAL;
        Optional<Secret> password = password(wasExternal, answers.databasePassword());
        return answers.withDatabase(new DatabaseConfig(DatabaseMode.EXTERNAL, host, port, name, user, null), password);
    }

    /**
     * The password of the external database user. Empty means "use what the file holds".
     *
     * <p>Where the console can hide input, it is a masked question; Enter keeps the stored value if
     * the database was external before. Where it cannot (JLine's dumb terminal, plain stdin), the
     * assistant first offers to put the password into {@code secrets/vectora.env} by hand and checks
     * the file afterwards, so the password never appears on the screen.
     */
    private Optional<Secret> password(boolean wasExternal, Optional<Secret> earlier) throws PromptCancelledException {
        Optional<Secret> stored = storedPassword();
        boolean canKeep = earlier.isPresent() || (wasExternal && stored.isPresent());
        Question<Secret> typed = Question.secret(SetupMessages.DB_PASSWORD)
                .validate(s -> !s.isEmpty() && SecretStore.isStorable(s) ? null : SetupMessages.CHECK_PASSWORD_FORM);
        Secret keep = Secret.of("");
        if (terminal.hidesSecrets()) {
            if (canKeep) {
                typed = typed.defaultValue(earlier.orElse(keep));
            }
            Secret answer = ask.ask(typed);
            return answer == keep ? Optional.empty() : Optional.of(answer);
        }
        out.warning(SetupMessages.PASSWORD_VISIBLE, secrets.file());
        List<Question.Option<String>> options = new ArrayList<>();
        options.add(Question.option("file", SetupMessages.PASSWORD_BY_FILE, "file"));
        options.add(Question.option("type", SetupMessages.PASSWORD_BY_TYPING, "type"));
        if (canKeep) {
            options.add(Question.option("keep", SetupMessages.PASSWORD_KEEP, "keep"));
        }
        String how = ask.ask(Question.choice(SetupMessages.PASSWORD_HOW, options).defaultValue(canKeep ? "keep" : "file"));
        switch (how) {
            case "keep" -> {
                return earlier;
            }
            case "type" -> {
                return Optional.of(ask.ask(typed));
            }
            default -> {
                for (int attempt = 1; attempt <= Prompter.MAX_ATTEMPTS; attempt++) {
                    ask.ask(Question.yesNo(SetupMessages.PASSWORD_FILE_SAVED).defaultValue(Boolean.TRUE));
                    Optional<Secret> now = storedPassword();
                    boolean fresh = now.isPresent() && (wasExternal || stored.isEmpty() || !now.get().equals(stored.get()));
                    if (fresh && SecretStore.isStorable(now.get())) {
                        out.success(SetupMessages.PASSWORD_FOUND);
                        return Optional.empty();
                    }
                    out.warning(SetupMessages.PASSWORD_STILL_MISSING);
                }
                throw new PromptCancelledException(PromptCancelledException.Reason.TOO_MANY_ATTEMPTS);
            }
        }
    }

    private Optional<Secret> storedPassword() {
        try {
            EnvFile file = secrets.load();
            return file.get(GeneratedSecret.DB_PASSWORD.key()).filter(s -> !s.isEmpty());
        } catch (EnvFormatException e) {
            out.error(SetupMessages.SECRETS_UNREADABLE, e.line());
            return Optional.empty();
        } catch (IOException e) {
            out.error(SetupMessages.SECRETS_ACCESS);
            return Optional.empty();
        }
    }

    private boolean passwordStored() {
        return storedPassword().isPresent();
    }

    // ---- summary ----------------------------------------------------------------------------------

    private void summary(SetupAnswers answers, Set<String> files) {
        Language lang = out.language();
        ConfigSnapshot current = config.snapshot();
        List<String> on = new ArrayList<>();
        List<String> off = new ArrayList<>();
        for (NetworkServer server : NetworkServer.values()) {
            (answers.wants(server) ? on : off).add(server.shortName().get(lang));
        }
        answers.groupSwitches().forEach((group, wanted) -> {
            if (!wanted && answers.wants(NetworkServer.MINIGAME)) {
                off.add(group);
            }
        });
        List<String> ports = new ArrayList<>();
        if (answers.wants(NetworkServer.PROXY)) {
            ports.add("Java " + answers.javaPort());
            ports.add("Bedrock " + answers.bedrockPort());
        }
        if (answers.wants(NetworkServer.LEGACY_PROXY)) {
            ports.add("Legacy " + answers.legacyPort());
        }
        List<String> memory = new ArrayList<>();
        for (NetworkServer server : NetworkServer.values()) {
            if (!answers.wants(server)) {
                continue;
            }
            for (String group : SetupPlan.heapGroups(current, server)) {
                if (answers.groupSwitches().getOrDefault(group, Boolean.TRUE)) {
                    memory.add(group + " " + answers.heap().getOrDefault(group, server.defaultHeap()));
                }
            }
        }
        long total = totalHeap(current, answers);
        List<String> counts = new ArrayList<>();
        for (NetworkServer server : NetworkServer.values()) {
            if (!answers.wants(server) || server.isProxy()) {
                continue;
            }
            for (String task : SetupPlan.serviceTasks(current, server)) {
                if (answers.groupSwitches().getOrDefault(groupOf(current, task), Boolean.TRUE)) {
                    counts.add(task + " " + answers.services().getOrDefault(task, server.defaultCounts()));
                }
            }
        }
        DatabaseConfig db = answers.database();
        String endpoint = db.host() + ":" + db.port();
        String database = switch (db.mode()) {
            case MANAGED -> SetupMessages.S_DB_MANAGED.format(lang, endpoint);
            case DOCKER -> SetupMessages.S_DB_DOCKER.format(lang, db.dockerImage(), endpoint);
            case EXTERNAL -> SetupMessages.S_DB_EXTERNAL.format(lang, endpoint, db.name(), db.user());
            case H2 -> SetupMessages.S_DB_H2.get(lang);
        };
        String secretsLine = SetupMessages.S_SECRETS_VALUE.get(lang)
                + (answers.databasePassword().isPresent() ? SetupMessages.S_PASSWORD_NEW.get(lang) : "");

        Map<Text, String> rows = new LinkedHashMap<>();
        rows.put(SetupMessages.S_LANGUAGE, lang == Language.DE ? "Deutsch" : "English");
        rows.put(SetupMessages.S_EULA, SetupMessages.S_ACCEPTED.get(lang));
        rows.put(SetupMessages.S_NETWORK, answers.networkName());
        rows.put(SetupMessages.S_WANTED, on.isEmpty() ? SetupMessages.S_NONE.get(lang) : String.join(", ", on));
        rows.put(SetupMessages.S_OFF, off.isEmpty() ? SetupMessages.S_NONE.get(lang) : String.join(", ", off));
        rows.put(SetupMessages.S_ADDRESS, answers.bind());
        rows.put(SetupMessages.S_PORTS, ports.isEmpty() ? SetupMessages.S_NONE.get(lang) : String.join(", ", ports));
        rows.put(SetupMessages.S_MEMORY, memory.isEmpty() ? SetupMessages.S_NONE.get(lang)
                : String.join(", ", memory) + " (" + size(total) + ")");
        rows.put(SetupMessages.S_SERVICES, counts.isEmpty() ? SetupMessages.S_NONE.get(lang)
                : String.join(", ", counts) + " " + SetupMessages.S_SERVICES_FORM.get(lang));
        rows.put(SetupMessages.S_DATABASE, database);
        rows.put(SetupMessages.S_SECRETS, secretsLine);
        rows.put(SetupMessages.S_FILES, files.isEmpty() ? SetupMessages.S_NONE.get(lang) : String.join(", ", files));

        out.heading(SetupMessages.SUMMARY.get(lang));
        int width = rows.keySet().stream().mapToInt(t -> t.get(lang).length()).max().orElse(0);
        rows.forEach((label, value) -> out.println("  " + pad(label.get(lang), width) + "  " + printable(value)));
    }

    /** {@code text} with control characters replaced, for values that came from a file. */
    static String printable(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            result.append(c < 0x20 || c == 0x7f || (c >= 0x80 && c < 0xa0) ? '?' : c);
        }
        return result.toString();
    }

    /** Megabytes as {@code 512M} or {@code 2G}. */
    static String size(long megabytes) {
        return megabytes % 1024 == 0 ? megabytes / 1024 + "G" : megabytes + "M";
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    private String label(NetworkServer server) {
        return server.label().get(out.language());
    }

    private String label(Text text) {
        return text.get(out.language());
    }
}
