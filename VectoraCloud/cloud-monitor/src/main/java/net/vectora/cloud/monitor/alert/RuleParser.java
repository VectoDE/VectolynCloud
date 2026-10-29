package net.vectora.cloud.monitor.alert;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import net.vectora.cloud.monitor.alert.RuleCondition.Quantity;
import net.vectora.cloud.monitor.alert.RuleSyntaxException.Reason;
import net.vectora.cloud.monitor.metric.Metric;
import net.vectora.cloud.monitor.metric.MetricUnit;

/**
 * Parses alert expressions:
 *
 * <pre>
 * rule      = metric op quantity [ "for" duration | "within" duration ]
 *             [ "clear" "at" quantity ] [ "after" duration ]
 * op        = "&lt;" | "&lt;=" | "&gt;" | "&gt;="
 * quantity  = number [ unit ]          number = (0|[1-9][0-9]{0,9})("."[0-9]{1,3})?
 * unit      = "%" | "ms" | "KB" | "MB" | "GB" | "TB"
 * duration  = number-without-point ( "s" | "m" | "h" )
 * </pre>
 *
 * Words are separated by blanks; a unit may follow its number with or without a blank
 * ({@code 5GB}, {@code 5 GB}). Metric ids and keywords are lower case, byte units upper case and
 * powers of 1024. Examples: {@code tps < 18 for 60s clear at 19}, {@code heap > 90% for 1m},
 * {@code disk-free < 5GB clear at 6GB}, {@code crashes >= 3 within 10m}.
 *
 * <p>Rules: percent metrics need {@code %}, byte metrics a byte unit, {@code mspt} may have
 * {@code ms}, the rest no unit. Values stay within the metric's range; counts are whole numbers.
 * {@code crashes} needs {@code within} and takes no {@code for}; every other metric takes no
 * {@code within}. The clear value lies on the healthy side ({@code >=} the threshold for
 * {@code <} rules, {@code <=} for {@code >} rules). Durations are at most {@link #MAX_DURATION}.
 *
 * <p>Errors are {@link RuleSyntaxException} only. Jazzer fuzzes this class
 * ({@code RuleParserFuzzTest}).
 */
public final class RuleParser {

    /** Longest expression. */
    public static final int MAX_LENGTH = 160;

    /** Longest {@code for}, {@code within} and {@code after}. */
    public static final Duration MAX_DURATION = Duration.ofHours(24);

    private RuleParser() {
    }

    /**
     * Parses an expression.
     *
     * @param text the expression
     * @return the condition
     * @throws RuleSyntaxException if the text breaks a rule above
     */
    public static RuleCondition parse(String text) {
        if (text == null || text.isBlank()) {
            throw new RuleSyntaxException(Reason.EMPTY, 0);
        }
        if (text.length() > MAX_LENGTH) {
            throw new RuleSyntaxException(Reason.TOO_LONG, MAX_LENGTH);
        }
        return new Parser(lex(text), text.length()).rule();
    }

    /** Comma-separated metric ids, for error messages. */
    static String metricList() {
        return Arrays.stream(Metric.values()).map(Metric::id).collect(Collectors.joining(", "));
    }

    private enum Kind { WORD, OPERATOR, NUMBER, PERCENT }

    private record Token(Kind kind, String text, int position) {
    }

