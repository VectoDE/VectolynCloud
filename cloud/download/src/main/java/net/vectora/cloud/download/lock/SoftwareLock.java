package net.vectora.cloud.download.lock;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The content of {@code software.lock.yml}: one {@link LockedArtifact} per name. Immutable;
 * every change returns a new lock.
 *
 * <p>Names are what the cloud calls an artifact, for example {@code paper}, {@code velocity} or
 * {@code viaversion-velocity}. They are lower case with digits, dots and dashes.
 */
public final class SoftwareLock {

    /** The format version this code reads and writes. */
    public static final int SCHEMA_VERSION = 1;

    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9.-]{0,63}");

    private static final SoftwareLock EMPTY = new SoftwareLock(Map.of());

    private final Map<String, LockedArtifact> entries;

    private SoftwareLock(Map<String, LockedArtifact> entries) {
        this.entries = Collections.unmodifiableMap(new TreeMap<>(entries));
    }

    /**
     * Returns a lock without entries.
     *
     * @return the empty lock
     */
    public static SoftwareLock empty() {
        return EMPTY;
    }

    /**
     * Creates a lock from entries.
     *
     * @param entries the entries by name
     * @return the lock
     * @throws IllegalArgumentException on an invalid name
     */
    public static SoftwareLock of(Map<String, LockedArtifact> entries) {
        entries.forEach((name, entry) -> {
            checkName(name);
            Objects.requireNonNull(entry, name);
        });
        return new SoftwareLock(entries);
    }

    /**
     * Returns all entries.
     *
     * @return the entries by name, sorted
     */
    public Map<String, LockedArtifact> entries() {
        return entries;
    }

    /**
     * Looks up an entry.
     *
     * @param name the name
     * @return the entry, or empty
     */
    public Optional<LockedArtifact> get(String name) {
        return Optional.ofNullable(entries.get(name));
    }

    /**
     * Records a new file for a name. The current entry, if it is a different file, becomes the
     * {@code previous} of the new one; an older previous is dropped.
     *
     * @param name the name
     * @param artifact the new entry; its own previous is ignored
     * @return the new lock
     */
    public SoftwareLock put(String name, LockedArtifact artifact) {
        checkName(name);
        Objects.requireNonNull(artifact, "artifact");
        LockedArtifact current = entries.get(name);
        LockedArtifact next;
        if (current == null) {
            next = artifact.withoutPrevious();
        } else if (current.sameFileAs(artifact)) {
            next = artifact.withPrevious(current.previous());
        } else {
            next = artifact.withPrevious(current);
        }
        Map<String, LockedArtifact> copy = new TreeMap<>(entries);
        copy.put(name, next);
        return new SoftwareLock(copy);
    }

    /**
     * Goes back to the previous file of a name. The two entries swap places, so a second rollback
     * returns to where the first one started.
     *
     * @param name the name
     * @return the new lock
     * @throws IllegalStateException if the name has no previous entry
     */
    public SoftwareLock rollback(String name) {
        LockedArtifact current = entries.get(name);
        if (current == null || current.previous() == null) {
            throw new IllegalStateException("nothing to roll back for " + name);
        }
        Map<String, LockedArtifact> copy = new TreeMap<>(entries);
        copy.put(name, current.previous().withPrevious(current));
        return new SoftwareLock(copy);
    }

    /**
     * Removes a name.
     *
     * @param name the name
     * @return the new lock
     */
    public SoftwareLock remove(String name) {
        Map<String, LockedArtifact> copy = new TreeMap<>(entries);
        copy.remove(name);
        return new SoftwareLock(copy);
    }

    static void checkName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("lock name must match " + NAME.pattern() + ": " + name);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SoftwareLock lock && lock.entries.equals(entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return "SoftwareLock" + entries.keySet();
    }
}
