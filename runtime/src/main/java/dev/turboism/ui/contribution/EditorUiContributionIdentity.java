package dev.turboism.ui.contribution;

import dev.turboism.internal.Requirements;
import dev.turboism.ui.host.EditorUiFamily;
import java.util.Objects;

/** Stable runtime identity for one plugin-owned Editor UI contribution. */
public record EditorUiContributionIdentity(String pluginId, EditorUiFamily family, String contributionId)
        implements Comparable<EditorUiContributionIdentity> {
    public EditorUiContributionIdentity {
        pluginId = Requirements.text(pluginId, "pluginId");
        family = Objects.requireNonNull(family, "family");
        contributionId = Requirements.text(contributionId, "contributionId");
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
}
