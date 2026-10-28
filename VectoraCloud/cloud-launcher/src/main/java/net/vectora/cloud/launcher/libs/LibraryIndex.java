package net.vectora.cloud.launcher.libs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The index of the libraries embedded in {@code VectoraCloud.jar}, stored as
 * {@code META-INF/vectora/libs.index} and written by the Gradle task
 * {@code embeddedLibrariesIndex}.
 *
 * <p>Format, one library per line, the same layout as {@code sha256sum}:
 * <pre>
 * # comment
 * 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08  cloud-node-0.1.0.jar
 * </pre>
 * Blank lines and lines starting with {@code #} are ignored. File names must be plain jar names
 * ({@value #NAME_REGEX}); anything with a path separator or {@code ..} is rejected here already,
 * before {@link LibraryExtractor} resolves it.
 */
public final class LibraryIndex {

    /** Resource path of the index inside {@code VectoraCloud.jar}. */
    public static final String RESOURCE = "META-INF/vectora/libs.index";

    /** Resource directory of the embedded jars inside {@code VectoraCloud.jar}. */
    public static final String LIBS_DIRECTORY = "META-INF/vectora/libs/";

    /** Allowed file names: letters, digits, dot, underscore, plus and minus, ending in {@code .jar}. */
    public static final String NAME_REGEX = "[A-Za-z0-9][A-Za-z0-9._+-]{0,199}\\.jar";

    private static final Pattern NAME = Pattern.compile(NAME_REGEX);
    private static final Pattern LINE = Pattern.compile("([0-9A-Fa-f]{64}) [ *](\\S.*)");

    private final List<LibraryEntry> entries;

    private LibraryIndex(List<LibraryEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    /**
     * Parses the index.
     *
     * @param text content of {@code libs.index}
     * @return the parsed index
     * @throws LibraryException for a malformed line, an unsafe or duplicate file name
     */
    public static LibraryIndex parse(String text) throws LibraryException {
        List<LibraryEntry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            var matcher = LINE.matcher(line);
            if (!matcher.matches()) {
                throw new LibraryException(LibraryException.Kind.INDEX_INVALID, line, "expected '<sha256>  <file name>'");
            }
            String name = matcher.group(2).strip();
            if (!isSafeName(name)) {
                throw new LibraryException(LibraryException.Kind.UNSAFE_NAME, name, "not a plain jar file name");
            }
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new LibraryException(LibraryException.Kind.INDEX_INVALID, name, "listed twice");
            }
            entries.add(new LibraryEntry(name, matcher.group(1).toLowerCase(Locale.ROOT)));
        }
        return new LibraryIndex(entries);
    }

    /**
     * Checks a file name against {@link #NAME_REGEX}.
     *
     * @param name the file name
     * @return {@code true} if it is a plain jar name that cannot leave its directory
     */
    public static boolean isSafeName(String name) {
        return NAME.matcher(name).matches() && !name.contains("..");
    }

    /**
     * Returns the libraries in index order.
     *
     * @return an unmodifiable list
     */
    public List<LibraryEntry> entries() {
        return entries;
    }
}
