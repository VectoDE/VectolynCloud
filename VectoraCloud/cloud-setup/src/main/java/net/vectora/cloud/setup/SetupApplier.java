package net.vectora.cloud.setup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.ConfigChanged;
import net.vectora.cloud.config.ConfigService;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.operation.ConfigOperation;
import net.vectora.cloud.config.secret.EnvFormatException;
import net.vectora.cloud.config.secret.GeneratedSecret;
import net.vectora.cloud.config.secret.SecretGenerator;
import net.vectora.cloud.config.secret.SecretStore;
import net.vectora.cloud.console.Text;

/**
 * Writes a set of answers, the same way for the console and for a setup file.
 *
 * <ol>
 *   <li>Check everything ({@link SetupChecks}). On a problem nothing is written.</li>
 *   <li>Secrets: the password of an external database if a new one was given, then every generated
 *       secret that is still missing. Existing generated secrets are never replaced.</li>
 *   <li>The configuration through {@link ConfigService#apply}: one change, validated as a whole;
 *       only files whose content differs are written.</li>
 *   <li>Last, in a change of its own, {@code eula-accepted} and {@code setup-complete}. If anything
 *       before fails, the setup stays open and runs again on the next start.</li>
 * </ol>
 */
final class SetupApplier {

    /** Comment above a new {@code VECTORA_DB_PASSWORD} line. */
    static final String PASSWORD_COMMENT = "Password of the database user. Set by the setup assistant for an external database";

    private SetupApplier() {
    }

    /**
     * Writes {@code answers}.
     *
     * @return {@link SetupResult.Status#COMPLETED} on the first setup, otherwise
     *     {@link SetupResult.Status#CHANGED} or {@link SetupResult.Status#UNCHANGED}
     * @throws SetupException if a check fails; nothing was written
     * @throws IOException    if a file cannot be written
     */
    static SetupResult apply(ConfigService config, SecretStore secrets, SetupAnswers answers, SetupEnvironment env)
            throws SetupException, IOException {
        ConfigSnapshot current = config.snapshot();
        boolean first = !current.cloud().setupComplete();
        boolean stored;
        try {
            stored = secrets.load().hasValue(GeneratedSecret.DB_PASSWORD.key());
        } catch (EnvFormatException e) {
            throw new SetupException(List.of(SetupMessages.fill(SetupMessages.SECRETS_UNREADABLE, e.line())));
        }
        List<Text> problems = new ArrayList<>();
        if (!answers.eulaAccepted()) {
            problems.add(SetupMessages.FILE_EULA);
        }
        problems.addAll(SetupChecks.check(current, answers, env, stored));
        if (!problems.isEmpty()) {
            throw new SetupException(problems);
        }
        Set<String> files = new TreeSet<>();
        String secretsFile = CloudHome.SECRETS_DIR + "/" + CloudHome.SECRETS_FILE;
        try {
            if (answers.databasePassword().isPresent()
                    && secrets.put(GeneratedSecret.DB_PASSWORD.key(), answers.databasePassword().get(), PASSWORD_COMMENT)) {
                files.add(secretsFile);
            }
            if (!secrets.ensureGenerated(new SecretGenerator()).isEmpty()) {
                files.add(secretsFile);
            }
        } catch (EnvFormatException e) {
            throw new SetupException(List.of(SetupMessages.fill(SetupMessages.SECRETS_UNREADABLE, e.line())));
        }
        try {
            List<ConfigOperation> ops = SetupPlan.operations(current, answers);
            if (!ops.isEmpty()) {
                ConfigChanged change = config.apply(ops);
                files.addAll(change.files());
            }
            List<ConfigOperation> finish = SetupPlan.finish(config.snapshot());
            if (!finish.isEmpty()) {
                files.addAll(config.apply(finish).files());
            }
        } catch (ConfigException e) {
            // The files changed between the check and the write (a hand edit in between).
            throw SetupException.of(e);
        }
        if (first) {
            return new SetupResult(SetupResult.Status.COMPLETED, files);
        }
        return new SetupResult(files.isEmpty() ? SetupResult.Status.UNCHANGED : SetupResult.Status.CHANGED, files);
    }
}
