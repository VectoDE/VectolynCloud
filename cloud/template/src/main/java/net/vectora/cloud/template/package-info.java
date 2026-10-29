/**
 * Renders {@code <home>/<Server>/template/} into {@code runtime/}, with placeholders and secrets.
 *
 * <p>{@link net.vectora.cloud.template.TemplateInstaller} renders a template and writes the
 * generated files ({@code forwarding.secret}, Floodgate's {@code key.pem}, {@code eula.txt}).
 * {@link net.vectora.cloud.template.PlaceholderRenderer} defines the strict placeholder syntax,
 * {@link net.vectora.cloud.template.ProxyServerList} builds the proxy's server list from the wanted
 * servers. Values come in through {@link net.vectora.cloud.template.PlaceholderValues}, so this
 * module does not depend on how the cloud stores its configuration.
 *
 * <p>Writes never follow a symbolic link or junction below the runtime folder and never leave it.
 * Placeholder values are checked before they are inserted. Details in
 * docs/cloud/template.md.
 */
package net.vectora.cloud.template;
