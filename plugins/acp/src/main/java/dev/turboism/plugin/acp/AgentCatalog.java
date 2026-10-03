package dev.turboism.plugin.acp;

import java.util.List;
import java.util.Optional;

/**
 * Built-in catalog of mainstream ACP v1 agents.
 *
 * <p>Every entry was checked against its upstream package or ACP registry entry: the executable
 * is launched directly with the listed arguments and speaks JSON-RPC 2.0 over stdio. Agents whose
 * upstream needs an additional login subcommand expose it through {@code loginArguments}.</p>
 */
final class AgentCatalog {

    static final String CUSTOM_AGENT_ID = "custom";

    private static final List<AgentProfile> PROFILES = List.of(
            new AgentProfile(
                    "claude",
                    "Claude Agent (ACP)",
                    List.of("claude-agent-acp"),
                    List.of(),
                    List.of(),
                    "npm install -g @agentclientprotocol/claude-agent-acp",
                    "https://github.com/agentclientprotocol/claude-agent-acp"),
            new AgentProfile(
                    "codex",
                    "Codex (ACP)",
                    List.of("codex-acp"),
                    List.of(),
                    List.of("login"),
                    "npm install -g @agentclientprotocol/codex-acp",
                    "https://github.com/agentclientprotocol/codex-acp"),
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
                    "pi",
                    "Pi (ACP)",
                    List.of("pi-acp"),
                    List.of(),
                    List.of(),
                    "npm install -g @automatalabs/pi-acp",
                    "https://www.npmjs.com/package/@automatalabs/pi-acp"),
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
}
