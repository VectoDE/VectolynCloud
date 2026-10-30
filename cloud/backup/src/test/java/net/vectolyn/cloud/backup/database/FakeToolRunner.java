package net.vectolyn.cloud.backup.database;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Records every call. By default it behaves like a successful dump tool: it writes the file named
 * by {@code --result-file=} (mariadb-dump) or {@code -script} (H2) and records what the option
 * file held while the tool ran.
 */
public final class FakeToolRunner implements ToolRunner {

    /** One call. */
    public record Call(List<String> command, String optionFileContent, boolean optionFileOwnerOnly) {
    }

    private final List<Call> calls = new ArrayList<>();
    private int exitCode;
    private String output = "";
    private String dump = "-- MariaDB dump\nCREATE DATABASE `luckperms`;\n-- Dump completed on 2026-10-29\n";

    public FakeToolRunner exitCode(int value, String text) {
        exitCode = value;
        output = text;
        return this;
    }

    public FakeToolRunner dump(String content) {
        dump = content;
        return this;
    }

    public List<Call> calls() {
        return calls;
    }

    @Override
    public Result run(List<String> command, Path workingDirectory, Duration timeout) throws IOException {
        String options = null;
        boolean ownerOnly = false;
        Path result = null;
        for (int i = 0; i < command.size(); i++) {
            String argument = command.get(i);
            if (argument.startsWith("--defaults-extra-file=")) {
                Path file = Path.of(argument.substring("--defaults-extra-file=".length()));
                options = Files.readString(file, StandardCharsets.UTF_8);
                ownerOnly = net.vectolyn.cloud.config.secret.OwnerOnlyPermissions.isOwnerOnly(file);
            } else if (argument.startsWith("--result-file=")) {
                result = Path.of(argument.substring("--result-file=".length()));
            } else if (argument.equals("-script")) {
                result = Path.of(command.get(i + 1));
            }
        }
        calls.add(new Call(List.copyOf(command), options, ownerOnly));
        if (exitCode == 0 && result != null && dump != null) {
            Files.writeString(result, dump, StandardCharsets.UTF_8);
        }
        return new Result(exitCode, output);
    }
}
