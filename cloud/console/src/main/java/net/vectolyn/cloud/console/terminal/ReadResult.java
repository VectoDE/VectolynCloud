package net.vectolyn.cloud.console.terminal;

import java.util.Objects;

/** Outcome of reading a line. */
public sealed interface ReadResult {

    /** A line was entered (without the line break). */
    record Line(String text) implements ReadResult {
        /** The text is required. */
        public Line {
            Objects.requireNonNull(text, "text");
        }

        /** Hides the content: a line may be a secret. */
        @Override
        public String toString() {
            return "Line[" + text.length() + " chars]";
        }
    }

    /** Ctrl+C on a real terminal. */
    record Interrupted() implements ReadResult {
    }

    /** Ctrl+D on an empty line, or the end of stdin. */
    record EndOfInput() implements ReadResult {
    }
}
