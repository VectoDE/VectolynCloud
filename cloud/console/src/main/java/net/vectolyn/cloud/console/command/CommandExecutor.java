package net.vectolyn.cloud.console.command;

/**
 * Runs a command.
 *
 * <p>Throw {@link CommandException} for a mistake of the user (it is shown as an error, without a
 * stack trace), let {@link net.vectolyn.cloud.config.error.ConfigException} through for rejected
 * configuration changes, and {@link net.vectolyn.cloud.console.prompt.PromptCancelledException} when
 * a question was cancelled. Anything else is logged and shown as an internal error.
 */
@FunctionalInterface
public interface CommandExecutor {

    /** Runs the command with the parsed arguments. */
    void execute(CommandContext context) throws Exception;
}
