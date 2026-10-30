package net.vectolyn.cloud.monitor.alert;

import java.util.Arrays;
import java.util.Optional;

/** Operator of an alert rule. */
public enum Comparison {
    /** Fires while the value is below the threshold. */
    LESS("<"),
    /** Fires while the value is at or below the threshold. */
    LESS_OR_EQUAL("<="),
    /** Fires while the value is above the threshold. */
    GREATER(">"),
    /** Fires while the value is at or above the threshold. */
    GREATER_OR_EQUAL(">=");

    private final String symbol;

    Comparison(String symbol) {
        this.symbol = symbol;
    }

    /** The operator as written in a rule. */
    public String symbol() {
        return symbol;
    }

    /** Whether the rule watches for low values ({@code <}, {@code <=}). */
    public boolean watchesLow() {
        return this == LESS || this == LESS_OR_EQUAL;
    }

    /** Whether {@code value} meets the condition. */
    public boolean test(double value, double threshold) {
        return switch (this) {
            case LESS -> value < threshold;
            case LESS_OR_EQUAL -> value <= threshold;
            case GREATER -> value > threshold;
            case GREATER_OR_EQUAL -> value >= threshold;
        };
    }

    /** Finds the operator for its symbol. */
    public static Optional<Comparison> bySymbol(String symbol) {
        return Arrays.stream(values()).filter(c -> c.symbol.equals(symbol)).findFirst();
    }
}
