package dev.turboism.plugin.acp;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, validated ACP agent process launch configuration.
 *
 * <p>The command is an argv list that is never joined into a shell string, so a persisted
 * executable path or argument cannot gain command-string semantics.</p>
 */
record AgentLaunchSpec(List<String> command, Path workingDirectory, java.util.Map<String, String> environment) {

    static final int MAX_ARGUMENTS = 64;
    static final int MAX_ARGUMENT_CHARS = 4096;

    AgentLaunchSpec {
        final java.util.ArrayList<String> checkedCommand = new java.util.ArrayList<>();
        for (String element : Objects.requireNonNull(command, "command")) {
            checkedCommand.add(requireArgument(element));
        }
        if (checkedCommand.isEmpty() || checkedCommand.size() > MAX_ARGUMENTS) {
            throw new IllegalArgumentException("agent command is invalid");
        }
        command = List.copyOf(checkedCommand);
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath()
                .normalize();
        final java.util.LinkedHashMap<String, String> checkedEnvironment = new java.util.LinkedHashMap<>();
        Objects.requireNonNull(environment, "environment").forEach((name, value) -> {
            final String key = Objects.requireNonNull(name, "environment name");
            final String text = Objects.requireNonNull(value, "environment value");
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") || text.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("process environment is invalid");
            }
            checkedEnvironment.put(key, text);
        });
        environment = java.util.Map.copyOf(checkedEnvironment);
    }

    AgentLaunchSpec(final List<String> command, final Path workingDirectory) {
        this(command, workingDirectory, java.util.Map.of());
    }

    /** Returns the executable path, the first argv element. */
    String executable() {
        return command.get(0);
    }

    private static String requireArgument(final String value) {
        final String argument = Objects.requireNonNull(value, "command element").strip();
        if (argument.isEmpty()
                || argument.length() > MAX_ARGUMENT_CHARS
                || argument.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("agent command element is invalid");
        }
        return argument;
    }
}
