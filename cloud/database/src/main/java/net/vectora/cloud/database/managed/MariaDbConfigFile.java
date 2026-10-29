package net.vectora.cloud.database.managed;

import java.nio.file.Path;
import java.util.Optional;
import net.vectora.cloud.database.ConnectionBudget;

/**
 * Writes {@code vectora-mariadb.ini} for the managed server. The settings are the ones of the old
 * hand-written file in {@code Server-Network/Database/template/}, with one difference: the server
 * always binds to {@code 127.0.0.1}, whatever {@code database.host} says. Nothing outside this
 * machine can reach the managed server.
 */
public final class MariaDbConfigFile {

    /** Address the managed server binds to. */
    public static final String BIND_ADDRESS = "127.0.0.1";
    /** InnoDB buffer pool, as before. */
    public static final String BUFFER_POOL = "256M";

    private MariaDbConfigFile() {
    }

    /**
     * Builds the file content.
     *
     * @param port TCP port
     * @param socket Unix socket file on Linux; empty on Windows, where no named pipe is opened
     * @return the content, with {@code \n} line ends
     */
    public static String render(int port, Optional<Path> socket) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        StringBuilder out = new StringBuilder();
        out.append("# Written by VectoraCloud on every start of the managed MariaDB. Changes here are lost;\n");
        out.append("# change cloud.yml (database section) instead. Paths are passed on the command line.\n");
        out.append("[mysqld]\n");
        out.append("port=").append(port).append('\n');
        out.append("bind-address=").append(BIND_ADDRESS).append('\n');
        socket.ifPresent(s -> out.append("socket=").append(checkedPath(s)).append('\n'));
        out.append("skip-name-resolve\n");
        out.append("character-set-server=utf8mb4\n");
        out.append("collation-server=utf8mb4_unicode_ci\n");
        out.append("# Sum of all connection pools stays below this, see ConnectionBudget.\n");
        out.append("max_connections=").append(ConnectionBudget.MANAGED_MAX_CONNECTIONS).append('\n');
        out.append("innodb_buffer_pool_size=").append(BUFFER_POOL).append('\n');
        out.append('\n');
        out.append("[client]\n");
        out.append("host=").append(BIND_ADDRESS).append('\n');
        out.append("port=").append(port).append('\n');
        socket.ifPresent(s -> out.append("socket=").append(checkedPath(s)).append('\n'));
        out.append("default-character-set=utf8mb4\n");
        return out.toString();
    }

    /** An option file value ends at the line break and cannot be quoted portably. */
    private static String checkedPath(Path path) {
        String text = path.toString();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) || c == '#' || c == ';' || c == '"' || c == '\'' || c == ' ') {
                throw new IllegalArgumentException("socket path contains a character the option file cannot hold");
            }
        }
        return text;
    }
}
