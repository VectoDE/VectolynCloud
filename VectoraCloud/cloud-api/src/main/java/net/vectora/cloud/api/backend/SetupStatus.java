package net.vectora.cloud.api.backend;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.List;
import net.vectora.cloud.config.model.ConfigSnapshot;

/**
 * Where the first setup stands, for {@code GET /api/v1/setup}. The dashboard (stage 8) uses it to
 * lead through the setup; it finishes the setup with {@code PUT /api/v1/config}, which runs the
 * same validation as the setup assistant ({@code setup-complete} needs {@code eula-accepted}).
 *
 * @param eulaAccepted  {@code eula-accepted} of {@code cloud.yml}
 * @param setupComplete {@code setup-complete} of {@code cloud.yml}
 * @param ready         whether the node starts services (setup complete and EULA accepted)
 * @param missing       what is still missing: {@code eula}, {@code setup}, or steps the node adds
 */
@JsonPropertyOrder({"eula-accepted", "setup-complete", "ready", "missing"})
public record SetupStatus(boolean eulaAccepted, boolean setupComplete, boolean ready, List<String> missing) {

    /** Copies the list. */
    public SetupStatus {
        missing = missing == null ? List.of() : List.copyOf(missing);
    }

    /**
     * The status as far as {@code cloud.yml} tells it.
     *
     * @param snapshot the configuration
     * @return the status
     */
    public static SetupStatus of(ConfigSnapshot snapshot) {
        boolean eula = snapshot.cloud().eulaAccepted();
        boolean complete = snapshot.cloud().setupComplete();
        List<String> missing = new ArrayList<>();
        if (!eula) {
            missing.add("eula");
        }
        if (!complete) {
            missing.add("setup");
        }
        return new SetupStatus(eula, complete, eula && complete, missing);
    }
}
