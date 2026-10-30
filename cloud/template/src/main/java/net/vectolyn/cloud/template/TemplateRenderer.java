package net.vectolyn.cloud.template;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Renders a template folder ({@code <home>/<Server>/template/}) into a runtime folder.
 *
 * <ul>
 *   <li>Files with a text extension (see {@link #TEXT_EXTENSIONS}, case-insensitive) go through
 *       {@link PlaceholderRenderer}. Every other file is copied byte for byte.</li>
 *   <li>Markdown files ({@code *.md}, case-insensitive, in any folder) document the template and
 *       are not copied: they name placeholders on purpose and would otherwise carry their values
 *       (secrets included) into the runtime folder.</li>
 *   <li>Files that come from the template overwrite their runtime copy. Every other file in the
 *       runtime folder (worlds, jars, logs, player data) stays untouched; nothing is deleted.</li>
 *   <li>Symbolic links and Windows junctions in the template are skipped, not followed. Neither is
 *       ever written through in the runtime folder (see the package documentation).</li>
 *   <li>All text files are rendered before the first write, so an unknown placeholder leaves the
 *       runtime folder as it was.</li>
 * </ul>
 */
public final class TemplateRenderer {

    /**
     * Extensions of files that are rendered. {@code bat} and {@code cmd} are included because the
     * start scripts in the templates carry heap-size and port placeholders.
     */
    public static final Set<String> TEXT_EXTENSIONS = Set.of(
            "yml", "yaml", "toml", "properties", "json", "conf", "txt", "cfg", "ini", "sql", "bat", "cmd");

    /** Extension of template documentation, which is never copied (case-insensitive). */
    private static final String DOCUMENTATION_EXTENSION = ".md";

    private TemplateRenderer() {
    }

    /**
     * Tells whether a file with this name is rendered (otherwise it is copied byte for byte).
     *
     * @param fileName file name, with or without folders
     * @return whether its extension is one of {@link #TEXT_EXTENSIONS}
     */
    public static boolean isRenderedFile(String fileName) {
        String name = fileName.substring(Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 && TEXT_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * Renders {@code template} into {@code runtime}.
     *
     * @param template template folder
     * @param runtime runtime folder; created if missing
     * @param values placeholder values
     * @return what was rendered, copied and skipped
     * @throws TemplateException if a placeholder is unknown or malformed, a text file is not UTF-8, or
     *     a target path is unsafe; nothing is written for placeholder and encoding errors
     * @throws IOException if the template cannot be read or the runtime folder cannot be written
     */
    public static RenderReport render(Path template, Path runtime, PlaceholderValues values)
            throws TemplateException, IOException {
        Objects.requireNonNull(values, "values");
        Path source = template.toAbsolutePath().normalize();
        Path target = runtime.toAbsolutePath().normalize();
        if (!Files.exists(source)) {
            throw new NoSuchFileException(source.toString());
        }
        if (!Files.isDirectory(source)) {
            throw new NotDirectoryException(source.toString());
        }
        if (source.startsWith(target) || target.startsWith(source)) {
            throw new IllegalArgumentException("template and runtime folder must not contain each other");
        }

        List<String> textFiles = new ArrayList<>();
        List<String> otherFiles = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Files.walkFileTree(source, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                // The template folder itself may be a link; a link or junction below it is not entered.
                if (!dir.equals(source) && RuntimePaths.isLink(attributes)) {
                    skipped.add(relative(source, dir));
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                String relative = relative(source, file);
                if (!attributes.isRegularFile() || RuntimePaths.isLink(attributes) || isDocumentation(relative)) {
                    skipped.add(relative);
                } else if (isRenderedFile(relative)) {
                    textFiles.add(relative);
                } else {
                    otherFiles.add(relative);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        Comparator<String> order = Comparator.naturalOrder();
        textFiles.sort(order);
        otherFiles.sort(order);
        skipped.sort(order);

        // Render and check everything first, write afterwards.
        List<byte[]> rendered = new ArrayList<>(textFiles.size());
        for (String file : textFiles) {
            rendered.add(PlaceholderRenderer.renderUtf8(Files.readAllBytes(source.resolve(file)), file, values));
            RuntimePaths.check(target, file);
        }
        for (String file : otherFiles) {
            RuntimePaths.check(target, file);
        }
        for (int i = 0; i < textFiles.size(); i++) {
            RuntimePaths.write(target, textFiles.get(i), rendered.get(i), false);
        }
        for (String file : otherFiles) {
            RuntimePaths.write(target, file, Files.readAllBytes(source.resolve(file)), false);
        }
        return new RenderReport(textFiles, otherFiles, skipped, List.of());
    }

    private static boolean isDocumentation(String relative) {
        return relative.toLowerCase(Locale.ROOT).endsWith(DOCUMENTATION_EXTENSION);
    }

    private static String relative(Path root, Path file) {
        StringBuilder out = new StringBuilder();
        for (Path part : root.relativize(file)) {
            if (!out.isEmpty()) {
                out.append('/');
            }
            out.append(part);
        }
        return out.toString();
    }
}
