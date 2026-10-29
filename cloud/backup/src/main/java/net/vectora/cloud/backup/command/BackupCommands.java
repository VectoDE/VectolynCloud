package net.vectora.cloud.backup.command;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.vectora.cloud.backup.BackupException;
import net.vectora.cloud.backup.BackupInfo;
import net.vectora.cloud.backup.BackupReason;
import net.vectora.cloud.backup.BackupRequest;
import net.vectora.cloud.backup.BackupService;
import net.vectora.cloud.backup.RestoreResult;
import net.vectora.cloud.backup.manifest.BackupManifest;
import net.vectora.cloud.console.Text;
import net.vectora.cloud.console.command.Argument;
import net.vectora.cloud.console.command.Command;
import net.vectora.cloud.console.command.CommandContext;
import net.vectora.cloud.console.command.CommandException;
import net.vectora.cloud.console.command.CommandRegistry;
import net.vectora.cloud.console.prompt.PromptCancelledException;
import net.vectora.cloud.console.prompt.Question;

/**
 * The console command {@code backup} with {@code create}, {@code list}, {@code restore} and
 * {@code delete}. cloud-node registers it with {@link #register(CommandRegistry, BackupService)}
 * once the backup service exists.
 *
 * <pre>
 * backup create [service...]          all static services and the database, or only the named services
 * backup list
 * backup restore &lt;backup&gt; &lt;service&gt; [--yes]
 * backup delete &lt;backup&gt; [--yes]
 * </pre>
 *
 * {@code restore} and {@code delete} ask first; {@code --yes} or {@code -y} skips the question
 * (for scripts). Enter means no.
 */
public final class BackupCommands {

    /** Name of the command. */
    public static final String NAME = "backup";

    private static final List<String> YES_FLAGS = List.of("--yes", "-y");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private BackupCommands() {
    }

    /**
     * Registers {@code backup}.
     *
     * @param registry the console's registry
     * @param service the backup service
     */
    public static void register(CommandRegistry registry, BackupService service) {
        registry.register(command(service));
    }

    /**
     * Builds {@code backup}.
     *
     * @param service the backup service
     * @return the command
     */
    public static Command command(BackupService service) {
        Objects.requireNonNull(service, "service");
        Argument.Suggestions backups = words -> {
            try {
                return service.list().stream().map(BackupInfo::id).toList();
            } catch (BackupException e) {
                return List.of();
            }
        };
        Argument.Suggestions services = words -> service.serviceIds();
        return Command.builder(NAME)
                .aliases("backups")
                .description(Text.of("Back up, list, restore and delete backups of the static services and the "
                        + "database.", "Sicherungen der statischen Dienste und der Datenbank anlegen, auflisten, "
                        + "wiederherstellen und löschen."))
                .subcommand(Command.builder("create")
                        .description(Text.of("Back up all static services and the database, or the named services.",
                                "Alle statischen Dienste und die Datenbank sichern oder nur die genannten Dienste."))
                        .argument(Argument.optional("services", Text.of("Service ids, for example lobby-1",
                                "Dienst-IDs, zum Beispiel lobby-1")).takingRest().suggest(services))
                        .executes(context -> create(context, service)))
                .subcommand(Command.builder("list")
                        .description(Text.of("List the backups, newest first.", "Sicherungen auflisten, neueste zuerst."))
                        .executes(context -> list(context, service)))
                .subcommand(Command.builder("restore")
                        .description(Text.of("Restore a stopped service from a backup.",
                                "Einen gestoppten Dienst aus einer Sicherung wiederherstellen."))
                        .argument(Argument.required("backup", Text.of("Backup id", "Sicherungs-ID")).suggest(backups))
                        .argument(Argument.required("service", Text.of("Service id", "Dienst-ID")).suggest(services))
                        .argument(confirmation())
                        .executes(context -> restore(context, service)))
                .subcommand(Command.builder("delete")
                        .description(Text.of("Delete a backup.", "Eine Sicherung löschen."))
                        .argument(Argument.required("backup", Text.of("Backup id", "Sicherungs-ID")).suggest(backups))
                        .argument(confirmation())
                        .executes(context -> delete(context, service)))
                .build();
    }

    private static Argument confirmation() {
        return Argument.optional("confirm", Text.of("--yes or -y runs without asking (for scripts)",
                "--yes oder -y führt ohne Rückfrage aus (für Skripte)")).suggest(YES_FLAGS);
    }

    private static void create(CommandContext context, BackupService service) throws CommandException,
            InterruptedException {
        Set<String> ids = new LinkedHashSet<>(context.words("services"));
        for (String id : ids) {
            if (!net.vectora.cloud.backup.BackupTarget.isServiceId(id)) {
                throw new CommandException(Text.of("Not a service id: %1$s", "Keine Dienst-ID: %1$s"), printable(id));
            }
        }
        BackupRequest request = ids.isEmpty() ? BackupRequest.all(BackupReason.MANUAL)
                : BackupRequest.of(BackupReason.MANUAL, ids);
        context.out().println(Text.of("Creating backup ...", "Sicherung wird angelegt ..."));
        BackupInfo info = run(() -> service.create(request));
        for (String warning : info.warnings()) {
            context.out().warning(warning);
        }
        BackupManifest manifest = info.manifest().orElseThrow();
        context.out().success(Text.of("Backup %1$s created: %2$s services, %3$s files, %4$s (%5$s).",
                        "Sicherung %1$s angelegt: %2$s Dienste, %3$s Dateien, %4$s (%5$s)."),
                info.id(), manifest.services().size(), manifest.files().size(), size(info.size()), info.file());
        if (ids.isEmpty()) {
            List<String> deleted = run(service::prune);
            if (!deleted.isEmpty()) {
                context.out().println(Text.of("Retention deleted: %1$s", "Durch Aufbewahrung gelöscht: %1$s"),
                        String.join(", ", deleted));
            }
        }
    }

