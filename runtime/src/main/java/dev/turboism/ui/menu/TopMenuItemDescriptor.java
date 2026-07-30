package dev.turboism.ui.menu;

import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.host.EditorUiFamily;

import java.util.Objects;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** One plugin-owned item under an arbitrary top-level and nested menu path. */
public record TopMenuItemDescriptor(
    String pluginId,
    String contributionId,
    String nativeItemId,
    String rootLabel,
    List<String> submenuPath,
    String label,
    String actionId,
    int order
    ) {
    public TopMenuItemDescriptor {
        pluginId = requireText(pluginId, "pluginId");
        contributionId = requireText(contributionId, "contributionId");
        nativeItemId = requireText(nativeItemId, "nativeItemId");
        rootLabel = requireText(rootLabel, "rootLabel");
        submenuPath = List.copyOf(Objects.requireNonNull(submenuPath, "submenuPath"));
        submenuPath.forEach(segment -> requireText(segment, "submenuPath segment"));
        label = requireText(label, "label");
        actionId = requireText(actionId, "actionId");
    }

    static Optional<TopMenuItemDescriptor> from(
        final EditorUiContribution<?> contribution
    ) {
        Objects.requireNonNull(contribution, "contribution");
        if (contribution.identity().family() != EditorUiFamily.MENU) {
            throw new IllegalArgumentException("top-menu contribution requires MENU identity");
        }
        if (!(contribution.descriptor() instanceof MenuRegistry.MenuContribution payload)) {
            throw new IllegalArgumentException("top-menu contribution payload is not a menu contribution");
        }
        final List<String> path = Arrays.stream(
                Objects.requireNonNull(payload.menuPath(), "menuPath").split("/", -1)
            )
            .map(String::trim)
            .toList();
        if (path.size() < 2) {
            throw new IllegalArgumentException(
                "top-menu path must contain a top-level menu and a leaf item"
            );
        }
        path.forEach(segment -> requireText(segment, "menuPath segment"));
        final String pluginId = contribution.identity().pluginId();
        final String contributionId = contribution.identity().contributionId();
        return Optional.of(new TopMenuItemDescriptor(
            pluginId,
            contributionId,
            "turboism.menu." + pluginId + "." + contributionId,
            path.get(0),
            path.subList(1, path.size() - 1),
            path.get(path.size() - 1),
            payload.actionId(),
            contribution.order()
        ));
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
