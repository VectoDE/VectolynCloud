package net.vectora.cloud.template;

/**
 * Renders {@code Server-Network/<Server>/template/} into {@code runtime/}, with placeholders and secrets.
 *
 * <p>Entry point: {@link TemplateInstaller}. Details in docs/cloud/template.md.
 */
public final class CloudTemplateModule {

    /** Gradle project name of this module. */
    public static final String NAME = "cloud-template";

    private CloudTemplateModule() {
    }
}
