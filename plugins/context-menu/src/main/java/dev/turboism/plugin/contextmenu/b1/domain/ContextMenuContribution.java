package dev.turboism.plugin.contextmenu.b1.domain;

import java.util.Objects;

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
