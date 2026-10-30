package net.vectora.cloud.node.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.ServerSoftware;

/**
 * Puts the server jar into the folder of a service.
 *
 * <p>Seam for the download step: the full chain (resolve the build, download it through
 * {@code cloud-download}, verify it, copy it into the folder) is wired in with the end-to-end
 * pull request (stage 2 PR 14). Until then {@link #existingJar()} expects the jar to be there
 * already, as it is in today's {@code <home>/<Server>/runtime/} folders.
 */
@FunctionalInterface
public interface ServerSoftwareProvider {

    /**
     * Makes sure the server jar of {@code spec} is in {@code workDirectory}.
     *
     * @param spec          the service
     * @param workDirectory its folder, already rendered from the template
     * @return the jar's file name relative to {@code workDirectory}, passed to {@code java -jar}
     * @throws IOException if the jar is missing and cannot be provided
     */
    String provide(ServiceSpec spec, Path workDirectory) throws IOException;

    /**
     * Fetches what the services of a group need (server jar, plugins or mods) ahead of their
     * start, for the software step of the start ({@code docs/cloud/node.md#start-order}). The
     * default does nothing; {@link #provide} still has to work without it.
     *
     * @param groupName the group
     * @param group     its configuration
     * @return a short description of what is in place, for example {@code paper 1.21.10 + 6 plugins},
     *         or empty if this provider fetches nothing ahead
     * @throws IOException if something cannot be fetched
     */
    default Optional<String> prepare(String groupName, GroupConfig group) throws IOException {
        return Optional.empty();
    }

    /**
     * Uses the jar that is already in the folder: {@code paper.jar}, {@code velocity.jar},
     * {@code fabric-server.jar} or {@code pandaspigot.jar}, the names the start scripts use.
     *
     * @return the default provider
     */
    static ServerSoftwareProvider existingJar() {
        return (spec, workDirectory) -> {
            String name = jarName(spec.group().software());
            Path jar = workDirectory.resolve(name);
            if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("server jar " + name + " is missing in the folder of " + spec.id()
                        + "; downloads are not wired in yet, so place it there");
            }
            return name;
        };
    }

    /**
     * File name of the server jar for a software.
     *
     * @param software the software
     * @return the jar name the templates and start scripts use
     */
    static String jarName(ServerSoftware software) {
        return switch (software) {
            case PAPER -> "paper.jar";
            case VELOCITY -> "velocity.jar";
            case FABRIC -> "fabric-server.jar";
            case PANDASPIGOT -> "pandaspigot.jar";
        };
    }
}
