package net.vectora.cloud.template;

/**
 * Renders {@code Server-Network/<Server>/template/} into {@code runtime/}, with placeholders and secrets.
 *
 * <p>Placeholder from the stage-2 scaffold. The module structure and its planned
 * content are described in docs/cloud/architecture.md.
 */
public final class CloudTemplateModule {

    /** Gradle project name of this module. */
    public static final String NAME = "cloud-template";

    private CloudTemplateModule() {
    }
}
