package dev.turboism.ui.contribution;

import java.util.Objects;

/** Runtime-normalized persistent contribution and its deterministic order. */
public record EditorUiContribution<T>(
    EditorUiContributionIdentity identity,
    int order,
    T descriptor
) {
    public EditorUiContribution {
        identity = Objects.requireNonNull(identity, "identity");
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }
}
