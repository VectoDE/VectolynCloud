package net.vectora.cloud.monitor.alert;

import java.io.Serial;
import java.util.Objects;

/**
 * An alert rule expression could not be parsed. The message names the position and what was
 * expected, never the text itself, so a value pasted into the wrong field cannot reach a log.
 */
public final class RuleSyntaxException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** What was wrong. */
    public enum Reason {
        /** Nothing but blanks. */
        EMPTY("the rule is empty"),
        /** Longer than {@link RuleParser#MAX_LENGTH}. */
        TOO_LONG("the rule is longer than " + RuleParser.MAX_LENGTH + " characters"),
        /** A character outside printable ASCII, or one that belongs to no word, number or operator. */
        BAD_CHARACTER("unexpected character"),
        /** The first word is not a metric. */
        UNKNOWN_METRIC("unknown metric, expected one of " + RuleParser.metricList()),
        /** No {@code <}, {@code <=}, {@code >} or {@code >=} after the metric. */
        EXPECTED_OPERATOR("expected <, <=, > or >="),
        /** No number where one belongs. */
        EXPECTED_NUMBER("expected a number such as 18, 90% or 5GB"),
        /** A number with more than 10 digits before or 3 after the point, or a leading zero. */
        BAD_NUMBER("numbers have up to 10 digits, optionally a point and up to 3 decimals, no leading zero"),
        /** The unit does not fit the metric. */
        WRONG_UNIT("the unit does not fit the metric"),
        /** The value is outside what the metric can reach. */
        OUT_OF_RANGE("the value is outside the range of the metric"),
        /** A count metric got a fraction. */
        NOT_WHOLE("this metric counts, the value must be a whole number"),
        /** A duration is malformed or too long. */
        BAD_DURATION("expected a duration such as 30s, 5m or 1h, at most " + RuleParser.MAX_DURATION.toHours() + "h"),
        /** {@code crashes} needs {@code within <duration>}. */
        WINDOW_REQUIRED("crashes needs a window: crashes >= 3 within 10m"),
        /** {@code within} on a metric that is not counted in a window. */
        WINDOW_NOT_ALLOWED("within only fits crashes; use for <duration>"),
        /** {@code for} on a metric that is counted in a window. */
        HOLD_NOT_ALLOWED("crashes is counted in a window; use within <duration>"),
        /** The clear value is on the wrong side of the threshold. */
        CLEAR_ON_WRONG_SIDE("clear at must be on the healthy side of the threshold"),
        /** A word that does not belong there. */
        UNEXPECTED_WORD("unexpected word, expected for, within, clear at or after");

        private final String text;

        Reason(String text) {
            this.text = text;
        }

        /** Description of the rule that was broken. */
        public String text() {
            return text;
        }
    }

    private final Reason reason;
    private final int position;

    /**
     * Creates the exception.
     *
     * @param reason   what was wrong
     * @param position 0-based character position
     */
    public RuleSyntaxException(Reason reason, int position) {
        super(Objects.requireNonNull(reason, "reason").text() + " (at character " + (position + 1) + ")");
        this.reason = reason;
        this.position = position;
    }

    /** What was wrong. */
    public Reason reason() {
        return reason;
    }

    /** 0-based position of the problem. */
    public int position() {
        return position;
    }
}
