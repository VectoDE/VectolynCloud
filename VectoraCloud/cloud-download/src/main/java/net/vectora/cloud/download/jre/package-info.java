/**
 * Java runtimes for the servers the cloud starts: which feature version a group needs
 * ({@link net.vectora.cloud.download.jre.JavaRequirement}), an installed system Java that fits
 * ({@link net.vectora.cloud.download.jre.SystemJavaFinder}), or Temurin from Adoptium unpacked into
 * {@code <home>/jre/} ({@link net.vectora.cloud.download.jre.JreInstaller}).
 * {@link net.vectora.cloud.download.jre.JreManager} ties them together and returns a
 * {@link net.vectora.cloud.download.jre.JavaRuntime} with the path of {@code java}.
 */
package net.vectora.cloud.download.jre;
