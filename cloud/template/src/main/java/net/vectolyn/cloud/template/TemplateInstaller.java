package net.vectolyn.cloud.template;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Creates or refreshes the runtime folder of one server: renders its template and writes the
 * generated files its platform needs.
 *
 * <ul>
 *   <li>{@link ServerPlatform#VELOCITY}: {@code forwarding.secret} from
 *       {@value GeneratedFiles#FORWARDING_PLACEHOLDER}, {@code plugins/floodgate/key.pem}.</li>
 *   <li>{@link ServerPlatform#VELOCITY_LEGACY}: {@code forwarding.secret} from
 *       {@value GeneratedFiles#LEGACY_FORWARDING_PLACEHOLDER}, which must differ from
 *       {@value GeneratedFiles#FORWARDING_PLACEHOLDER}.</li>
 *   <li>{@link ServerPlatform#PAPER}: {@code plugins/floodgate/key.pem}, {@code eula.txt} after
 *       consent, {@code spigot.yml} with Spigot's restart turned off.</li>
 *   <li>{@link ServerPlatform#PANDASPIGOT}: {@code eula.txt} after consent, {@code spigot.yml} with
 *       Spigot's restart turned off.</li>
 *   <li>{@link ServerPlatform#FABRIC}: {@code config/floodgate/key.pem}, {@code eula.txt} after
 *       consent.</li>
 *   <li>{@link ServerPlatform#NONE}: nothing.</li>
 * </ul>
 *
 * <p>The Floodgate key comes from {@value GeneratedFiles#FLOODGATE_PLACEHOLDER} and is only written
 * for a template that ships Floodgate's folder ({@link #runsFloodgate}). Secrets are checked before
 * anything is written.
 *
 * <p>For Paper and PandaSpigot the node always sets {@code restart-script: ''} and
 * {@code restart-on-crash: true} in the runtime {@code spigot.yml}, whatever the template says:
 * otherwise the server would start a second, unmanaged server on {@code /restart} or after a hang,
 * or (PandaSpigot with {@code false}) stay hung instead of stopping.
 * {@code spigot.yml} is listed as generated only when the node created or changed it.
 *
 * <p>For Paper, PandaSpigot and Fabric the node always sets {@code enable-command-block=false} in
 * the runtime {@code server.properties} ({@link CommandBlockSetting}), with no setting to turn this
 * off. Another value is overwritten and reported in {@link RenderReport#warnings()};
 * {@code server.properties} is listed as generated only when the node created or changed it.
 */
public final class TemplateInstaller {

    /** The warning when the template or an operator had turned command blocks on (or set an odd value). */
    public static final String COMMAND_BLOCK_WARNING = CommandBlockSetting.FILE + " had " + CommandBlockSetting.KEY
            + " set to another value than false; VectolynCloud always writes " + CommandBlockSetting.LINE
            + " (command blocks are removed network-wide and this cannot be switched off)";

    private TemplateInstaller() {
    }

    /**
     * Renders {@code template} into {@code runtime} and writes the generated files.
     *
     * @param template template folder, e.g. {@code <home>/Proxy/template}
     * @param runtime runtime folder, e.g. {@code <home>/Proxy/runtime}; created if missing
     * @param platform server software of the template
     * @param values placeholder values, including the secrets the platform needs
     * @param eulaAccepted whether the operator explicitly accepted the Minecraft EULA
     * @return what was rendered, copied, skipped and generated
     * @throws TemplateException if a placeholder or secret is missing or invalid, or a path is unsafe
     * @throws IOException if reading the template or writing the runtime folder fails
     */
    public static RenderReport install(Path template, Path runtime, ServerPlatform platform, PlaceholderValues values,
            boolean eulaAccepted) throws TemplateException, IOException {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(values, "values");
        String forwardingSecret = null;
        if (platform.forwardingPlaceholder().isPresent()) {
            String placeholder = platform.forwardingPlaceholder().get();
            forwardingSecret = required(values, placeholder, GeneratedFiles.FORWARDING_SECRET_FILE);
            GeneratedFiles.checkForwardingSecret(placeholder, forwardingSecret);
            if (!placeholder.equals(GeneratedFiles.FORWARDING_PLACEHOLDER)
                    && values.find(GeneratedFiles.FORWARDING_PLACEHOLDER).filter(forwardingSecret::equals).isPresent()) {
                // bungeeguard sends the secret in plain text to the backends; reusing the modern one
                // would hand it to every 1.8.8 server.
                throw new TemplateException(GeneratedFiles.FORWARDING_SECRET_FILE, placeholder + " equals "
                        + GeneratedFiles.FORWARDING_PLACEHOLDER + "; the legacy proxy needs a secret of its own");
            }
        }
        String floodgateKey = null;
        if (runsFloodgate(template, platform)) {
            String path = platform.floodgateKeyPath().get();
            floodgateKey = required(values, GeneratedFiles.FLOODGATE_PLACEHOLDER, path);
            GeneratedFiles.decodeFloodgateKey(path, floodgateKey);
        }

        RenderReport report = TemplateRenderer.render(template, runtime, values);

        List<String> generated = new ArrayList<>();
        if (forwardingSecret != null) {
            generated.add(GeneratedFiles.writeForwardingSecret(runtime, forwardingSecret));
        }
        if (floodgateKey != null) {
            generated.add(GeneratedFiles.writeFloodgateKey(runtime, platform, floodgateKey));
        } else if (platform.floodgateKeyPath().isPresent()) {
            removeStaleFloodgateKey(runtime, platform.floodgateKeyPath().get());
        }
        if (platform.needsEula()) {
            GeneratedFiles.writeEula(runtime, eulaAccepted).ifPresent(generated::add);
        }
        if (platform.readsSpigotYml()) {
            SpigotRestart.enforce(runtime).ifPresent(generated::add);
        }
        List<String> warnings = new ArrayList<>();
        if (platform.readsServerProperties()) {
            CommandBlockSetting.Outcome outcome = CommandBlockSetting.enforce(runtime);
            if (outcome.changed()) {
                generated.add(CommandBlockSetting.FILE);
            }
            if (outcome.overwritten()) {
                warnings.add(COMMAND_BLOCK_WARNING);
            }
        }
        generated.sort(null);
        return report.withGenerated(generated, warnings);
    }

    /**
     * Whether a template runs Floodgate: its platform can (it has a key path) and the template ships
     * Floodgate's folder there ({@code plugins/floodgate/} or {@code config/floodgate/}, a real folder,
     * not a link). Only then does the runtime folder get the key; a custom template without
     * Floodgate never holds it, and a key of an earlier start is deleted.
     *
     * @param template the template folder
     * @param platform its platform
     * @return whether {@code key.pem} is written
     */
    public static boolean runsFloodgate(Path template, ServerPlatform platform) {
        Optional<String> key = platform.floodgateKeyPath();
        if (key.isEmpty()) {
            return false;
        }
        Path folder = template.resolve(key.get()).getParent();
        return folder != null && Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Deletes the key an earlier start wrote, once the template no longer ships Floodgate, so the
     * runtime folder does not keep the secret. Only a regular file reached without a link is
     * deleted; anything else at that path was not written by the cloud and stays.
     */
    private static void removeStaleFloodgateKey(Path runtime, String path) throws IOException {
        Path target;
        try {
            target = RuntimePaths.check(runtime, path);
        } catch (TemplateException e) {
            return;
        }
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(target);
        }
    }

    private static String required(PlaceholderValues values, String name, String file) throws TemplateException {
        return values.find(name).orElseThrow(() -> new TemplateException(file, name + " has no value"));
    }
}
