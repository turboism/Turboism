package dev.turboism.mapping.verification;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The complete compatibility verdict for one located host artifact.
 *
 * <p>A resolution is produced once at admission time and then threaded through
 * premain, runtime start, and hook installation unchanged: no downstream stage
 * re-derives identity or re-picks candidate profiles. The resolution records
 * <em>why</em> every slice was admitted or refused so diagnostics can report a
 * bounded reason instead of a generic failure.</p>
 *
 * <p>{@link Mode#COMPATIBLE} only ever means <em>structural</em> admission:
 * selector-level static verification plus a pinned reviewed contract. It is
 * never reported as real-host validation.</p>
 */
public final class CompatibilityResolution {

    /** Host admission mode decided from identity plus reviewed artifacts. */
    public enum Mode {
        /** The artifact is byte-identical to a reviewed Cubism build. */
        VERIFIED,
        /** The artifact is a Cubism host admitted through structural compatibility probing. */
        COMPATIBLE,
        /** The artifact is not admitted at all: the host keeps running uninstrumented. */
        REJECTED
    }

    /** Per-slice admission outcome. */
    public enum SliceStatus {
        /** The slice contract was matched and the slice may be created. */
        ADMITTED,
        /** No catalog candidate exists or the required artifact was absent. */
        NO_CANDIDATE,
        /** Every probed candidate failed selector verification. */
        MISMATCHED,
        /** Multiple structurally matching candidates disagree on bindings. */
        AMBIGUOUS,
        /** The slice shares an adapter group that resolved to mixed source versions. */
        GROUP_CONFLICT
    }

    private final Mode mode;
    private final HostIdentityProbe identityProbe;
    private final Map<String, SliceResolution> slices;
    private final boolean runtimeAdmitted;
    private final String detail;

    private CompatibilityResolution(
        final Mode mode,
        final HostIdentityProbe identityProbe,
        final Map<String, SliceResolution> slices,
        final boolean runtimeAdmitted,
        final String detail
    ) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.identityProbe = Objects.requireNonNull(identityProbe, "identityProbe");
        this.slices = Map.copyOf(Objects.requireNonNull(slices, "slices"));
        this.runtimeAdmitted = runtimeAdmitted;
        this.detail = Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
    }

    /**
     * Builds a rejected resolution around a failed identity probe.
     *
     * @param identityProbe the probe verdict that rejected the host
     * @return a resolution admitting nothing
     */
    public static CompatibilityResolution rejected(final HostIdentityProbe identityProbe) {
        return new CompatibilityResolution(
            Mode.REJECTED,
            identityProbe,
            Map.of(),
            false,
            identityProbe.detail()
        );
    }

    /**
     * Builds a resolution from the resolved per-slice verdicts.
     *
     * @param mode admission mode
     * @param identityProbe the successful identity probe
     * @param slices per-slice verdicts
     * @param runtimeAdmitted whether the base runtime capability admitted
     * @param detail bounded human-readable summary
     * @return the resolution
     */
    public static CompatibilityResolution of(
        final Mode mode,
        final HostIdentityProbe identityProbe,
        final Map<String, SliceResolution> slices,
        final boolean runtimeAdmitted,
        final String detail
    ) {
        return new CompatibilityResolution(mode, identityProbe, slices, runtimeAdmitted, detail);
    }

    /**
     * @return the admission mode
     */
    public Mode mode() {
        return mode;
    }

    /**
     * @return the identity probe this resolution was built from
     */
    public HostIdentityProbe identityProbe() {
        return identityProbe;
    }

    /**
     * @return the declared host identity, present unless {@link #mode()} is {@link Mode#REJECTED}
     */
    public Optional<CubismHostIdentity> identity() {
        return identityProbe.identity();
    }

    /**
     * @return the version the host itself declared; throws when the resolution
     *     carries no declared identity ({@link Mode#REJECTED})
     */
    public String declaredVersion() {
        return identity()
            .map(CubismHostIdentity::version)
            .orElseThrow(() -> new IllegalStateException(
                "resolution carries no declared host identity"
            ));
    }

    /**
     * @return per-slice verdicts keyed by slice id
     */
    public Map<String, SliceResolution> slices() {
        return slices;
    }

    /**
     * @return whether the runtime may start on this host (base capability
     *     contract for verified mode; document-group slices admitted for
     *     compatibility mode)
     */
    public boolean runtimeAdmitted() {
        return runtimeAdmitted;
    }

    /**
     * @return whether the runtime may start at all on this host
     */
    public boolean admitted() {
        return mode != Mode.REJECTED && runtimeAdmitted;
    }

    /**
     * @return bounded human-readable summary of the resolution
     */
    public String detail() {
        return detail;
    }

    /**
     * The admitted contract for one slice.
     *
     * @param sliceId the slice key to look up
     * @return the contract when the slice was admitted, empty otherwise
     */
    public Optional<SliceContract> contractFor(final String sliceId) {
        final SliceResolution resolution = slices.get(sliceId);
        return resolution == null ? Optional.empty() : resolution.contract();
    }

    /**
     * Whether this compatibility resolution bound every admitted slice to the
     * host's own declared generation and that generation is runtime-admitted.
     * True means a repacked reviewed-generation artifact passed its declared
     * generation's whole selector contract; runtime hooks and transaction
     * bridges may install on it. Unknown declared versions never qualify —
     * their bound generation is by definition a different reviewed record.
     *
     * @return {@code true} only for a fully declared-generation-bound
     *     compatible resolution
     */
    public boolean declaredGenerationBound() {
        if (mode != Mode.COMPATIBLE || !runtimeAdmitted) {
            return false;
        }
        final String declared = declaredVersion();
        if (!ReviewedHostArtifacts.admitsFullRuntime(declared)
            || !ReviewedCubismReleases.isReviewed(declared, identity().orElseThrow().build())) {
            return false;
        }
        return slices.values().stream()
            .filter(SliceResolution::admitted)
            .allMatch(slice -> slice.contract().orElseThrow().declaredGenerationBound());
    }

    /**
     * The union of capability ids every admitted slice contract granted. This
     * is the capability evidence API availability and hook degradation read:
     * compatibility admission drops ids whose declared support conditions were
     * not satisfied by the binding.
     *
     * @return immutable capability-id union across all admitted slices
     */
    public java.util.Set<String> admittedCapabilityIds() {
        final java.util.Set<String> capabilities = new java.util.LinkedHashSet<>();
        for (final SliceResolution resolution : slices.values()) {
            resolution.contract().ifPresent(contract -> capabilities.addAll(contract.capabilities()));
        }
        return java.util.Collections.unmodifiableSet(capabilities);
    }

    /**
     * The verdict for one slice.
     *
     * @param sliceId the slice key to look up
     * @return the slice verdict, or a {@link SliceStatus#NO_CANDIDATE}
     *     placeholder for unknown slices
     */
    public SliceResolution slice(final String sliceId) {
        final SliceResolution resolution = slices.get(sliceId);
        return resolution == null
            ? new SliceResolution(
                sliceId, SliceStatus.NO_CANDIDATE, Optional.empty(), List.of(), "no catalog slice")
            : resolution;
    }

    /**
     * One slice's admission outcome.
     *
     * @param sliceId the slice key
     * @param status the admission verdict
     * @param contract the binding decided when admitted
     * @param matchedCandidates reviewed versions whose contracts verified, in
     *     probe order (for diagnostics only)
     * @param detail bounded human-readable detail
     */
    public record SliceResolution(
        String sliceId,
        SliceStatus status,
        Optional<SliceContract> contract,
        List<String> matchedCandidates,
        String detail
    ) {
        public SliceResolution {
            Objects.requireNonNull(sliceId, "sliceId");
            status = Objects.requireNonNull(status, "status");
            contract = Objects.requireNonNull(contract, "contract");
            matchedCandidates = List.copyOf(Objects.requireNonNull(matchedCandidates, "matchedCandidates"));
            detail = Objects.requireNonNull(detail, "detail");
        }

        /**
         * @return whether the slice was admitted
         */
        public boolean admitted() {
            return status == SliceStatus.ADMITTED;
        }
    }
}
