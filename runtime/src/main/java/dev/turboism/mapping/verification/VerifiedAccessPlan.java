package dev.turboism.mapping.verification;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable alias plan containing only selectors verified against the admitted artifact. */
final class VerifiedAccessPlan {

    /** How this plan was admitted relative to reviewed artifacts. */
    enum BindingMode {
        /** The artifact is byte-identical to a reviewed Cubism build. */
        EXACT,
        /** The artifact declared an unreviewed identity and matched one reviewed contract structurally. */
        COMPATIBLE
    }

    private final String adapterSliceId;
    private final java.util.Set<String> capabilityIds;
    private final String cubismVersion;
    private final HostArtifactFingerprint artifact;
    private final Map<String, StaticSelector> selectors;
    private final BindingMode bindingMode;
    private final String sourceVersion;
    private final Map<String, java.util.List<String>> capabilityConditions;

    private VerifiedAccessPlan(
        final String adapterSliceId,
        final java.util.Set<String> capabilityIds,
        final String cubismVersion,
        final HostArtifactFingerprint artifact,
        final Map<String, StaticSelector> selectors,
        final BindingMode bindingMode,
        final String sourceVersion,
        final Map<String, java.util.List<String>> capabilityConditions
    ) {
        this.adapterSliceId = adapterSliceId;
        this.capabilityIds = java.util.Set.copyOf(capabilityIds);
        this.cubismVersion = cubismVersion;
        this.artifact = artifact;
        this.selectors = Map.copyOf(selectors);
        this.bindingMode = Objects.requireNonNull(bindingMode, "bindingMode");
        this.sourceVersion = Objects.requireNonNull(sourceVersion, "sourceVersion");
        this.capabilityConditions = this.capabilityIds.stream().collect(
            java.util.stream.Collectors.toUnmodifiableMap(id -> id,
                id -> java.util.List.copyOf(capabilityConditions.get(id))));
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
            verified,
            BindingMode.EXACT,
            record.cubismVersion(),
            record.capabilityConditions()
        );
    }

    /**
     * Builds a plan for a compatibility-bound host: the artifact is NOT the
     * reviewed one, so the whole-file fingerprint is measured rather than
     * matched. Only successful selectors enter the plan, and each admitted
     * capability requires its complete selector dependency set.
     *
     * @param record the reviewed record whose selector contract was probed
     * @param report structural verification outcome against the live artifact
     * @param declaredVersion the version the host itself declared
     * @param actualArtifact measured size/SHA-256 of the live artifact
     * @return the verified subset of the reviewed contract, marked
     *     {@link BindingMode#COMPATIBLE}
     */
    static VerifiedAccessPlan fromCompatibility(
        final StaticVerificationRecord record,
        final StaticSelectorVerifier.StructureVerificationReport report,
        final String declaredVersion,
        final HostArtifactFingerprint actualArtifact
    ) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(declaredVersion, "declaredVersion");
        Objects.requireNonNull(actualArtifact, "actualArtifact");
        if (declaredVersion.isBlank()) {
            throw new IllegalArgumentException("declaredVersion must not be blank");
        }
        if (report.artifact().size() != actualArtifact.size()
            || !report.artifact().sha256().equals(actualArtifact.sha256())) {
            throw new IllegalArgumentException("structural report does not describe the admitted artifact");
        }
        if (report.results().size() != record.selectors().size()) {
            throw new IllegalArgumentException("structural report does not cover the complete selector set");
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
            if (result == null || !result.selector().equals(selector)) {
                throw new IllegalArgumentException("selector tuple is not verified: " + selector.alias());
            }
            if (result.status() != StaticVerificationStatus.VERIFIED_STATIC) {
                continue;
            }
            if (verified.put(selector.alias(), selector) != null) {
                throw new IllegalArgumentException("duplicate selector alias: " + selector.alias());
            }
        }
        return new VerifiedAccessPlan(
            record.adapterSliceId(),
            CapabilitySelectorDependencies.capabilities(record, verified.keySet()),
            declaredVersion,
            actualArtifact,
            verified,
            BindingMode.COMPATIBLE,
            record.cubismVersion(),
            record.capabilityConditions()
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
            restrictedSelectors,
            bindingMode,
            sourceVersion,
            capabilityConditions
        );
    }

    java.util.Set<String> capabilitiesRequiringHook(final String hookId) {
        final String condition = "hook:" + hookId;
        return capabilityConditions.entrySet().stream()
            .filter(entry -> entry.getValue().contains(condition))
            .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    java.util.List<StaticSelector> selectors() {
        return java.util.List.copyOf(selectors.values());
    }

    String cubismVersion() {
        return cubismVersion;
    }

    /**
     * The reviewed Cubism version this plan's contract was authored against:
     * the declared version for exact bindings, the bound record's version for
     * compatibility bindings.
     */
    String admittedCubismVersion() {
        return bindingMode == BindingMode.EXACT ? cubismVersion : sourceVersion;
    }

    BindingMode bindingMode() {
        return bindingMode;
    }

    String sourceVersion() {
        return sourceVersion;
    }

    String adapterSliceId() {
        return adapterSliceId;
    }

    boolean isExactCubismVersion(final String expectedVersion) {
        return bindingMode == BindingMode.EXACT && cubismVersion.equals(expectedVersion);
    }

    /**
     * Whether this plan bound the given reviewed generation — the declared
     * version for exact hosts, the verified contract's source generation for
     * compatibility-bound hosts. Selector and profile checks that key on the
     * reviewed generation must use this, not the host-declared version.
     */
    boolean isAdmittedCubismVersion(final String expectedVersion) {
        return admittedCubismVersion().equals(expectedVersion);
    }

    HostArtifactFingerprint artifact() {
        return artifact;
    }
}
