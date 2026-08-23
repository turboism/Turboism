package dev.turboism.plugin.contextmenu.b1.domain;

import java.util.Objects;

/**
 * One declared context-menu entry: where it appears, what label it uses, and where it sits.
 *
 * <p>This is a declaration, not a live menu item — it carries no action. {@code labelKey} is a
 * translation key rather than user-visible text, so the entry stays localisable. All reference
 * components are rejected when null; {@code order} is unconstrained.
 *
 * @param id          stable identifier of the entry, non-null
 * @param contextKind the selection surface the entry appears on, non-null
 * @param labelKey    translation key for the entry's label, non-null; not the label itself
 * @param order       sort position within its surface, lower first
 */
public record ContextMenuContribution(
    String id,
    ContextKind contextKind,
    String labelKey,
    int order
) {
    public ContextMenuContribution {
        id = Objects.requireNonNull(id, "id");
        contextKind = Objects.requireNonNull(contextKind, "contextKind");
        labelKey = Objects.requireNonNull(labelKey, "labelKey");
    }
}
