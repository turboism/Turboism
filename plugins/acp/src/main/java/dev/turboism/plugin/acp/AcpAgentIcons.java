package dev.turboism.plugin.acp;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.Icon;

/** Bundled provider marks, tinted by the current theme. See META-INF/licenses/agent-icons.md. */
final class AcpAgentIcons {
    private static final Map<String, Icon> ICONS = loadIcons();
    private static final Icon FALLBACK = AcpIcons.icon(AcpIcons.Kind.AGENT);

    private AcpAgentIcons() {}

    static Icon icon(final String agentId) {
        return ICONS.getOrDefault(agentId, FALLBACK);
    }

    private static Map<String, Icon> loadIcons() {
        final Map<String, Icon> icons = new LinkedHashMap<>();
        for (AgentProfile profile : AgentCatalog.profiles()) {
            final String path = "/icons/agents/" + profile.id() + ".png";
            icons.put(profile.id(), AcpThemedIcon.load(path, 14));
        }
        return Map.copyOf(icons);
    }
}