    private static void list(CommandContext context, BackupService service) throws CommandException,
            InterruptedException {
        List<BackupInfo> backups = run(service::list);
        if (backups.isEmpty()) {
            context.out().println(Text.of("No backups yet.", "Noch keine Sicherungen."));
            return;
        }
        context.out().heading(String.format(Locale.ROOT, "%-21s %-16s %-11s %10s  %s", "ID",
                Text.of("Created", "Angelegt").get(context.language()), Text.of("Reason", "Grund").get(context.language()),
                Text.of("Size", "Größe").get(context.language()), Text.of("Services", "Dienste").get(context.language())));
        for (BackupInfo info : backups) {
            if (info.manifest().isEmpty()) {
                context.out().warning(String.format(Locale.ROOT, "%-21s %s", info.id(), info.problem().orElse("")));
                continue;
            }
            BackupManifest manifest = info.manifest().get();
            List<String> services = new ArrayList<>();
            for (BackupManifest.ServiceEntry entry : manifest.services()) {
                services.add(entry.hasFiles() ? entry.id() : entry.id() + " (" + entry.consistency() + ")");
            }
            if (manifest.database() != null) {
                services.add("db:" + manifest.database().status());
            }
            context.out().println(String.format(Locale.ROOT, "%-21s %-16s %-11s %10s  %s", info.id(),
                    TIME.format(ZonedDateTime.ofInstant(manifest.created(), service.zone())), manifest.reason(),
                    size(info.size()), String.join(", ", services)));
        }
    }

    private static void restore(CommandContext context, BackupService service)
            throws CommandException, PromptCancelledException, InterruptedException {
        String backup = context.argument("backup");
        String target = context.argument("service");
        if (!confirm(context, Text.of("Restore %2$s from backup %1$s? Its current folder is backed up first and "
                        + "then replaced.", "%2$s aus Sicherung %1$s wiederherstellen? Der aktuelle Ordner wird "
                        + "vorher gesichert und dann ersetzt."), backup, target)) {
            context.out().println(Text.of("Nothing was restored.", "Es wurde nichts wiederhergestellt."));
            return;
        }
        RestoreResult result = run(() -> service.restore(backup, target));
        result.warnings().forEach(context.out()::warning);
        context.out().success(Text.of("Restored %1$s from %2$s (%3$s files).", "%1$s aus %2$s wiederhergestellt "
                + "(%3$s Dateien)."), result.serviceId(), result.backupId(), result.files());
        result.safetyBackupId().ifPresent(id -> context.out().println(Text.of(
                "The previous state is in backup %1$s.", "Der vorherige Stand liegt in Sicherung %1$s."), id));
    }

    private static void delete(CommandContext context, BackupService service)
            throws CommandException, PromptCancelledException, InterruptedException {
        String backup = context.argument("backup");
        // Fails early for an unknown id, so nobody confirms a deletion that cannot happen.
        run(() -> service.find(backup));
        if (!confirm(context, Text.of("Really delete backup %1$s?", "Sicherung %1$s wirklich löschen?"), backup)) {
            context.out().println(Text.of("Nothing was deleted.", "Es wurde nichts gelöscht."));
            return;
        }
        run(() -> {
            service.delete(backup);
            return null;
        });
        context.out().success(Text.of("Deleted backup %1$s.", "Sicherung %1$s gelöscht."), backup);
    }

    /** Whether to go ahead: {@code --yes}/{@code -y}, or a yes to the question. */
    private static boolean confirm(CommandContext context, Text question, Object... args)
            throws CommandException, PromptCancelledException {
        Optional<String> flag = context.optional("confirm");
        if (flag.isPresent() && !YES_FLAGS.contains(flag.get())) {
            throw new CommandException(Text.of("Only --yes or -y may follow.", "Danach ist nur --yes oder -y erlaubt."));
        }
        if (flag.isPresent()) {
            return true;
        }
        String english = String.format(Locale.ROOT, question.english(), args);
        String german = String.format(Locale.ROOT, question.german(), args);
        return context.prompter().ask(Question.yesNo(Text.of(english, german)).defaultValue(false));
    }

    /** A backup call whose failure becomes a console error. */
    @FunctionalInterface
    private interface Call<T> {
        T run() throws BackupException, InterruptedException;
    }

    private static <T> T run(Call<T> call) throws CommandException, InterruptedException {
        try {
            return call.run();
        } catch (BackupException e) {
            throw new CommandException(Text.of("Backup: %1$s", "Sicherung: %1$s"), e.getMessage());
        }
    }

    /** Human-readable size. */
    static String size(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit == 0 ? bytes + " B" : String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String printable(String text) {
        return text.codePoints().limit(80).collect(StringBuilder::new,
                (out, c) -> out.appendCodePoint(Character.isISOControl(c) ? '?' : c), StringBuilder::append).toString();
    }
}
