package net.vectora.cloud.config.io;

import net.vectora.cloud.config.error.ErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Task files from version 1 to 2: the task-wide {@code mode} becomes {@code scaling.static-services}.
 * <ul>
 *   <li>{@code mode: static}: {@code static-services} and {@code max-services} become
 *       {@code min-services}, so the task keeps its fixed number of static services and is never
 *       scaled, as before. With {@code min-services: 0} the task gets one static service
 *       (static = min = max = 1) and a warning in the log names the file;</li>
 *   <li>{@code mode: dynamic}: {@code static-services: 0}, min and max stay.</li>
 * </ul>
 * Version 1 had no {@code scale-down-below}. The migration sets it relative to {@code scale-up-at}:
 * {@code min(50, scale-up-at / 2)}, rounded down, so a task with a low {@code scale-up-at} stays
 * valid ({@code scale-down-below} must stay below it). A file that already has the field keeps it.
 * Content that is not what version 1 allowed is left as it is, so binding or the validator reports
 * it with its field.
 */
final class TaskModeMigration implements ConfigMigration {

    private static final System.Logger LOG = System.getLogger(TaskModeMigration.class.getName());

    /** Upper limit of the {@code scale-down-below} the migration sets, in percent. */
    static final int MAX_MIGRATED_SCALE_DOWN_BELOW = 50;

    @Override
    public int fromVersion() {
        return 1;
    }

    @Override
    public void migrate(ObjectNode root) {
        migrate(root, "a task file");
    }

    @Override
    public void migrate(ObjectNode root, String file) {
        JsonNode mode = root.get("mode");
        if (mode == null || mode.isNull()) {
            // Version 1 required the field; guessing a mode would change what the task does.
            throw new MigrationException("mode", ErrorCode.REQUIRED);
        }
        if (!mode.isString() || !("static".equals(mode.stringValue()) || "dynamic".equals(mode.stringValue()))) {
            throw new MigrationException("mode", ErrorCode.INVALID_VALUE, "static or dynamic");
        }
        boolean isStatic = "static".equals(mode.stringValue());
        root.remove("mode");
        JsonNode scaling = root.get("scaling");
        if (!(scaling instanceof ObjectNode section)) {
            return;
        }
        addScaleDownBelow(section);
        if (!isStatic) {
            section.put("static-services", 0);
            return;
        }
        JsonNode min = section.get("min-services");
        if (min == null || !min.isIntegralNumber() || !min.canConvertToInt()) {
            return;
        }
        int count = min.intValue();
        if (count == 0) {
            // A static task with no service ran nothing; one static service is the smallest valid
            // static task (max-services is at least 1), so it gets that and the log says so.
            count = 1;
            section.put("min-services", count);
            LOG.log(System.Logger.Level.WARNING, "{0}: mode static with min-services 0 migrated to static-services 1,"
                    + " min-services 1 and max-services 1 (a static task needs at least one service)", file);
        }
        section.put("static-services", count);
        section.put("max-services", count);
    }

    /**
     * {@code scale-down-below = min(50, scale-up-at / 2)} if the file has none and
     * {@code scale-up-at} is a whole number; otherwise nothing, so binding reports the field.
     */
    private static void addScaleDownBelow(ObjectNode section) {
        if (section.has("scale-down-below")) {
            return;
        }
        JsonNode up = section.get("scale-up-at");
        if (up == null || !up.isIntegralNumber() || !up.canConvertToInt()) {
            return;
        }
        section.put("scale-down-below", scaleDownBelowFor(up.intValue()));
    }

    /**
     * The {@code scale-down-below} a version 1 file gets for its {@code scale-up-at}.
     *
     * @param scaleUpAt {@code scale-up-at} of the file
     * @return {@code min(50, scaleUpAt / 2)}, at least 0
     */
    static int scaleDownBelowFor(int scaleUpAt) {
        return Math.max(0, Math.min(MAX_MIGRATED_SCALE_DOWN_BELOW, scaleUpAt / 2));
    }
}
