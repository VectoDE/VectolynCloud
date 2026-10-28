package net.vectora.cloud.template;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
 *       consent.</li>
 *   <li>{@link ServerPlatform#PANDASPIGOT}: {@code eula.txt} after consent.</li>
 *   <li>{@link ServerPlatform#FABRIC}: {@code config/floodgate/key.pem}, {@code eula.txt} after
 *       consent.</li>
 *   <li>{@link ServerPlatform#NONE}: nothing.</li>
 * </ul>
 *
 * <p>The Floodgate key comes from {@value GeneratedFiles#FLOODGATE_PLACEHOLDER}. Secrets are checked before
 * anything is written.
 */
public final class TemplateInstaller {

    private TemplateInstaller() {
    }

    /**
     * Renders {@code template} into {@code runtime} and writes the generated files.
     *
     * @param template template folder, e.g. {@code Server-Network/Proxy/template}
     * @param runtime runtime folder, e.g. {@code Server-Network/Proxy/runtime}; created if missing
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
        if (platform.floodgateKeyPath().isPresent()) {
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
        }
        if (platform.needsEula()) {
            GeneratedFiles.writeEula(runtime, eulaAccepted).ifPresent(generated::add);
        }
        generated.sort(null);
        return report.withGenerated(generated);
    }

    private static String required(PlaceholderValues values, String name, String file) throws TemplateException {
        return values.find(name).orElseThrow(() -> new TemplateException(file, name + " has no value"));
    }
}
