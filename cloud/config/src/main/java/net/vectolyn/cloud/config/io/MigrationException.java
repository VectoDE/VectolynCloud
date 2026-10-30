package net.vectolyn.cloud.config.io;

import java.io.Serial;
import java.util.List;
import net.vectolyn.cloud.config.error.ErrorCode;

/**
 * Thrown by a {@link ConfigMigration} that finds a field it cannot carry over. {@link ConfigYaml}
 * turns it into an error at that field, without quoting the value.
 */
public final class MigrationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String path;
    private final ErrorCode code;
    private final transient List<Object> args;

    /**
     * Creates the exception.
     *
     * @param path dotted field path in the file as it was read, for example {@code mode}
     * @param code what is wrong, for example {@link ErrorCode#REQUIRED}
     * @param args arguments of the message template of {@code code}; never the value of the file
     */
    public MigrationException(String path, ErrorCode code, Object... args) {
        super("cannot migrate " + path);
        this.path = path;
        this.code = code;
        this.args = List.of(args);
    }

    /** Dotted field path. */
    public String path() {
        return path;
    }

    /** What is wrong. */
    public ErrorCode code() {
        return code;
    }

    /** Arguments of the message. */
    public Object[] args() {
        return args.toArray();
    }
}
