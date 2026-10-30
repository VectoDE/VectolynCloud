package net.vectolyn.cloud.launcher.libs;

import java.io.Serial;

/** The embedded libraries cannot be used. The launcher stops before loading any node code. */
public final class LibraryException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /** What went wrong. */
    public enum Kind {
        /** {@code libs.index} has a line that is not {@code <sha256>  <file name>}. */
        INDEX_INVALID,
        /** A file name in the index could leave {@code storage/libs} (zip slip) or is not a jar name. */
        UNSAFE_NAME,
        /** The index lists a jar that is not in {@code VectolynCloud.jar}. */
        MISSING,
        /** An unpacked jar does not have the SHA-256 from the index. */
        HASH_MISMATCH,
        /** Reading or writing a file failed. */
        IO
    }

    private final Kind kind;
    private final String subject;

    /**
     * Creates the exception.
     *
     * @param kind    what went wrong
     * @param subject the file name or index line concerned
     * @param detail  technical detail for the log
     * @param cause   underlying exception, may be {@code null}
     */
    public LibraryException(Kind kind, String subject, String detail, Throwable cause) {
        super(kind + " " + subject + ": " + detail, cause);
        this.kind = kind;
        this.subject = subject;
    }

    /**
     * Creates the exception without a cause.
     *
     * @param kind    what went wrong
     * @param subject the file name or index line concerned
     * @param detail  technical detail for the log
     */
    public LibraryException(Kind kind, String subject, String detail) {
        this(kind, subject, detail, null);
    }

    /**
     * Returns what went wrong.
     *
     * @return the kind
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Returns the file name or index line concerned.
     *
     * @return the subject
     */
    public String subject() {
        return subject;
    }
}
