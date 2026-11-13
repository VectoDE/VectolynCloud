// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.node.console;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.template.TemplateFiles;
import net.vectolyn.cloud.console.ConsoleOutput;
import net.vectolyn.cloud.console.Text;
import net.vectolyn.cloud.console.command.Argument;
import net.vectolyn.cloud.console.command.Command;
import net.vectolyn.cloud.console.command.CommandException;
import org.jspecify.annotations.Nullable;

/**
 * {@code template list|files|show|put}: the template folders under {@code local/templates}, with
 * the same rules as {@code /api/v1/templates} (no secrets, no links, text files up to
 * {@link TemplateFiles#MAX_WRITE_BYTES} when writing).
 */
public final class TemplateCommands {

    private TemplateCommands() {
    }

    /**
     * Builds {@code template}.
     *
     * @param home  the cloud home; relative source files of {@code template put} start there
     * @param files the template files, the same rules as the API
     * @return the command
     */
    public static Command command(CloudHome home, TemplateFiles files) {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(files, "files");
        Argument template = Argument.required("template", Text.of("Template folder, for example Lobby-Server",
                "Template-Ordner, zum Beispiel Lobby-Server")).suggest(previous -> names(files));
        return Command.builder("template")
                .aliases("templates")
                .description(Text.of("Lists the templates and their files, shows a file and writes a text file.",
                        "Listet die Templates und ihre Dateien, zeigt eine Datei und schreibt eine Textdatei."))
                .subcommand(Command.builder("list")
                        .description(Text.of("Lists the template folders.", "Listet die Template-Ordner auf."))
                        .executes(context -> list(context.out(), files)))
                .subcommand(Command.builder("files")
                        .description(Text.of("Lists the files of a template (secrets and links are left out).",
                                "Listet die Dateien eines Templates (ohne Secrets und Links)."))
                        .argument(template)
                        .executes(context -> files(context.out(), files, context.argument("template"))))
                .subcommand(Command.builder("show")
                        .description(Text.of("Shows a text file of a template.", "Zeigt eine Textdatei eines Templates."))
                        .argument(template)
                        .argument(Argument.required("path", Text.of("Path inside the template, for example "
                                + "plugins/config.yml", "Pfad im Template, zum Beispiel plugins/config.yml")))
                        .executes(context -> show(context.out(), files, context.argument("template"),
                                context.argument("path"))))
                .subcommand(Command.builder("put")
                        .description(Text.of("Writes a text file into a template, from a file on this machine.",
                                "Schreibt eine Textdatei in ein Template, aus einer Datei auf diesem Rechner."))
                        .argument(template)
                        .argument(Argument.required("path", Text.of("Path inside the template",
                                "Pfad im Template")))
                        .argument(Argument.required("source", Text.of("Text file to copy; relative paths start at "
                                + "the cloud home", "Zu kopierende Textdatei; relative Pfade beginnen im Cloud-Home")))
                        .executes(context -> put(context.out(), files, context.argument("template"),
                                context.argument("path"), source(home, context.argument("source")))))
                .build();
    }

    private static List<String> names(TemplateFiles files) {
        try {
            return files.templates();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static void list(ConsoleOutput out, TemplateFiles files) throws CommandException {
        List<String> names = call(null, files::templates);
        if (names.isEmpty()) {
            out.println(Text.of("No templates.", "Keine Templates."));
            return;
        }
        names.forEach(out::println);
    }

    private static void files(ConsoleOutput out, TemplateFiles files, String template) throws CommandException {
        TemplateFiles.Listing listing = call(null, () -> files.files(template));
        for (TemplateFiles.FileInfo file : listing.files()) {
            out.println(file.path() + "  " + file.size());
        }
        out.println(Text.of("%1$s file(s).", "%1$s Datei(en)."), listing.files().size());
        if (listing.truncated()) {
            out.warning(Text.of("The list is cut off after %1$s files.", "Die Liste endet nach %1$s Dateien."),
                    TemplateFiles.MAX_LISTED_FILES);
        }
    }

    private static void show(ConsoleOutput out, TemplateFiles files, String template, String path)
            throws CommandException {
        byte[] content = call(path, () -> files.file(template, path));
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
            text.lines().forEach(out::println);
        } catch (CharacterCodingException e) {
            out.println(Text.of("%1$s is no text file (%2$s bytes).", "%1$s ist keine Textdatei (%2$s Bytes)."),
                    path, content.length);
        }
    }

    private static void put(ConsoleOutput out, TemplateFiles files, String template, String path, Path source)
            throws CommandException {
        byte[] content;
        try {
            if (Files.size(source) > TemplateFiles.MAX_WRITE_BYTES) {
                throw new CommandException(Text.of("%1$s is larger than %2$s bytes.",
                        "%1$s ist größer als %2$s Bytes."), source, TemplateFiles.MAX_WRITE_BYTES);
            }
            content = Files.readAllBytes(source);
        } catch (IOException e) {
            throw new CommandException(e, Text.of("Cannot read %1$s: %2$s", "%1$s ist nicht lesbar: %2$s"),
                    source, String.valueOf(e.getMessage()));
        }
        TemplateFiles.Outcome outcome = call(path, () -> files.write(template, path, content, null));
        out.success(outcome == TemplateFiles.Outcome.CREATED
                ? Text.of("Created: %1$s/%2$s", "Angelegt: %1$s/%2$s")
                : Text.of("Saved: %1$s/%2$s", "Gespeichert: %1$s/%2$s"), template, path);
    }

    /** A path from the console: relative paths start at the cloud home. */
    private static Path source(CloudHome home, String name) throws CommandException {
        try {
            return home.root().resolve(name).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new CommandException(e, Text.of("Not a valid path.", "Kein gültiger Pfad."));
        }
    }

    /** A call of {@link TemplateFiles}. */
    @FunctionalInterface
    private interface Call<T> {
        T run() throws IOException, TemplateFiles.PreconditionException;
    }

    /** Runs a call and turns its failures into console errors. */
    private static <T> T call(@Nullable String path, Call<T> call) throws CommandException {
        try {
            return call.run();
        } catch (TemplateFiles.TooLargeException e) {
            throw new CommandException(e, Text.of("The file is larger than %1$s bytes.",
                    "Die Datei ist größer als %1$s Bytes."), e.limit());
        } catch (NoSuchFileException e) {
            throw new CommandException(e, path == null
                    ? Text.of("No such template.", "Dieses Template gibt es nicht.")
                    : Text.of("No such template or file: %1$s", "Template oder Datei gibt es nicht: %1$s"),
                    String.valueOf(path));
        } catch (FileSystemException e) {
            throw new CommandException(e, Text.of("A link or special file is in the way.",
                    "Ein Link oder eine Sonderdatei ist im Weg."));
        } catch (IllegalArgumentException e) {
            throw new CommandException(e, Text.of("Refused: %1$s", "Abgelehnt: %1$s"), String.valueOf(e.getMessage()));
        } catch (IOException | TemplateFiles.PreconditionException e) {
            throw new CommandException(e, Text.of("Template files: %1$s", "Template-Dateien: %1$s"),
                    String.valueOf(e.getMessage()));
        }
    }
}
