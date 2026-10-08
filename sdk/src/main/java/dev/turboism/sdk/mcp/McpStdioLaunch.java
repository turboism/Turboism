package dev.turboism.sdk.mcp;

import dev.turboism.sdk.Incubating;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Credential-free stdio launch descriptor for a Turboism-owned MCP bridge process.
 *
 * <p>The descriptor carries an absolute launcher path and a bounded argument vector; it never
 * carries the bearer token. The bridge process reads the token itself inside the MCP plugin
 * state directory.</p>
 */
@Incubating
public final class McpStdioLaunch {

    private static final int MAX_ARGS = 16;
    private static final int MAX_TEXT_CHARS = 4096;

    private final String command;
    private final List<String> args;

    /**
     * Creates a validated stdio launch descriptor.
     *
     * @param command absolute path of the process launcher
     * @param args launch arguments, at most 16 entries of at most 4096 characters each,
     *     free of ISO control characters
     */
    public McpStdioLaunch(final String command, final List<String> args) {
        this.command = requireCommand(command);
        this.args = requireArgs(args);
    }

    /** @return the absolute launcher path */
    public String command() {
        return command;
    }

    /** @return the immutable launch argument vector */
    public List<String> args() {
        return args;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) return true;
        if (!(other instanceof McpStdioLaunch launch)) return false;
        return command.equals(launch.command) && args.equals(launch.args);
    }

    @Override
    public int hashCode() {
        return Objects.hash(command, args);
    }

    @Override
    public String toString() {
        return "McpStdioLaunch[args=" + args.size() + "]";
    }

    private static String requireCommand(final String value) {
        final String command = requireText(value, "command");
        try {
            if (!Path.of(command).isAbsolute()) {
                throw new IllegalArgumentException("command must be an absolute path");
            }
        } catch (InvalidPathException invalid) {
            throw new IllegalArgumentException("command is not a valid path", invalid);
        }
        return command;
    }

    private static List<String> requireArgs(final List<String> value) {
        final List<String> args = List.copyOf(Objects.requireNonNull(value, "args"));
        if (args.size() > MAX_ARGS) {
            throw new IllegalArgumentException("args must not exceed " + MAX_ARGS + " entries");
        }
        for (String arg : args) {
            requireText(arg, "args entry");
        }
        return args;
    }

    private static String requireText(final String value, final String name) {
        final String text = Objects.requireNonNull(value, name);
        if (text.isBlank() || text.length() > MAX_TEXT_CHARS || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return text;
    }
}
