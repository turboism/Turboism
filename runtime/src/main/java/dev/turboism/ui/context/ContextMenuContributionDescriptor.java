package dev.turboism.ui.context;

import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.host.EditorUiFamily;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Runtime-normalized object context-menu contribution. */
public record ContextMenuContributionDescriptor(
    String pluginId,
    String contributionId,
    String actionId,
    String label,
    String icon,
    ContextMenuRegistry.Location location,
    Set<ContextMenuRegistry.ObjectKind> objectKinds,
    int priority,
    ContextMenuRegistry.ContextMenuEntry entry,
    ContextMenuRegistry.Placement placement,
    Predicate<ContextMenuSelection> visibleWhen
) {
    public ContextMenuContributionDescriptor {
        pluginId = requireText(pluginId, "pluginId");
        contributionId = requireText(contributionId, "contributionId");
        actionId = requireText(actionId, "actionId");
        label = requireText(label, "label");
        location = Objects.requireNonNull(location, "location");
        objectKinds = Set.copyOf(Objects.requireNonNull(objectKinds, "objectKinds"));
        entry = Objects.requireNonNull(entry, "entry");
        placement = Objects.requireNonNull(placement, "placement");
    }

    public ContextMenuContributionDescriptor(
        final String pluginId,
        final String contributionId,
        final String actionId,
        final String label,
        final String icon,
        final ContextMenuRegistry.Location location,
        final Set<ContextMenuRegistry.ObjectKind> objectKinds,
        final int priority
    ) {
        this(
            pluginId, contributionId, actionId, label, icon, location, objectKinds, priority,
            ContextMenuRegistry.ContextMenuEntry.item(contributionId, label, actionId),
            ContextMenuRegistry.Placement.last(),
            null
        );
    }

    public ContextMenuContributionDescriptor(
        final String pluginId,
        final String contributionId,
        final String actionId,
        final String label,
        final String icon,
        final ContextMenuRegistry.Location location,
        final Set<ContextMenuRegistry.ObjectKind> objectKinds,
        final int priority,
        final ContextMenuRegistry.ContextMenuEntry entry,
        final ContextMenuRegistry.Placement placement
    ) {
        this(
            pluginId, contributionId, actionId, label, icon, location, objectKinds, priority,
            entry, placement, null
        );
    }

    public ContextMenuContributionDescriptor(
        final String pluginId,
        final String contributionId,
        final String actionId,
        final String label,
        final String icon,
        final ContextMenuRegistry.Location location,
        final Set<ContextMenuRegistry.ObjectKind> objectKinds,
        final int priority,
        final Predicate<ContextMenuSelection> visibleWhen
    ) {
        this(
            pluginId, contributionId, actionId, label, icon, location, objectKinds, priority,
            ContextMenuRegistry.ContextMenuEntry.item(contributionId, label, actionId),
            ContextMenuRegistry.Placement.last(),
            visibleWhen
        );
    }

    /**
     * Normalizes a generic editor-UI contribution into a context-menu descriptor.
     *
     * <p>Accepts only contributions whose identity is in the {@code CONTEXT_MENU} family and
     * whose payload is a {@code ContextMenuContribution}; anything else is a programming error
     * on the caller's side rather than a recoverable condition. The plugin id is taken from the
     * contribution's identity, not from the payload.
     *
     * @param contribution the registered editor-UI contribution to convert; must not be null
     * @return the normalized descriptor
     * @throws IllegalArgumentException if the contribution is not in the {@code CONTEXT_MENU}
     *                                  family, or its descriptor is not a context-menu contribution
     * @throws NullPointerException if {@code contribution} is null
     */
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
            value.priority(),
            value.entry(),
            value.placement(),
            value.visibleWhen()
        );
    }

    /** Empty selections never match; mixed selections require every item to match. */
    public boolean matches(final ContextMenuSelection selection) {
        Objects.requireNonNull(selection, "selection");
        return selection.location() == location
            && !selection.items().isEmpty()
            && selection.items().stream().allMatch(item -> objectKinds.contains(item.kind()))
            && (visibleWhen == null || visibleWhen.test(selection));
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
