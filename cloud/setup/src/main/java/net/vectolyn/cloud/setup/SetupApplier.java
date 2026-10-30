package net.vectolyn.cloud.setup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.ConfigChanged;
import net.vectolyn.cloud.config.ConfigService;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.operation.ConfigOperation;
import net.vectolyn.cloud.config.secret.EnvFormatException;
import net.vectolyn.cloud.config.secret.GeneratedSecret;
import net.vectolyn.cloud.config.secret.Secret;
import net.vectolyn.cloud.config.secret.SecretGenerator;
import net.vectolyn.cloud.config.secret.SecretStore;
import net.vectolyn.cloud.console.Text;

/**
 * Writes a set of answers, the same way for the console and for a setup file.
 *
 * <ol>
 *   <li>Check everything ({@link SetupChecks}). On a problem nothing is written.</li>
 *   <li>The configuration through {@link ConfigService#apply}: one change, validated as a whole;
 *       only files whose content differs are written. If it fails, nothing is written at all.</li>
 *   <li>Only then the secrets: every generated secret that is still missing, then the password of an
 *       external database if a new one was given. Existing generated secrets are never replaced.</li>
 *   <li>Last, in a change of its own, {@code eula-accepted} and {@code setup-complete}. If step 2 or
 *       3 fails, the configuration and the password go back to what they were before, and the
 *       setup stays open and runs again on the next start.</li>
 * </ol>
 */
final class SetupApplier {

    /** Comment above a new {@code VECTOLYN_DB_PASSWORD} line. */
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
        return apply(config, secrets, answers, null, env);
    }

    /**
     * Writes {@code answers}, merged with the files as they are now: a setting the operator left as
     * {@code shown} keeps what the files hold at this moment ({@link SetupPlan#rebase}), so a change
     * made through the API or the console while the assistant ran is not reset.
     *
     * @param shown the defaults the assistant showed, or null to write {@code answers} as they are
     * @return as {@link #apply(ConfigService, SecretStore, SetupAnswers, SetupEnvironment)}
     * @throws SetupException if a check fails; nothing was written
     * @throws IOException    if a file cannot be written
     */
    static SetupResult apply(ConfigService config, SecretStore secrets, SetupAnswers answered, SetupAnswers shown,
                             SetupEnvironment env) throws SetupException, IOException {
        ConfigSnapshot current = config.snapshot();
        SetupAnswers answers = shown == null ? answered : SetupPlan.rebase(answered, shown, SetupPlan.defaults(current, env));
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
        Optional<Secret> previousPassword;
        try {
            previousPassword = secrets.load().get(GeneratedSecret.DB_PASSWORD.key());
        } catch (EnvFormatException e) {
            throw new SetupException(List.of(SetupMessages.fill(SetupMessages.SECRETS_UNREADABLE, e.line())));
        }
        // 1. The configuration. If it fails, nothing at all has been written yet.
        boolean configWritten = false;
        try {
            List<ConfigOperation> ops = SetupPlan.operations(current, answers);
            if (!ops.isEmpty()) {
                ConfigChanged change = config.apply(ops);
                files.addAll(change.files());
                configWritten = !change.files().isEmpty();
            }
        } catch (ConfigException e) {
            // The files changed between the check and the write (a hand edit in between).
            throw SetupException.of(e);
        }
        // 2. Secrets, only now: a password must never go with a host and user that were not saved.
        //    3. Then eula-accepted and setup-complete. A failure in 2 or 3 rolls 1 and the password back.
        boolean passwordWritten = false;
        try {
            if (!secrets.ensureGenerated(new SecretGenerator()).isEmpty()) {
                files.add(secretsFile);
                // A password that was missing was generated just now; a rollback goes back to that
                // one, not to the operator's external password.
                previousPassword = secrets.load().get(GeneratedSecret.DB_PASSWORD.key());
            }
            if (answers.databasePassword().isPresent()
                    && secrets.put(GeneratedSecret.DB_PASSWORD.key(), answers.databasePassword().get(), PASSWORD_COMMENT)) {
                passwordWritten = true;
                files.add(secretsFile);
            }
            List<ConfigOperation> finish = SetupPlan.finish(config.snapshot());
            if (!finish.isEmpty()) {
                files.addAll(config.apply(finish).files());
            }
        } catch (IOException | EnvFormatException | ConfigException | RuntimeException e) {
            rollBack(config, configWritten ? current : null, secrets, passwordWritten ? previousPassword : null, e);
            if (e instanceof EnvFormatException format) {
                throw new SetupException(List.of(SetupMessages.fill(SetupMessages.SECRETS_UNREADABLE, format.line())));
            }
            if (e instanceof ConfigException configProblem) {
                throw SetupException.of(configProblem);
            }
            if (e instanceof IOException io) {
                throw io;
            }
            throw (RuntimeException) e;
        }
        if (first) {
            return new SetupResult(SetupResult.Status.COMPLETED, files);
        }
        return new SetupResult(files.isEmpty() ? SetupResult.Status.UNCHANGED : SetupResult.Status.CHANGED, files);
    }

    /**
     * Undoes a half-written setup: the configuration goes back to {@code before} (in one change, so
     * groups and tasks the setup created are removed again), and a password the setup wrote goes back
     * to the one stored before. Generated secrets that were missing stay; they never replace a value
     * and the node creates them on every start anyway. A failure here is attached to {@code cause}.
     *
     * @param before           the configuration before the setup, or null if it was not changed
     * @param previousPassword the password before, or null if the setup did not write one; empty if
     *                         there was none (it cannot be removed, but the setup stays open then)
     */
    private static void rollBack(ConfigService config, ConfigSnapshot before, SecretStore secrets,
                                 Optional<Secret> previousPassword, Exception cause) {
        if (before != null) {
            try {
                config.apply(List.<ConfigOperation>of(snapshot -> before));
            } catch (IOException | ConfigException | RuntimeException e) {
                cause.addSuppressed(e);
            }
        }
        if (previousPassword != null && previousPassword.isPresent()) {
            try {
                secrets.put(GeneratedSecret.DB_PASSWORD.key(), previousPassword.get(), PASSWORD_COMMENT);
            } catch (IOException | EnvFormatException | RuntimeException e) {
                cause.addSuppressed(e);
            }
        }
    }
}
