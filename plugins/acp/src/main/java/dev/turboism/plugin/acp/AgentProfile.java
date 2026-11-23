package dev.turboism.plugin.acp;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        Map<String, List<String>> candidateArguments,
        Map<String, List<String>> candidateLoginArguments,
        String installHint,
        String homepage) {

    AgentProfile(
            final String id,
            final String displayName,
            final List<String> executableCandidates,
            final List<String> arguments,
            final List<String> loginArguments,
            final String installHint,
            final String homepage) {
        this(
                id,
                displayName,
                executableCandidates,
                arguments,
                loginArguments,
                Map.of(),
                Map.of(),
                installHint,
                homepage);
    }

    AgentProfile {
        id = requireText(id, "id");
        displayName = requireText(displayName, "displayName");
        executableCandidates = List.copyOf(Objects.requireNonNull(executableCandidates, "executableCandidates"));
        arguments = List.copyOf(Objects.requireNonNullElse(arguments, List.of()));
        loginArguments = List.copyOf(Objects.requireNonNullElse(loginArguments, List.of()));
        candidateArguments = copyCandidateMap(candidateArguments);
        candidateLoginArguments = copyCandidateMap(candidateLoginArguments);
        installHint = Objects.requireNonNullElse(installHint, "");
        homepage = Objects.requireNonNullElse(homepage, "");
        if (executableCandidates.isEmpty()) {
            throw new IllegalArgumentException("an agent profile needs at least one executable name");
        }
        executableCandidates.forEach(name -> requireText(name, "executable"));
    }

    /** Resolves ACP launch arguments for the selected executable candidate. */
    List<String> argumentsFor(final Path executable) {
        final String name = AgentProtocol.normalizedExecutableName(executable);
        return candidateArguments.getOrDefault(name, arguments);
    }

    /** Resolves interactive terminal login arguments for the selected executable candidate. */
    List<String> loginArgumentsFor(final Path executable) {
        final String name = AgentProtocol.normalizedExecutableName(executable);
        return candidateLoginArguments.getOrDefault(name, loginArguments);
    }

    private static Map<String, List<String>> copyCandidateMap(final Map<String, List<String>> source) {
        if (source == null || source.isEmpty()) return Map.of();
        final Map<String, List<String>> copied = new LinkedHashMap<>();
        source.forEach((key, value) ->
                copied.put(requireText(key, "candidate"), List.copyOf(Objects.requireNonNullElse(value, List.of()))));
        return Map.copyOf(copied);
    }

    private static String requireText(final String value, final String name) {
        final String text = Objects.requireNonNull(value, name);
        if (text.isBlank() || text.indexOf('\0') >= 0 || text.length() > 4096) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return text;
    }
}
