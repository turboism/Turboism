package dev.turboism.ui.menu;

import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.host.EditorUiFamily;

import java.util.Objects;
import java.util.Optional;

/** One runtime-owned item under the bounded Turboism top-menu root. */
public record TopMenuItemDescriptor(
    String pluginId,
    String contributionId,
    String nativeItemId,
    String label,
    String actionId,
    int order
) {

    static final String ROOT_LABEL = "Turboism";

    public TopMenuItemDescriptor {
        pluginId = requireText(pluginId, "pluginId");
        contributionId = requireText(contributionId, "contributionId");
        nativeItemId = requireText(nativeItemId, "nativeItemId");
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
        final String[] path = Objects.requireNonNull(payload.menuPath(), "menuPath")
            .split("/", -1);
        if (path.length != 2) {
            if (path.length > 0 && ROOT_LABEL.equals(path[0].trim())) {
                throw new IllegalArgumentException(
                    "Turboism top-menu path must contain exactly one item segment"
                );
            }
            return Optional.empty();
        }
        final String root = path[0].trim();
        if (!ROOT_LABEL.equals(root)) {
            return Optional.empty();
        }
        final String pluginId = contribution.identity().pluginId();
        final String contributionId = contribution.identity().contributionId();
        return Optional.of(new TopMenuItemDescriptor(
            pluginId,
            contributionId,
            "turboism.menu." + pluginId + "." + contributionId,
            path[1].trim(),
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
