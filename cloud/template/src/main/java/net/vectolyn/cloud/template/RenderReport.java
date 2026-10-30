package net.vectolyn.cloud.template;

import java.util.List;

/**
 * What rendering a template did. All paths are relative, separated by {@code /} and sorted.
 *
 * @param rendered text files rendered from the template
 * @param copied other files copied byte for byte
 * @param skipped template entries not written: symbolic links, special files, the template README
 * @param generated files created from secrets or consent ({@code forwarding.secret},
 *     {@code key.pem}, {@code eula.txt}) or set by the node ({@code spigot.yml},
 *     {@code server.properties})
 * @param warnings what the node overrode in the template and the operator should know, one line
 *     each, for the node's log
 */
public record RenderReport(List<String> rendered, List<String> copied, List<String> skipped, List<String> generated,
                           List<String> warnings) {

    /**
     * Creates a report with unmodifiable copies of the lists.
     *
     * @param rendered text files rendered from the template
     * @param copied other files copied byte for byte
     * @param skipped template entries not written
     * @param generated files created from secrets or consent, or set by the node
     * @param warnings what the node overrode
     */
    public RenderReport {
        rendered = List.copyOf(rendered);
        copied = List.copyOf(copied);
        skipped = List.copyOf(skipped);
        generated = List.copyOf(generated);
        warnings = List.copyOf(warnings);
    }

    /**
     * Creates a report without warnings.
     *
     * @param rendered text files rendered from the template
     * @param copied other files copied byte for byte
     * @param skipped template entries not written
     * @param generated files created from secrets or consent, or set by the node
     */
    public RenderReport(List<String> rendered, List<String> copied, List<String> skipped, List<String> generated) {
        this(rendered, copied, skipped, generated, List.of());
    }

    /**
     * Returns this report with {@code files} as generated files and {@code notes} as warnings.
     *
     * @param files generated files, sorted
     * @param notes warnings
     * @return the new report
     */
    RenderReport withGenerated(List<String> files, List<String> notes) {
        return new RenderReport(rendered, copied, skipped, files, notes);
    }
}
