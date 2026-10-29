package net.vectora.cloud.monitor.metric;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-size history: once full, each new entry replaces the oldest. Thread-safe; the monitor
 * thread adds while console commands read.
 *
 * @param <T> entry type
 */
public final class RingBuffer<T> {

    private final Object[] entries;
    private int start;
    private int size;

    /**
     * Creates an empty buffer.
     *
     * @param capacity number of entries kept, at least 1
     */
    public RingBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        entries = new Object[capacity];
    }

    /** Number of entries the buffer keeps. */
    public int capacity() {
        return entries.length;
    }

    /** Adds an entry, dropping the oldest one when the buffer is full. */
    public synchronized void add(T entry) {
        if (entry == null) {
            throw new NullPointerException("entry");
        }
        if (size < entries.length) {
            entries[(start + size) % entries.length] = entry;
            size++;
        } else {
            entries[start] = entry;
            start = (start + 1) % entries.length;
        }
    }

    /** Number of entries now. */
    public synchronized int size() {
        return size;
    }

    /** All entries, oldest first. */
    @SuppressWarnings("unchecked")
    public synchronized List<T> snapshot() {
        List<T> copy = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            copy.add((T) entries[(start + i) % entries.length]);
        }
        return copy;
    }

    /** The newest entry, or null when empty. */
    @SuppressWarnings("unchecked")
    public synchronized T latest() {
        return size == 0 ? null : (T) entries[(start + size - 1) % entries.length];
    }

    /** Removes every entry. */
    public synchronized void clear() {
        java.util.Arrays.fill(entries, null);
        start = 0;
        size = 0;
    }
}
