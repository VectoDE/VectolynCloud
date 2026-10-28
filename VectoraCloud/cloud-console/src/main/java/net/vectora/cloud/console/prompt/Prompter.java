package net.vectora.cloud.console.prompt;

import java.util.List;
import java.util.Objects;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.console.ConsoleOutput;
import net.vectora.cloud.console.Messages;
import net.vectora.cloud.console.Style;
import net.vectora.cloud.console.terminal.ConsoleTerminal;
import net.vectora.cloud.console.terminal.ReadResult;

/**
 * Asks {@link Question}s on the console. This is the API the setup assistant and other assistants
 * build on.
 *
 * <ul>
 *   <li>An invalid answer prints what is expected and asks again, up to {@value #MAX_ATTEMPTS}
 *       times in a row. The message never repeats the answer.</li>
 *   <li>Enter without an answer takes the default, if there is one.</li>
 *   <li>Ctrl+C, Ctrl+D, the end of stdin or typing {@value #CANCEL_WORD} cancel with
 *       {@link PromptCancelledException}. Without a terminal Ctrl+C does not reach the console, so
 *       {@value #CANCEL_WORD} is the way to cancel there.</li>
 *   <li>Answers are read as {@link ConsoleTerminal.LineKind#ANSWER}, secrets as
 *       {@link ConsoleTerminal.LineKind#SECRET} (masked); neither is ever stored in the history.
 *       If the terminal cannot hide the input ({@link ConsoleTerminal#hidesSecrets()}), a warning
 *       comes before the secret question.</li>
 *   <li>A line break or control character in an answer is rejected.</li>
 * </ul>
 */
public final class Prompter {

    /** Typing this cancels a question. */
    public static final String CANCEL_WORD = "!cancel";
    /** Invalid answers in a row before the question is given up. */
    public static final int MAX_ATTEMPTS = 10;

    private final ConsoleTerminal terminal;
    private final ConsoleOutput out;

    /** Asks on {@code terminal}, writing hints and errors to {@code out}. */
    public Prompter(ConsoleTerminal terminal, ConsoleOutput out) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.out = Objects.requireNonNull(out, "out");
    }

    /**
     * Asks until a valid answer comes.
     *
     * @throws PromptCancelledException if the question is cancelled or the input ends
     */
    public <T> T ask(Question<T> question) throws PromptCancelledException {
        Objects.requireNonNull(question, "question");
        Language language = out.language();
        if (question.helpText() != null) {
            out.println(Style.MUTED.apply(question.helpText().get(language), terminal.ansi()));
        }
        List<Question.Option<T>> options = question.options();
        for (int i = 0; i < options.size(); i++) {
            Question.Option<T> option = options.get(i);
            out.println("  " + (i + 1) + ") " + option.label().get(language)
                    + Style.MUTED.apply(" (" + option.key() + ")", terminal.ansi()));
        }
        String prompt = render(question, language);
        boolean secret = question.kind() == Question.Kind.SECRET;
        if (secret && !terminal.hidesSecrets()) {
            out.warning(Messages.SECRET_VISIBLE, CANCEL_WORD);
        }
        ConsoleTerminal.LineKind kind = secret ? ConsoleTerminal.LineKind.SECRET : ConsoleTerminal.LineKind.ANSWER;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            ReadResult result = terminal.readLine(prompt, kind);
            String typed = switch (result) {
                case ReadResult.Line line -> line.text();
                case ReadResult.Interrupted interrupted -> throw new PromptCancelledException(PromptCancelledException.Reason.CANCELLED);
                case ReadResult.EndOfInput end -> throw new PromptCancelledException(PromptCancelledException.Reason.END_OF_INPUT);
            };
            String text = secret ? typed : typed.strip();
            if (typed.strip().equals(CANCEL_WORD)) {
                throw new PromptCancelledException(PromptCancelledException.Reason.CANCELLED);
            }
            if (hasControlCharacter(typed)) {
                out.error(Messages.ANSWER_CONTROL);
                continue;
            }
            if (text.isEmpty()) {
                if (question.defaultAnswer() != null) {
                    return question.defaultAnswer();
                }
                out.error(Messages.ANSWER_REQUIRED);
                continue;
            }
            try {
                return question.accept(text);
            } catch (InvalidAnswerException e) {
                out.error(e.message(language));
            }
        }
        throw new PromptCancelledException(PromptCancelledException.Reason.TOO_MANY_ATTEMPTS);
    }

    private <T> String render(Question<T> question, Language language) {
        StringBuilder prompt = new StringBuilder(question.prompt().get(language));
        T fallback = question.defaultAnswer();
        String hint = switch (question.kind()) {
            case YES_NO -> (fallback == null ? Messages.YES_NO
                    : Boolean.TRUE.equals(fallback) ? Messages.YES_NO_DEFAULT_YES : Messages.YES_NO_DEFAULT_NO).get(language);
            case NUMBER -> "(" + question.min() + "-" + question.max() + ")"
                    + (fallback == null ? "" : " [" + question.display(fallback) + "]");
            case SECRET -> fallback == null ? "" : Messages.SECRET_KEEP.get(language);
            case CHOICE -> Messages.CHOICE_PROMPT.get(language) + (fallback == null ? "" : " [" + question.display(fallback) + "]");
            case TEXT -> fallback == null ? "" : "[" + question.display(fallback) + "]";
        };
        if (!hint.isEmpty()) {
            prompt.append(' ').append(Style.MUTED.apply(hint, terminal.ansi()));
        }
        prompt.append(": ");
        return prompt.toString();
    }

    private static boolean hasControlCharacter(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7f || (c >= 0x80 && c < 0xa0) || c == ' ' || c == ' ') {
                return true;
            }
        }
        return false;
    }
}