    private static List<Token> lex(String text) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t') {
                i++;
                continue;
            }
            int start = i;
            Kind kind;
            if (isLetter(c)) {
                i++;
                while (i < text.length() && (isLetter(text.charAt(i))
                        || text.charAt(i) == '-' && i + 1 < text.length() && isLetter(text.charAt(i + 1)))) {
                    i++;
                }
                kind = Kind.WORD;
            } else if (c >= '0' && c <= '9') {
                while (i < text.length() && (text.charAt(i) >= '0' && text.charAt(i) <= '9' || text.charAt(i) == '.')) {
                    i++;
                }
                kind = Kind.NUMBER;
            } else if (c == '<' || c == '>') {
                i++;
                if (i < text.length() && text.charAt(i) == '=') {
                    i++;
                }
                kind = Kind.OPERATOR;
            } else if (c == '%') {
                i++;
                kind = Kind.PERCENT;
            } else {
                throw new RuleSyntaxException(Reason.BAD_CHARACTER, i);
            }
            tokens.add(new Token(kind, text.substring(start, i), start));
        }
        return tokens;
    }

    private static boolean isLetter(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    private static final class Parser {

        private final List<Token> tokens;
        private final int end;
        private int next;

        Parser(List<Token> tokens, int end) {
            this.tokens = tokens;
            this.end = end;
        }

        RuleCondition rule() {
            Token metricToken = take();
            if (metricToken == null) {
                throw new RuleSyntaxException(Reason.EMPTY, 0);
            }
            if (metricToken.kind != Kind.WORD) {
                throw new RuleSyntaxException(Reason.UNKNOWN_METRIC, metricToken.position);
            }
            Metric metric = Metric.byId(metricToken.text)
                    .orElseThrow(() -> new RuleSyntaxException(Reason.UNKNOWN_METRIC, metricToken.position));
            Token op = take();
            if (op == null || op.kind != Kind.OPERATOR) {
                throw new RuleSyntaxException(Reason.EXPECTED_OPERATOR, op == null ? end : op.position);
            }
            Comparison comparison = Comparison.bySymbol(op.text)
                    .orElseThrow(() -> new RuleSyntaxException(Reason.EXPECTED_OPERATOR, op.position));
            Quantity threshold = quantity(metric);

            Duration holdFor = Duration.ZERO;
            Duration window = Duration.ZERO;
            if (peekWord("for")) {
                Token word = take();
                if (metric.isWindowCount()) {
                    throw new RuleSyntaxException(Reason.HOLD_NOT_ALLOWED, word.position);
                }
                holdFor = duration(false);
            } else if (peekWord("within")) {
                Token word = take();
                if (!metric.isWindowCount()) {
                    throw new RuleSyntaxException(Reason.WINDOW_NOT_ALLOWED, word.position);
                }
                window = duration(true);
            }
            if (metric.isWindowCount() && window.isZero()) {
                Token at = peek();
                throw new RuleSyntaxException(Reason.WINDOW_REQUIRED, at == null ? end : at.position);
            }

            Quantity clearAt = threshold;
            if (peekWord("clear")) {
                take();
                Token at = take();
                if (at == null || at.kind != Kind.WORD || !at.text.equals("at")) {
                    throw new RuleSyntaxException(Reason.UNEXPECTED_WORD, at == null ? end : at.position);
                }
                int position = peek() == null ? end : peek().position;
                clearAt = quantity(metric);
                boolean healthySide = comparison.watchesLow()
                        ? clearAt.value() >= threshold.value() : clearAt.value() <= threshold.value();
                if (!healthySide) {
                    throw new RuleSyntaxException(Reason.CLEAR_ON_WRONG_SIDE, position);
                }
            }
            Duration clearAfter = holdFor;
            if (peekWord("after")) {
                take();
                clearAfter = duration(false);
            }
            Token rest = take();
            if (rest != null) {
                throw new RuleSyntaxException(Reason.UNEXPECTED_WORD, rest.position);
            }
            return new RuleCondition(metric, comparison, threshold, holdFor, window, clearAt, clearAfter);
        }

        private Quantity quantity(Metric metric) {
            Token number = take();
            if (number == null || number.kind != Kind.NUMBER) {
                throw new RuleSyntaxException(Reason.EXPECTED_NUMBER, number == null ? end : number.position);
            }
            if (!number.text.matches("(0|[1-9][0-9]{0,9})(\\.[0-9]{1,3})?")) {
                throw new RuleSyntaxException(Reason.BAD_NUMBER, number.position);
            }
            double raw = Double.parseDouble(number.text);
            String unit = "";
            Token unitToken = peek();
            if (unitToken != null && (unitToken.kind == Kind.PERCENT
                    || unitToken.kind == Kind.WORD && isUnitWord(unitToken.text))) {
                take();
                unit = unitToken.text;
            }
            int unitPosition = unitToken == null ? end : unitToken.position;
            double factor = switch (metric.unit()) {
                case PERCENT -> unit.equals("%") ? 1 : -1;
                case MILLISECONDS -> unit.isEmpty() || unit.equals("ms") ? 1 : -1;
                case NUMBER -> unit.isEmpty() ? 1 : -1;
                case BYTES -> switch (unit) {
                    case "KB" -> 1024.0;
                    case "MB" -> 1024.0 * 1024;
                    case "GB" -> 1024.0 * 1024 * 1024;
                    case "TB" -> 1024.0 * 1024 * 1024 * 1024;
                    default -> -1;
                };
            };
            if (factor < 0) {
                throw new RuleSyntaxException(Reason.WRONG_UNIT, unit.isEmpty() ? number.position : unitPosition);
            }
            if (metric.unit() == MetricUnit.NUMBER && (metric == Metric.PLAYERS || metric == Metric.CRASHES
                    || metric == Metric.RESTARTS) && raw != Math.rint(raw)) {
                throw new RuleSyntaxException(Reason.NOT_WHOLE, number.position);
            }
            double value = raw * factor;
            if (value < metric.min() || value > metric.max()) {
                throw new RuleSyntaxException(Reason.OUT_OF_RANGE, number.position);
            }
            return new Quantity(number.text, unit, value);
        }

        private Duration duration(boolean positive) {
            Token number = take();
            if (number == null || number.kind != Kind.NUMBER || !number.text.matches("0|[1-9][0-9]{0,5}")) {
                throw new RuleSyntaxException(Reason.BAD_DURATION, number == null ? end : number.position);
            }
            Token unit = take();
            if (unit == null || unit.kind != Kind.WORD || !unit.text.matches("[smh]")) {
                throw new RuleSyntaxException(Reason.BAD_DURATION, unit == null ? end : unit.position);
            }
            long amount = Long.parseLong(number.text);
            Duration value = switch (unit.text) {
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                default -> Duration.ofHours(amount);
            };
            if (value.compareTo(MAX_DURATION) > 0 || positive && value.isZero()) {
                throw new RuleSyntaxException(Reason.BAD_DURATION, number.position);
            }
            return value;
        }

        private static boolean isUnitWord(String word) {
            return switch (word) {
                case "ms", "KB", "MB", "GB", "TB" -> true;
                default -> false;
            };
        }

        private boolean peekWord(String word) {
            Token token = peek();
            return token != null && token.kind == Kind.WORD && token.text.equals(word);
        }

        private Token peek() {
            return next < tokens.size() ? tokens.get(next) : null;
        }

        private Token take() {
            return next < tokens.size() ? tokens.get(next++) : null;
        }
    }
}
