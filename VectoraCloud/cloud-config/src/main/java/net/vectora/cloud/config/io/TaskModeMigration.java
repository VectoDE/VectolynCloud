package net.vectora.cloud.config.io;

import net.vectora.cloud.config.error.ErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Task files from version 1 to 2: the task-wide {@code mode} becomes {@code scaling.static-services}.
 * <ul>
 *   <li>{@code mode: static}: {@code static-services} and {@code max-services} become
 *       {@code min-services}, so the task keeps its fixed number of static services and is never
 *       scaled, as before;</li>
 *   <li>{@code mode: dynamic}: {@code static-services: 0}, min and max stay.</li>
 * </ul>
 * {@code scale-down-below} is not added; a file without it gets the default
 * ({@link net.vectora.cloud.config.model.ScalingConfig#DEFAULT_SCALE_DOWN_BELOW}). Content that is
 * not what version 1 allowed is left as it is, so binding or the validator reports it with its
 * field.
 */
final class TaskModeMigration implements ConfigMigration {

    @Override
    public int fromVersion() {
        return 1;
    }

    @Override
    public void migrate(ObjectNode root) {
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
        if (!isStatic) {
            section.put("static-services", 0);
            return;
        }
        JsonNode min = section.get("min-services");
        if (min == null || !min.isIntegralNumber() || !min.canConvertToInt()) {
            return;
        }
        section.put("static-services", min.intValue());
        section.put("max-services", min.intValue());
    }
}
