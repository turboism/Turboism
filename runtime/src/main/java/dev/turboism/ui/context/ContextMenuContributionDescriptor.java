package dev.turboism.ui.context;

import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.host.EditorUiFamily;

import java.util.Objects;
import java.util.Set;

/** Runtime-normalized object context-menu contribution. */
public record ContextMenuContributionDescriptor(
    String pluginId,
    String contributionId,
    String actionId,
    String label,
    String icon,
    ContextMenuRegistry.Location location,
    Set<ContextMenuRegistry.ObjectKind> objectKinds,
    int priority
) {
    public ContextMenuContributionDescriptor {
        pluginId = requireText(pluginId, "pluginId");
        contributionId = requireText(contributionId, "contributionId");
        actionId = requireText(actionId, "actionId");
        label = requireText(label, "label");
        location = Objects.requireNonNull(location, "location");
        objectKinds = Set.copyOf(Objects.requireNonNull(objectKinds, "objectKinds"));
    }

    public static ContextMenuContributionDescriptor from(
        final EditorUiContribution<?> contribution
    ) {
        Objects.requireNonNull(contribution, "contribution");
        if (contribution.identity().family() != EditorUiFamily.CONTEXT_MENU) {
            throw new IllegalArgumentException("context-menu descriptor requires CONTEXT_MENU family");
        }
        if (!(contribution.descriptor() instanceof ContextMenuRegistry.ContextMenuContribution value)) {
            throw new IllegalArgumentException("context-menu contribution descriptor has the wrong type");
        }
        return new ContextMenuContributionDescriptor(
            contribution.identity().pluginId(),
            value.id(),
            value.actionId(),
            value.label(),
            value.icon(),
            value.location(),
            value.objectKinds(),
            value.priority()
        );
    }

    /** Empty selections never match; mixed selections require every item to match. */
    public boolean matches(final ContextMenuSelection selection) {
        Objects.requireNonNull(selection, "selection");
        return selection.location() == location
            && !selection.items().isEmpty()
            && selection.items().stream().allMatch(item -> objectKinds.contains(item.kind()));
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
