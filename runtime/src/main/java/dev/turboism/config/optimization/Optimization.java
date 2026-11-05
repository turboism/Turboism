package dev.turboism.config.optimization;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One declared native optimization, written down once instead of per consumer.
 *
 * <p>Every view the product derives from this declaration - the launcher field allowlist, the
 * {@code -D} override names, the settings row and its i18n keys, the diagnostics protocol that
 * proves installation, the hooks a kill switch can address - reads the fields here rather than
 * restating them. {@link OptimizationCatalog} refuses at first touch when two rows share an
 * identity.</p>
 *
 * <p>An entry that no settings page renders is {@link Access#DIAGNOSTIC_ONLY}: it has no config
 * key, no row and no i18n key, so {@link #key()}, {@link #contributionId()}, {@link #labelKey()}
 * and {@link #tooltipKey()} are empty and {@link #placement()} is absent.</p>
 */
public record Optimization(
        /** {@code config.json} key; empty for a diagnostic-only probe. */
        String key,
        /** Settings row id, which is also the contribution id a tooltip lookup starts from. */
        String contributionId,
        /** Where the preference is persisted, or {@link Access#DIAGNOSTIC_ONLY}. */
        Access access,
        /** {@code -D} properties that force this entry, most authoritative first. */
        List<String> overrideProperties,
        /** Protocols whose install outcome proves this entry, in aggregation order. */
        List<String> protocols,
        /** Ids a kill switch or startup policy can address for the hooks that serve this entry. */
        Set<String> hookIds,
        /** The settings row to render, absent only for a diagnostic-only probe. */
        Optional<Placement> placement,
        /** Label key; explicit because the Atlas and mesh groups do not derive it. */
        String labelKey,
        /** Tooltip key; empty when the row has none. */
        String tooltipKey) {

    public Optimization {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(contributionId, "contributionId");
        Objects.requireNonNull(access, "access");
        overrideProperties = List.copyOf(Objects.requireNonNull(overrideProperties, "overrideProperties"));
        protocols = List.copyOf(Objects.requireNonNull(protocols, "protocols"));
        hookIds = Set.copyOf(Objects.requireNonNull(hookIds, "hookIds"));
        placement = Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(labelKey, "labelKey");
        Objects.requireNonNull(tooltipKey, "tooltipKey");
    }

    /** How the preference is persisted, which decides where a config writer puts it. */
    public enum Access {
        /** Nested in the {@code launcher} object, the same place the managed launcher reads it. */
        PERSISTED_LAUNCHER,
        /** A root-level boolean next to {@code safeMode}. */
        PERSISTED_ROOT,
        /** No persisted preference at all: the row only reports what its hook measured. */
        DIAGNOSTIC_ONLY
    }

    /** The settings row a persisted entry renders as: section id plus render index. */
    public record Placement(String section, int index) {
        public Placement {
            Objects.requireNonNull(section, "section");
        }
    }

    /** The property a user sets to force this entry, ignoring the persisted preference. */
    public String property() {
        return overrideProperties.isEmpty() ? "" : overrideProperties.get(0);
    }

    /** {@code true} when this entry renders a settings row, i.e. it is persisted. */
    public boolean persisted() {
        return access != Access.DIAGNOSTIC_ONLY;
    }
}
