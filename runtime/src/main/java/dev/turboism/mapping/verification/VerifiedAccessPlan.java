package dev.turboism.mapping.verification;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable alias plan available only after exact artifact and selector verification. */
final class VerifiedAccessPlan {

    private final String adapterSliceId;
    private final java.util.Set<String> capabilityIds;
    private final String cubismVersion;
    private final HostArtifactFingerprint artifact;
    private final Map<String, StaticSelector> selectors;

    private VerifiedAccessPlan(
        final String adapterSliceId,
        final java.util.Set<String> capabilityIds,
        final String cubismVersion,
        final HostArtifactFingerprint artifact,
        final Map<String, StaticSelector> selectors
    ) {
        this.adapterSliceId = adapterSliceId;
        this.capabilityIds = java.util.Set.copyOf(capabilityIds);
        this.cubismVersion = cubismVersion;
        this.artifact = artifact;
        this.selectors = Map.copyOf(selectors);
    }

    static VerifiedAccessPlan from(
        final StaticVerificationRecord record,
        final StaticVerificationReport report
    ) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(report, "report");
        if (!report.allSelectorsVerified()) {
            throw new IllegalArgumentException("static verification report is not fully verified");
        }
        if (!record.artifact().matches(report.expectedFingerprint())
            || record.artifact().size() != report.actualFingerprint().size()
            || !record.artifact().sha256().equals(report.actualFingerprint().sha256())) {
            throw new IllegalArgumentException("verification record and report artifact digests differ");
        }
        if (report.results().size() != record.selectors().size()) {
            throw new IllegalArgumentException("verification report does not cover the complete selector set");
        }
        final Map<String, StaticSelectorResult> results = new LinkedHashMap<>();
        for (StaticSelectorResult result : report.results()) {
            if (results.put(result.alias(), result) != null) {
                throw new IllegalArgumentException("duplicate result alias: " + result.alias());
            }
        }
        final Map<String, StaticSelector> verified = new LinkedHashMap<>();
        for (StaticSelector selector : record.selectors()) {
            final StaticSelectorResult result = results.get(selector.alias());
            if (result == null
                || result.status() != StaticVerificationStatus.VERIFIED_STATIC
                || !result.selector().equals(selector)) {
                throw new IllegalArgumentException("selector tuple is not verified: " + selector.alias());
            }
            if (verified.put(selector.alias(), selector) != null) {
                throw new IllegalArgumentException("duplicate selector alias: " + selector.alias());
            }
        }
        return new VerifiedAccessPlan(
            record.adapterSliceId(),
            java.util.Set.copyOf(record.capabilityIds()),
            record.cubismVersion(),
            record.artifact(),
            verified
        );
    }

    StaticSelector selector(final String alias) {
        Objects.requireNonNull(alias, "alias");
        final StaticSelector selector = selectors.get(alias);
        if (selector == null) {
            throw new IllegalArgumentException("alias is not part of the verified access plan: " + alias);
        }
        return selector;
    }

    boolean authorizes(
        final String requiredAdapterSliceId,
        final java.util.Set<String> requiredCapabilityIds,
        final java.util.Set<String> requiredAliases
    ) {
        return adapterSliceId.equals(requiredAdapterSliceId)
            && capabilityIds.equals(requiredCapabilityIds)
            && selectors.keySet().equals(requiredAliases);
    }

    boolean authorizesFeatureSet(
        final String requiredAdapterSliceId,
        final java.util.Set<String> requiredCapabilityIds,
        final java.util.Set<String> requiredAliases
    ) {
        return adapterSliceId.equals(requiredAdapterSliceId)
            && capabilityIds.containsAll(requiredCapabilityIds)
            && selectors.keySet().containsAll(requiredAliases);
    }

    boolean authorizesFeature(
        final String requiredAdapterSliceId,
        final String requiredCapabilityId,
        final java.util.Set<String> requiredAliases
    ) {
        return adapterSliceId.equals(requiredAdapterSliceId)
            && capabilityIds.contains(requiredCapabilityId)
            && selectors.keySet().containsAll(requiredAliases);
    }

    VerifiedAccessPlan restrictTo(
        final java.util.Set<String> admittedCapabilityIds,
        final java.util.Set<String> admittedAliases
    ) {
        final java.util.Set<String> capabilities = java.util.Set.copyOf(admittedCapabilityIds);
        final java.util.Set<String> aliases = java.util.Set.copyOf(admittedAliases);
        if (!capabilityIds.containsAll(capabilities) || !selectors.keySet().containsAll(aliases)) {
            throw new IllegalArgumentException(
                "restricted access plan is not a subset of the verified record"
            );
        }
        final Map<String, StaticSelector> restrictedSelectors = new LinkedHashMap<>();
        for (final String alias : aliases) {
            restrictedSelectors.put(alias, selectors.get(alias));
        }
        return new VerifiedAccessPlan(
            adapterSliceId,
            capabilities,
            cubismVersion,
            artifact,
            restrictedSelectors
        );
    }

    java.util.List<StaticSelector> selectors() {
        return java.util.List.copyOf(selectors.values());
    }

    String cubismVersion() {
        return cubismVersion;
    }

    HostArtifactFingerprint artifact() {
        return artifact;
    }
}
