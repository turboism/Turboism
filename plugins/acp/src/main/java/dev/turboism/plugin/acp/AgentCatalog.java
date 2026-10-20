package dev.turboism.plugin.acp;

import java.util.List;
import java.util.Optional;

/** Built-in native CLI providers and compatible ACP agents. */
final class AgentCatalog {

    static final String CUSTOM_AGENT_ID = "custom";

    private static final List<AgentProfile> PROFILES = List.of(
            new AgentProfile(
                    "claude",
                    "Claude Code",
                    List.of("claude", "claude-agent-acp"),
                    List.of(),
                    List.of(),
                    "https://code.claude.com/docs/en/setup",
                    "https://code.claude.com"),
            new AgentProfile(
                    "codex",
                    "Codex",
                    List.of("codex", "codex-acp"),
                    List.of(),
                    List.of("login"),
                    "npm install -g @openai/codex",
                    "https://developers.openai.com/codex/cli"),
            new AgentProfile(
                    "gemini",
                    "Gemini CLI",
                    List.of("gemini"),
                    List.of("--experimental-acp"),
                    List.of(),
                    "npm install -g @google/gemini-cli",
                    "https://geminicli.com"),
            new AgentProfile(
                    "opencode",
                    "OpenCode",
                    List.of("opencode"),
                    List.of("acp"),
                    List.of("auth", "login"),
                    "npm install -g opencode-ai",
                    "https://opencode.ai"),
            new AgentProfile(
                    "pi", "Pi", List.of("pi", "pi-acp"), List.of(), List.of(), "https://pi.dev", "https://pi.dev"),
            new AgentProfile(
                    "devin",
                    "Devin CLI",
                    List.of("devin"),
                    List.of("acp"),
                    List.of("auth", "login"),
                    "https://devin.ai",
                    "https://devin.ai"),
            new AgentProfile(
                    "antigravity",
                    "Google Antigravity (agy-acp)",
                    List.of("agy-acp"),
                    List.of(),
                    List.of(),
                    "npm install -g agy-acp",
                    "https://www.npmjs.com/package/agy-acp"));

    private AgentCatalog() {}

    static List<AgentProfile> profiles() {
        return PROFILES;
    }

    static Optional<AgentProfile> profile(final String id) {
        return PROFILES.stream().filter(profile -> profile.id().equals(id)).findFirst();
    }

    /** Fallback agent ID when no catalog agent executable is found on the system. */
    static String fallbackAgentId() {
        return PROFILES.get(0).id();
    }

    /** Finds the first catalog profile whose executable candidate is discovered. */
    static Optional<AgentProfile> findFirstAvailable() {
        for (AgentProfile profile : PROFILES) {
            final Optional<java.nio.file.Path> located = AgentAvailability.locate(profile);
            if (located.isPresent()) {
                return Optional.of(profile);
            }
        }
        return Optional.empty();
    }

    /** Returns all catalog profiles currently discovered on this system. */
    static List<AgentProfile> availableProfiles() {
        final java.util.ArrayList<AgentProfile> available = new java.util.ArrayList<>();
        for (AgentProfile profile : PROFILES) {
            final Optional<java.nio.file.Path> located = AgentAvailability.locate(profile);
            if (located.isPresent()) {
                available.add(profile);
            }
        }
        return List.copyOf(available);
    }

    /**
     * Returns the catalog default fallback agent ID without performing background probes.
     */
    static String defaultAgentId() {
        return fallbackAgentId();
    }
}
