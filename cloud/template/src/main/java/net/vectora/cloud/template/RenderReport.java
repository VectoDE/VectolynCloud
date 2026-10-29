package net.vectora.cloud.template;

import java.util.List;

/**
 * What rendering a template did. All paths are relative, separated by {@code /} and sorted.
 *
 * @param rendered text files rendered from the template
 * @param copied other files copied byte for byte
 * @param skipped template entries not written: symbolic links, special files, the template README
 * @param generated files created from secrets or consent ({@code forwarding.secret},
 *     {@code key.pem}, {@code eula.txt})
 */
public record RenderReport(List<String> rendered, List<String> copied, List<String> skipped, List<String> generated) {

    /**
     * Creates a report with unmodifiable copies of the lists.
     *
     * @param rendered text files rendered from the template
     * @param copied other files copied byte for byte
     * @param skipped template entries not written
     * @param generated files created from secrets or consent
     */
    public RenderReport {
        rendered = List.copyOf(rendered);
        copied = List.copyOf(copied);
        skipped = List.copyOf(skipped);
        generated = List.copyOf(generated);
    }

    /**
     * Returns this report with {@code files} as generated files.
     *
     * @param files generated files, sorted
     * @return the new report
     */
    RenderReport withGenerated(List<String> files) {
        return new RenderReport(rendered, copied, skipped, files);
    }
}
