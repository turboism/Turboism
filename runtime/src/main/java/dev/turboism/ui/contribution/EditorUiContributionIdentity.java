package dev.turboism.ui.contribution;

import dev.turboism.ui.host.EditorUiFamily;

import java.util.Objects;

/** Stable runtime identity for one plugin-owned Editor UI contribution. */
public record EditorUiContributionIdentity(
    String pluginId,
    EditorUiFamily family,
    String contributionId
) implements Comparable<EditorUiContributionIdentity> {
    public EditorUiContributionIdentity {
        pluginId = requireText(pluginId, "pluginId");
        family = Objects.requireNonNull(family, "family");
        contributionId = requireText(contributionId, "contributionId");
    }

    @Override
    public int compareTo(final EditorUiContributionIdentity other) {
        int familyOrder = family.compareTo(other.family);
        if (familyOrder != 0) {
            return familyOrder;
        }
        int pluginOrder = pluginId.compareTo(other.pluginId);
        return pluginOrder != 0 ? pluginOrder : contributionId.compareTo(other.contributionId);
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
