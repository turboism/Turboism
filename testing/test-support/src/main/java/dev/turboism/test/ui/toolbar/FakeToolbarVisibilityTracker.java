package dev.turboism.test.ui.toolbar;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, thread-safe tracker for visible main and palette toolbar contributions per plugin.
 */
public final class FakeToolbarVisibilityTracker {

    private final ConcurrentHashMap<String, Set<String>> visibleByPlugin = new ConcurrentHashMap<>();

    public void markVisible(String pluginId, String contributionId, String toolbarKind) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(contributionId, "contributionId");
        Objects.requireNonNull(toolbarKind, "toolbarKind");
        if (!"main".equals(toolbarKind) && !"palette".equals(toolbarKind)) {
            throw new IllegalArgumentException(
                "toolbarKind must be 'main' or 'palette': " + toolbarKind
            );
        }
        visibleByPlugin
            .computeIfAbsent(pluginId, k -> Collections.synchronizedSet(new HashSet<>()))
            .add(contributionId);
    }

    public void markHidden(String pluginId, String contributionId) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(contributionId, "contributionId");
        Set<String> visible = visibleByPlugin.get(pluginId);
        if (visible != null) {
            visible.remove(contributionId);
        }
    }

    public Set<String> visibleFor(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        Set<String> visible = visibleByPlugin.get(pluginId);
        return visible == null ? Set.of() : Set.copyOf(visible);
    }

    public boolean isVisible(String pluginId, String contributionId) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(contributionId, "contributionId");
        Set<String> visible = visibleByPlugin.get(pluginId);
        return visible != null && visible.contains(contributionId);
    }
}
