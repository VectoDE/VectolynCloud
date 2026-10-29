package net.vectora.cloud.launcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.vectora.cloud.launcher.platform.SystemAccess;

/** Scriptable {@link SystemAccess} for tests. Nothing touches the real system. */
public final class FakeSystemAccess implements SystemAccess {

    private final Map<String, String> env = new HashMap<>();
    private final Map<String, String> properties = new HashMap<>();
    private final Set<Path> existing = new HashSet<>();
    private final Map<Path, String> files = new HashMap<>();
    private final Map<List<String>, String> commands = new HashMap<>();
    private final List<List<String>> executed = new ArrayList<>();
    private Long unixUid;
    private List<String> windowsSids;
    private boolean terminal;

    /** Creates an empty fake: Linux on x86_64, nothing set. */
    public FakeSystemAccess() {
        properties.put("os.name", "Linux");
        properties.put("os.arch", "amd64");
        properties.put("user.language", "en");
        properties.put("java.version", "21.0.5");
    }

    /** Builds a fake for a normal Linux user (uid 1000). */
    public static FakeSystemAccess linuxUser() {
        return new FakeSystemAccess().uid(1000L);
    }

    /** Builds a fake for Linux root. */
    public static FakeSystemAccess linuxRoot() {
        return new FakeSystemAccess().uid(0L);
    }

    public FakeSystemAccess env(String name, String value) {
        env.put(name, value);
        return this;
    }

    public FakeSystemAccess property(String name, String value) {
        properties.put(name, value);
        return this;
    }

    public FakeSystemAccess file(String path, String content) {
        files.put(Path.of(path), content);
        existing.add(Path.of(path));
        return this;
    }

    public FakeSystemAccess exists(String path) {
        existing.add(Path.of(path));
        return this;
    }

    public FakeSystemAccess command(String output, String... command) {
        commands.put(List.of(command), output);
        return this;
    }

    public FakeSystemAccess uid(Long uid) {
        this.unixUid = uid;
        return this;
    }

    public FakeSystemAccess windowsSids(String... sids) {
        this.windowsSids = List.of(sids);
        return this;
    }

    public FakeSystemAccess terminal(boolean value) {
        this.terminal = value;
        return this;
    }

    public List<List<String>> executed() {
        return executed;
    }

    @Override
    public Optional<String> env(String name) {
        return Optional.ofNullable(env.get(name));
    }

    @Override
    public Optional<String> property(String name) {
        return Optional.ofNullable(properties.get(name));
    }

    @Override
    public boolean exists(Path path) {
        return existing.contains(path);
    }

    @Override
    public Optional<String> readFile(Path path) {
        return Optional.ofNullable(files.get(path));
    }

    @Override
    public Optional<String> run(List<String> command) {
        executed.add(command);
        return Optional.ofNullable(commands.get(command));
    }

    @Override
    public Optional<Long> unixUid() {
        return Optional.ofNullable(unixUid);
    }

    @Override
    public Optional<List<String>> windowsGroupSids() {
        return Optional.ofNullable(windowsSids);
    }

    @Override
    public boolean interactiveTerminal() {
        return terminal;
    }
}
