package net.vectolyn.protocol;

/**
 * Version of the wire format, sent in every frame and in {@code Hello}.
 *
 * <p>Compatibility rules:
 * <ul>
 *   <li>Peers can talk only if their major versions are equal. A major version changes when
 *       the frame layout, a message id or the encoding of an existing message changes.</li>
 *   <li>A minor version only adds: new message types, new enum codes. A connection uses the
 *       lower minor version of both peers ({@link #negotiate}), and neither side sends anything
 *       introduced after it.</li>
 * </ul>
 *
 * @param major major version, 0 to 255
 * @param minor minor version, 0 to 255
 */
public record ProtocolVersion(int major, int minor) implements Comparable<ProtocolVersion> {

    /** Largest value of each component; both are sent as one unsigned byte. */
    public static final int MAX_COMPONENT = 255;

    /**
     * Creates a version.
     *
     * @throws IllegalArgumentException if a component is outside 0 to {@value #MAX_COMPONENT}
     */
    public ProtocolVersion {
        if (major < 0 || major > MAX_COMPONENT || minor < 0 || minor > MAX_COMPONENT) {
            throw new IllegalArgumentException("version components must be 0 to " + MAX_COMPONENT
                    + ", got " + major + "." + minor);
        }
    }

    /** Returns whether a peer with {@code other} can talk to a peer with this version. */
    public boolean isCompatibleWith(ProtocolVersion other) {
        return major == other.major;
    }

    /**
     * Returns the version a connection between this and {@code other} uses: the lower of both.
     *
     * @throws IllegalArgumentException if the major versions differ
     */
    public ProtocolVersion negotiate(ProtocolVersion other) {
        if (!isCompatibleWith(other)) {
            throw new IllegalArgumentException("incompatible protocol versions " + this + " and " + other);
        }
        return compareTo(other) <= 0 ? this : other;
    }

    @Override
    public int compareTo(ProtocolVersion other) {
        int byMajor = Integer.compare(major, other.major);
        return byMajor != 0 ? byMajor : Integer.compare(minor, other.minor);
    }

    /** Reads a version (two unsigned bytes). */
    public static ProtocolVersion read(PacketReader in) {
        int major = in.readUnsignedByte();
        int minor = in.readUnsignedByte();
        return new ProtocolVersion(major, minor);
    }

    /** Writes this version as two unsigned bytes. */
    public void write(PacketWriter out) {
        out.writeByte(major).writeByte(minor);
    }

    /** Returns {@code major.minor}. */
    @Override
    public String toString() {
        return major + "." + minor;
    }
}
