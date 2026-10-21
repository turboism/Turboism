package dev.turboism.plugin.acp;

/** Wire protocol spoken by the installed executable; independent from its display identity. */
enum AgentProtocol {
    ACP,
    CODEX,
    CLAUDE,
    PI;

    static AgentProtocol forExecutable(final AgentProfile profile, final java.nio.file.Path executable) {
        final String name = executable
                .getFileName()
                .toString()
                .toLowerCase(java.util.Locale.ROOT)
                .replaceFirst("\\.(exe|cmd|bat|com)$", "");
        return switch (profile.id()) {
            case "codex" -> name.equals("codex") ? CODEX : ACP;
            case "claude" -> name.equals("claude") ? CLAUDE : ACP;
            case "pi" -> name.equals("pi") ? PI : ACP;
            default -> ACP;
        };
    }
}
