// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.tools.apicompat;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the public API of {@code common/protocol} and {@code common/client} against the newest
 * release (docs/api-compatibility.md). Run by {@code ./gradlew :tools:api-compat:apiCompat}.
 *
 * <p>Arguments: {@code --japicmp <jar> --baseline <tag> --next <version> --report <file>}, then
 * once per module {@code --module <name> <old jar> <new jar> <old classpath|-> <new classpath|->}.
 *
 * <p>Exit code 0: no incompatible change, or the next version is a breaking step. 1: an
 * incompatible change without a breaking step. 2: wrong arguments or japicmp failed.
 */
public final class ApiCompat {

    private ApiCompat() {
    }

    /** One module to compare. */
    record Module(String name, Path oldJar, Path newJar, List<Path> oldClasspath, List<Path> newClasspath) {
    }

    /** The parsed arguments. */
    record Options(Path japicmp, String baseline, String next, Path report, List<Module> modules) {
    }

    public static void main(String[] args) {
        int code;
        try {
            Options options = parse(args);
            code = run(options, Japicmp.withCurrentJava(options.japicmp()), System.out);
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("error: " + e.getMessage());
            code = 2;
        }
        System.exit(code);
    }

    static Options parse(String[] args) {
        Path japicmp = null;
        String baseline = null;
        String next = null;
        Path report = null;
        List<Module> modules = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--japicmp" -> japicmp = Path.of(value(args, ++i));
                case "--baseline" -> baseline = value(args, ++i);
                case "--next" -> next = value(args, ++i);
                case "--report" -> report = Path.of(value(args, ++i));
                case "--module" -> {
                    if (i + 5 >= args.length) {
                        throw new IllegalArgumentException("--module needs <name> <old> <new> <old cp> <new cp>");
                    }
                    modules.add(new Module(args[i + 1], Path.of(args[i + 2]), Path.of(args[i + 3]),
                            classpath(args[i + 4]), classpath(args[i + 5])));
                    i += 5;
                }
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        if (japicmp == null || baseline == null || next == null || report == null || modules.isEmpty()) {
            throw new IllegalArgumentException("usage: --japicmp <jar> --baseline <tag> --next <version> "
                    + "--report <file> --module <name> <old> <new> <old cp|-> <new cp|-> ...");
        }
        VersionRule.Core.parse(baseline);
        VersionRule.Core.parse(next);
        return new Options(japicmp, baseline, next, report, List.copyOf(modules));
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException(args[i - 1] + " needs a value");
        }
        return args[i];
    }

    private static List<Path> classpath(String text) {
        return text.equals("-") ? List.of() : List.of(Path.of(text));
    }

    /**
     * Compares every module, writes the Markdown report and prints the verdict.
     *
     * @return the exit code
     */
    static int run(Options options, Japicmp japicmp, PrintStream out) throws IOException {
        boolean allowed = VersionRule.breakingChangeAllowed(options.baseline(), options.next());
        List<String> broken = new ArrayList<>();
        StringBuilder md = new StringBuilder();
        md.append("## API compatibility against ").append(options.baseline()).append("\n\n");
        md.append("Version being built: `").append(options.next()).append("`. ");
        md.append(allowed ? "It is a breaking step, so incompatible changes are allowed.\n\n"
                : "It is not a breaking step; an incompatible change needs "
                        + VersionRule.nextBreakingVersion(options.baseline()) + " or higher.\n\n");
        md.append("| Module | Result |\n|---|---|\n");
        StringBuilder details = new StringBuilder();
        for (Module module : options.modules()) {
            Japicmp.Result result = japicmp.compare(module.oldJar(), module.newJar(), module.oldClasspath(),
                    module.newClasspath());
            md.append("| `").append(module.name()).append("` | ")
                    .append(result.compatible() ? "compatible" : "**incompatible**").append(" |\n");
            if (!result.compatible()) {
                broken.add(module.name());
                details.append("\n### ").append(module.name()).append("\n\n```text\n")
                        .append(result.output().strip()).append("\n```\n");
            }
        }
        md.append(details);
        Path parent = options.report().toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(options.report(), md, StandardCharsets.UTF_8);

        if (broken.isEmpty()) {
            out.println("API compatible with " + options.baseline() + ": " + names(options.modules()));
            return 0;
        }
        if (allowed) {
            out.println("Incompatible API changes in " + String.join(", ", broken) + " against "
                    + options.baseline() + "; allowed, " + options.next() + " is a breaking step.");
            return 0;
        }
        out.println("Incompatible API changes in " + String.join(", ", broken) + " against " + options.baseline()
                + ". Make the change compatible, or raise version in gradle.properties to "
                + VersionRule.nextBreakingVersion(options.baseline()) + "-SNAPSHOT or higher (now "
                + options.next() + "). Details: " + options.report());
        return 1;
    }

    private static String names(List<Module> modules) {
        return String.join(", ", modules.stream().map(Module::name).toList());
    }
}
