package dev.turboism.plugin.acp;

import java.util.List;
import java.util.Objects;

/**
 * Static launch metadata for one known ACP agent.
 *
 * <p>A profile never stores credentials and never downloads anything; it describes executable
 * names to discover on the user's machine, the argv that puts the program into ACP mode, an
 * optional login subcommand for agents without protocol authentication, and where to get it.</p>
 */
record AgentProfile(
        String id,
        String displayName,
        List<String> executableCandidates,
        List<String> arguments,
        List<String> loginArguments,
        String installHint,
        String homepage) {

    AgentProfile {
        id = requireText(id, "id");
        displayName = requireText(displayName, "displayName");
        executableCandidates = List.copyOf(Objects.requireNonNull(executableCandidates, "executableCandidates"));
        arguments = List.copyOf(Objects.requireNonNullElse(arguments, List.of()));
        loginArguments = List.copyOf(Objects.requireNonNullElse(loginArguments, List.of()));
        installHint = Objects.requireNonNullElse(installHint, "");
        homepage = Objects.requireNonNullElse(homepage, "");
        if (executableCandidates.isEmpty()) {
            throw new IllegalArgumentException("an agent profile needs at least one executable name");
        }
        executableCandidates.forEach(name -> requireText(name, "executable"));
    }

    private static String requireText(final String value, final String name) {
        final String text = Objects.requireNonNull(value, name);
        if (text.isBlank() || text.indexOf('\0') >= 0 || text.length() > 4096) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return text;
    }
}
