package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.CubismSliceCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Compatibility admission engine: probes the host-declared identity, then
 * resolves every adapter slice against the reviewed contract candidates the
 * {@link CubismSliceCatalog} pins.
 *
 * <p>Two modes, one code path. A byte-identical reviewed artifact resolves
 * {@code VERIFIED} — contracts bind to the matching reviewed record and every
 * downstream check keeps its exact semantics. Any other Cubism artifact with a
 * coherent declared identity resolves {@code COMPATIBLE}: candidates are tried
 * in declared-version order, each capability needs its complete selector
 * dependencies, and only verified members enter the runtime access plan.
 * Equal candidate contracts merge; conflicting capabilities fail closed as
 * ambiguous. Slices sharing an adapter group must bind one source version.</p>
 *
 * <p>The whole-artifact digest is never consulted to pick a version and never
 * gates global startup eligibility; it remains evidence for reviewed-integrity
 * checks and is pinned inside every emitted {@link SliceContract}.</p>
 */
public final class CubismHostCompatibilityResolver {

    /** Slice keys that must admit for a compatibility host to run the runtime at all. */
    private static final Set<String> RUNTIME_BASE_SLICES = Set.of("project-workspace", "editor-model");

    /** Slice whose selectors target the Cubism Core jar rather than the Editor jar. */
    private static final String CORE_SLICE = "core-model-read";

    private static final String RECORD_RESOURCE_PREFIX = "/META-INF/turboism/verification/";

    private CubismHostCompatibilityResolver() {}

    /**
     * Resolves the host artifact against embedded reviewed candidates.
     *
     * @param editorJar located Editor JAR
     * @param coreJar located Core JAR, or {@code null} when absent
     * @return the full admission resolution
     */
    public static CompatibilityResolution resolve(final Path editorJar, final Path coreJar) {
        return resolve(editorJar, coreJar, CubismHostCompatibilityResolver::embeddedRecord);
    }

    /**
     * Resolves the host artifact against caller-supplied record bytes.
     *
     * @param editorJar located Editor JAR
     * @param coreJar located Core JAR, or {@code null} when absent
     * @param recordSource returns the record bytes for a file name, or
     *     {@code null} when the record is unavailable
     * @return the full admission resolution
     */
    public static CompatibilityResolution resolve(
            final Path editorJar, final Path coreJar, final Function<String, byte[]> recordSource) {
        Objects.requireNonNull(editorJar, "editorJar");
        Objects.requireNonNull(recordSource, "recordSource");
        final HostIdentityProbe probe = CubismEditorReleaseDetector.probe(editorJar);
        if (!probe.declared()) {
            return CompatibilityResolution.rejected(probe);
        }
        final CubismHostIdentity identity = probe.identity().orElseThrow();
        final Optional<String> reviewed = ReviewedHostArtifacts.cubismVersionOf(identity.artifact());
        if (reviewed.isPresent()) {
            return resolveVerified(probe, identity, reviewed.orElseThrow(), coreJar, recordSource);
        }
        return resolveCompatible(probe, identity, editorJar, coreJar, recordSource);
    }

    /**
     * Exact path: identity must agree with the reviewed artifact, then every
     * slice binds the record authored for that version. Nothing weakens here:
     * the downstream pinned workflow re-verifies everything again.
     */
    private static CompatibilityResolution resolveVerified(
            final HostIdentityProbe probe,
            final CubismHostIdentity identity,
            final String reviewedVersion,
            final Path coreJar,
            final Function<String, byte[]> recordSource) {
        if (!reviewedVersion.equals(identity.version())
                || !ReviewedCubismReleases.isReviewed(identity.version(), identity.build())) {
            return CompatibilityResolution.rejected(HostIdentityProbe.rejected(
                    HostIdentityProbe.Status.DECLARATION_AMBIGUOUS,
                    "declared version/build conflicts with reviewed artifact identity"));
        }
        final Map<String, CompatibilityResolution.SliceResolution> slices = new LinkedHashMap<>();
        final StaticVerificationRecordLoader loader = new StaticVerificationRecordLoader();
        for (final String sliceId : CubismSliceCatalog.slices()) {
            final CubismSliceCatalog.Candidate candidate = candidateForVerified(sliceId, identity, coreJar);
            if (candidate == null && CORE_SLICE.equals(sliceId) && coreJar != null && Files.isRegularFile(coreJar)) {
                // Editor and Core are separate artifacts. An exact Editor must not prevent
                // probing the reviewed Core contracts against a repacked sibling jar.
                slices.put(
                        sliceId,
                        probeSlice(
                                        sliceId,
                                        identity,
                                        coreJar,
                                        orderedCandidates(sliceId, identity),
                                        loader,
                                        new StaticSelectorVerifier(),
                                        recordSource)
                                .resolution());
                continue;
            }
            final byte[] bytes = candidate == null ? null : recordSource.apply(candidate.recordFileName());
            StaticVerificationRecord record = null;
            if (bytes != null) {
                try {
                    final var loaded = loader.load(bytes, candidate.recordFileName());
                    if (loaded.sha256().equals(candidate.recordSha256())
                            && loaded.record().cubismVersion().equals(candidate.cubismVersion())
                            && loaded.record().verificationId().equals(candidate.verificationId())
                            && loaded.record().adapterSliceId().equals(candidate.adapterSliceId())) {
                        record = loaded.record();
                    }
                } catch (IOException | RuntimeException failure) {
                    record = null;
                }
            }
            if (record == null) {
                slices.put(
                        sliceId,
                        new CompatibilityResolution.SliceResolution(
                                sliceId,
                                CompatibilityResolution.SliceStatus.NO_CANDIDATE,
                                Optional.empty(),
                                List.of(),
                                "no intact catalog-pinned record for this exact host version"));
                continue;
            }
            slices.put(sliceId, admitted(sliceId, candidate, record, identity, false));
        }
        final boolean runtimeAdmitted = baseSlicesAdmitted(slices);
        return CompatibilityResolution.of(
                CompatibilityResolution.Mode.VERIFIED,
                probe,
                slices,
                runtimeAdmitted,
                runtimeAdmitted
                        ? "exact reviewed host " + reviewedVersion
                        : "base runtime capability did not resolve for exact reviewed host " + reviewedVersion);
    }

    /**
     * Compatibility path: probe every eligible candidate, merge equal
     * contracts, reject disagreement, and enforce single-generation groups.
     */
    private static CompatibilityResolution resolveCompatible(
            final HostIdentityProbe probe,
            final CubismHostIdentity identity,
            final Path editorJar,
            final Path coreJar,
            final Function<String, byte[]> recordSource) {
        final Map<String, CompatibilityResolution.SliceResolution> slices = new LinkedHashMap<>();
        final Map<String, List<CandidateOutcome>> passedOutcomes = new LinkedHashMap<>();
        final StaticVerificationRecordLoader loader = new StaticVerificationRecordLoader();
        final StaticSelectorVerifier verifier = new StaticSelectorVerifier();
        for (final String sliceId : CubismSliceCatalog.slices()) {
            final Path effective = CORE_SLICE.equals(sliceId) ? coreJar : editorJar;
            if (effective == null || !Files.isRegularFile(effective)) {
                slices.put(
                        sliceId,
                        new CompatibilityResolution.SliceResolution(
                                sliceId,
                                CompatibilityResolution.SliceStatus.NO_CANDIDATE,
                                Optional.empty(),
                                List.of(),
                                CORE_SLICE.equals(sliceId)
                                        ? "core artifact absent beside the editor jar"
                                        : "host artifact absent"));
                continue;
            }
            final List<CubismSliceCatalog.Candidate> ordered = orderedCandidates(sliceId, identity);
            final SliceProbe probed = probeSlice(sliceId, identity, effective, ordered, loader, verifier, recordSource);
            slices.put(sliceId, probed.resolution());
            passedOutcomes.put(sliceId, probed.passed());
        }
        enforceGroupConsistency(slices, passedOutcomes, identity);
        final boolean runtimeAdmitted = baseSlicesAdmitted(slices);
        return CompatibilityResolution.of(
                CompatibilityResolution.Mode.COMPATIBLE,
                probe,
                slices,
                runtimeAdmitted,
                runtimeAdmitted
                        ? "compatibility admission for declared host " + identity.label()
                        : "base runtime capability did not resolve for declared host " + identity.label());
    }

    private static boolean baseSlicesAdmitted(final Map<String, CompatibilityResolution.SliceResolution> slices) {
        return RUNTIME_BASE_SLICES.stream()
                        .allMatch(id -> slices.containsKey(id) && slices.get(id).admitted())
                && slices.get("editor-model")
                        .contract()
                        .orElseThrow()
                        .capabilities()
                        .contains("cubism.editor-model.read");
    }

    /**
     * Package-visible candidate probing seam: unit tests supply synthetic
     * candidates and record bytes; production callers pass the catalog's
     * declared-version-ordered list. Returns the slice resolution plus every
     * passing candidate outcome so shared adapter groups can re-bind to one
     * common source generation.
     */
    static SliceProbe probeSlice(
            final String sliceId,
            final CubismHostIdentity identity,
            final Path targetArtifact,
            final List<CubismSliceCatalog.Candidate> ordered,
            final StaticVerificationRecordLoader loader,
            final StaticSelectorVerifier verifier,
            final Function<String, byte[]> recordSource) {
        if (ordered.isEmpty()) {
            return new SliceProbe(
                    new CompatibilityResolution.SliceResolution(
                            sliceId,
                            CompatibilityResolution.SliceStatus.NO_CANDIDATE,
                            Optional.empty(),
                            List.of(),
                            "no candidate declared for this host major version"),
                    List.of());
        }
        final List<CandidateOutcome> passed = new ArrayList<>();
        String lastDetail = "no candidate verified";
        boolean sawRecord = false;
        for (final CubismSliceCatalog.Candidate candidate : ordered) {
            final byte[] bytes = recordSource.apply(candidate.recordFileName());
            if (bytes == null) {
                lastDetail = "record resource unavailable: " + candidate.recordFileName();
                continue;
            }
            sawRecord = true;
            final CandidateOutcome outcome = tryCandidate(candidate, bytes, targetArtifact, identity, loader, verifier);
            if (outcome.passed()) {
                passed.add(outcome);
            } else {
                lastDetail = outcome.detail();
            }
        }
        if (!sawRecord) {
            return new SliceProbe(
                    new CompatibilityResolution.SliceResolution(
                            sliceId,
                            CompatibilityResolution.SliceStatus.NO_CANDIDATE,
                            Optional.empty(),
                            List.of(),
                            "no embedded record available for any candidate"),
                    List.of());
        }
        if (passed.isEmpty()) {
            return new SliceProbe(
                    new CompatibilityResolution.SliceResolution(
                            sliceId,
                            CompatibilityResolution.SliceStatus.MISMATCHED,
                            Optional.empty(),
                            List.of(),
                            lastDetail),
                    List.of());
        }
        final List<CandidateOutcome> distinct = new ArrayList<>();
        for (final CandidateOutcome outcome : passed) {
            boolean merged = false;
            for (final CandidateOutcome kept : distinct) {
                if (kept.equivalentContract(outcome)) {
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                distinct.add(outcome);
            }
        }
        final List<String> matched = passed.stream()
                .map(outcome -> outcome.candidate().cubismVersion())
                .toList();
        // Only a reviewed version/build can resolve conflicting bindings by
        // declaration. New builds must resolve ambiguity through evidence.
        for (final CandidateOutcome outcome : distinct) {
            if (ReviewedCubismReleases.isReviewed(identity.version(), identity.build())
                    && outcome.candidate().cubismVersion().equals(identity.version())) {
                return new SliceProbe(
                        new CompatibilityResolution.SliceResolution(
                                sliceId,
                                CompatibilityResolution.SliceStatus.ADMITTED,
                                Optional.of(outcome.contract(sliceId, identity)),
                                matched,
                                "structural contract matched declared generation " + identity.version()),
                        List.copyOf(passed));
            }
        }
        if (distinct.size() > 1) {
            final Set<String> ambiguous = ambiguousCapabilities(passed);
            final List<CandidateOutcome> usable = passed.stream()
                    .map(outcome -> outcome.withAmbiguities(ambiguous))
                    .filter(outcome ->
                            !outcome.contract(sliceId, identity).capabilities().isEmpty())
                    .sorted(Comparator.comparingInt((CandidateOutcome outcome) -> outcome.contract(sliceId, identity)
                                    .capabilities()
                                    .size())
                            .reversed())
                    .toList();
            if (!usable.isEmpty()) {
                final CandidateOutcome chosen = usable.get(0);
                return new SliceProbe(
                        new CompatibilityResolution.SliceResolution(
                                sliceId,
                                CompatibilityResolution.SliceStatus.ADMITTED,
                                Optional.of(chosen.contract(sliceId, identity)),
                                matched,
                                "matched capability contracts; ambiguous capabilities disabled: " + ambiguous),
                        usable);
            }
            return new SliceProbe(
                    new CompatibilityResolution.SliceResolution(
                            sliceId,
                            CompatibilityResolution.SliceStatus.AMBIGUOUS,
                            Optional.empty(),
                            matched,
                            "structurally matching candidates disagree on bindings: " + matched),
                    List.copyOf(passed));
        }
        return new SliceProbe(
                new CompatibilityResolution.SliceResolution(
                        sliceId,
                        CompatibilityResolution.SliceStatus.ADMITTED,
                        Optional.of(distinct.get(0).contract(sliceId, identity)),
                        matched,
                        "structural contract matched "
                                + distinct.get(0).candidate().cubismVersion()),
                List.copyOf(passed));
    }

    private static Set<String> ambiguousCapabilities(final List<CandidateOutcome> candidates) {
        final Map<String, Set<String>> bindings = new LinkedHashMap<>();
        final Set<String> ambiguous = new LinkedHashSet<>();
        for (final CandidateOutcome candidate : candidates) {
            for (final String capability :
                    CapabilitySelectorDependencies.capabilities(candidate.record(), candidate.verifiedAliases())) {
                final Set<String> aliases =
                        CapabilitySelectorDependencies.requiredAliases(candidate.record(), capability);
                final Set<String> keys = candidate.record().selectors().stream()
                        .filter(selector -> aliases.contains(selector.alias()))
                        .map(CandidateOutcome::bindingKey)
                        .collect(java.util.stream.Collectors.toSet());
                candidate
                        .record()
                        .capabilityConditions()
                        .get(capability)
                        .forEach(condition -> keys.add("condition:" + condition));
                final Set<String> previous = bindings.putIfAbsent(capability, Set.copyOf(keys));
                if (previous != null && !previous.equals(keys)) ambiguous.add(capability);
            }
        }
        return Set.copyOf(ambiguous);
    }

    /** Probe result pairing the public resolution with its passing outcomes. */
    record SliceProbe(CompatibilityResolution.SliceResolution resolution, List<CandidateOutcome> passed) {}

    private static List<CubismSliceCatalog.Candidate> orderedCandidates(
            final String sliceId, final CubismHostIdentity identity) {
        final List<CubismSliceCatalog.Candidate> candidates = new ArrayList<>(CubismSliceCatalog.candidates(sliceId));
        final int major = identity.majorVersion();
        candidates.removeIf(candidate -> majorOf(candidate.cubismVersion()) != major);
        candidates.sort(Comparator.comparing((CubismSliceCatalog.Candidate c) ->
                        !majorMinorOf(c.cubismVersion()).equals(identity.majorMinorVersion()))
                .thenComparing(c -> !c.cubismVersion().equals(identity.version()))
                .thenComparing(CubismSliceCatalog.Candidate::cubismVersion, Comparator.reverseOrder()));
        return List.copyOf(candidates);
    }

    private static int majorOf(final String version) {
        final int dot = version.indexOf('.');
        try {
            return Integer.parseInt(dot < 0 ? version : version.substring(0, dot));
        } catch (NumberFormatException failure) {
            return -1;
        }
    }

    private static String majorMinorOf(final String version) {
        final int first = version.indexOf('.');
        if (first < 0) return version;
        final int second = version.indexOf('.', first + 1);
        return second < 0 ? version : version.substring(0, second);
    }

    private static CandidateOutcome tryCandidate(
            final CubismSliceCatalog.Candidate candidate,
            final byte[] bytes,
            final Path targetArtifact,
            final CubismHostIdentity identity,
            final StaticVerificationRecordLoader loader,
            final StaticSelectorVerifier verifier) {
        final StaticVerificationRecordLoader.LoadedRecord loaded;
        try {
            loaded = loader.load(bytes, candidate.recordFileName());
        } catch (IOException | RuntimeException failure) {
            return CandidateOutcome.failed(
                    candidate, "record failed to load: " + failure.getClass().getSimpleName());
        }
        if (!loaded.sha256().equals(candidate.recordSha256())) {
            return CandidateOutcome.failed(candidate, "record sha256 does not match catalog pin");
        }
        final StaticVerificationRecord record = loaded.record();
        if (!record.cubismVersion().equals(candidate.cubismVersion())
                || !record.verificationId().equals(candidate.verificationId())
                || !record.adapterSliceId().equals(candidate.adapterSliceId())) {
            return CandidateOutcome.failed(candidate, "record fields do not match catalog pin");
        }
        final StaticSelectorVerifier.StructureVerificationReport report;
        try {
            report = verifier.verifyStructure(targetArtifact, record.selectors());
        } catch (IOException | RuntimeException failure) {
            return CandidateOutcome.failed(
                    candidate,
                    "structural verification failed: " + failure.getClass().getSimpleName());
        }
        final Set<String> verifiedAliases = report.results().stream()
                .filter(result -> result.status() == StaticVerificationStatus.VERIFIED_STATIC)
                .map(StaticSelectorResult::alias)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (CapabilitySelectorDependencies.capabilities(record, verifiedAliases).isEmpty()) {
            final StaticSelectorResult first = report.results().stream()
                    .filter(r -> r.status() != StaticVerificationStatus.VERIFIED_STATIC)
                    .findFirst()
                    .orElse(null);
            return CandidateOutcome.failed(
                    candidate,
                    first == null
                            ? "selector contract not fully verified"
                            : "selector " + first.alias() + " is " + first.status());
        }
        return new CandidateOutcome(candidate, record, report.artifact(), verifiedAliases, Set.of(), "passed");
    }

    /**
     * A shared adapter group must bind one reviewed generation end to end.
     * When individually admitted slices preferred different source versions,
     * the group first re-binds to the common candidate every member verified —
     * the one closest to the declared version — and only fails closed as
     * {@code GROUP_CONFLICT} when no common generation exists.
     *
     * <p>Package-visible seam for focused unit tests.</p>
     */
    static void enforceGroupConsistency(
            final Map<String, CompatibilityResolution.SliceResolution> slices,
            final Map<String, List<CandidateOutcome>> passedOutcomes,
            final CubismHostIdentity identity) {
        final Map<String, List<String>> groups = new LinkedHashMap<>();
        for (final CompatibilityResolution.SliceResolution slice : slices.values()) {
            if (slice.contract().isEmpty()) {
                continue;
            }
            groups.computeIfAbsent(CubismSliceCatalog.groupOf(slice.sliceId()), key -> new ArrayList<>())
                    .add(slice.sliceId());
        }
        for (final Map.Entry<String, List<String>> group : groups.entrySet()) {
            // Versions every admitted group member verified, in candidate order.
            Set<String> common = null;
            for (final String sliceId : group.getValue()) {
                final Set<String> verified = new LinkedHashSet<>();
                for (final CandidateOutcome outcome : passedOutcomes.getOrDefault(sliceId, List.of())) {
                    verified.add(outcome.candidate().cubismVersion());
                }
                if (common == null) {
                    common = verified;
                } else {
                    common.retainAll(verified);
                }
            }
            if (common == null || common.isEmpty()) {
                demoteGroup(slices, group.getKey(), group.getValue(), Set.of());
                continue;
            }
            final String chosen = common.stream().min(candidacyOrder(identity)).orElseThrow();
            for (final String sliceId : group.getValue()) {
                final CompatibilityResolution.SliceResolution slice = slices.get(sliceId);
                final CandidateOutcome rebinding = passedOutcomes.getOrDefault(sliceId, List.of()).stream()
                        .filter(outcome -> outcome.candidate().cubismVersion().equals(chosen))
                        .findFirst()
                        .orElseThrow();
                slices.put(
                        sliceId,
                        new CompatibilityResolution.SliceResolution(
                                sliceId,
                                CompatibilityResolution.SliceStatus.ADMITTED,
                                Optional.of(rebinding.contract(sliceId, identity)),
                                slice.matchedCandidates(),
                                "structural contract matched shared group generation " + chosen));
            }
        }
    }

    /** Ordering shared with candidate probing: closest to the declared version first. */
    private static Comparator<String> candidacyOrder(final CubismHostIdentity identity) {
        return Comparator.comparing((String version) -> !majorMinorOf(version).equals(identity.majorMinorVersion()))
                .thenComparing(version -> !version.equals(identity.version()))
                .thenComparing(Comparator.reverseOrder());
    }

    private static void demoteGroup(
            final Map<String, CompatibilityResolution.SliceResolution> slices,
            final String groupName,
            final List<String> members,
            final Set<String> versions) {
        for (final String sliceId : members) {
            final CompatibilityResolution.SliceResolution slice = slices.get(sliceId);
            slices.put(
                    sliceId,
                    new CompatibilityResolution.SliceResolution(
                            sliceId,
                            CompatibilityResolution.SliceStatus.GROUP_CONFLICT,
                            Optional.empty(),
                            slice.matchedCandidates(),
                            "shared adapter group " + groupName + " found no common source generation " + versions));
        }
    }

    private static CubismSliceCatalog.Candidate candidateForVerified(
            final String sliceId, final CubismHostIdentity identity, final Path coreJar) {
        final List<CubismSliceCatalog.Candidate> candidates = CubismSliceCatalog.candidates(sliceId);
        if (CORE_SLICE.equals(sliceId)) {
            if (coreJar == null || !Files.isRegularFile(coreJar)) {
                return null;
            }
            final String coreProfile;
            try {
                coreProfile = VerifiedCorePublicApiResolverFactory.profileForArtifact(coreJar);
            } catch (IOException | RuntimeException failure) {
                return null;
            }
            for (final CubismSliceCatalog.Candidate candidate : candidates) {
                if (candidate.cubismVersion().equals(coreProfile)) {
                    return candidate;
                }
            }
            return null;
        }
        for (final CubismSliceCatalog.Candidate candidate : candidates) {
            if (candidate.cubismVersion().equals(identity.version())) {
                return candidate;
            }
        }
        return null;
    }

    static CompatibilityResolution.SliceResolution admitted(
            final String sliceId,
            final CubismSliceCatalog.Candidate candidate,
            final StaticVerificationRecord record,
            final CubismHostIdentity identity,
            final boolean compatible) {
        return new CompatibilityResolution.SliceResolution(
                sliceId,
                CompatibilityResolution.SliceStatus.ADMITTED,
                Optional.of(contract(
                        sliceId,
                        candidate,
                        record,
                        identity,
                        compatible,
                        new HostArtifactDigest(
                                record.artifact().size(), record.artifact().sha256()),
                        CapabilitySelectorDependencies.allAliases(record))),
                List.of(candidate.cubismVersion()),
                compatible ? "structural contract matched" : "exact reviewed record");
    }

    /**
     * Per-capability admission under a compatibility binding. Every declared
     * {@code capabilityIds} entry carries the record's
     * {@code capabilityConditions}: {@code structure} survives on selector
     * evidence alone, {@code declaredGeneration} requires this slice to have
     * bound the host's declared reviewed generation (runtime hooks and
     * transaction bridges only install there), and {@code hook:<id>} resolves
     * the named hook's contract kind from the catalog — declared-generation
     * hooks follow the same rule, while artifact-digest hooks can never be
     * satisfied by an unreviewed archive. Unsatisfied conditions land in the
     * contract's {@code droppedCapabilities} so installers and diagnostics see
     * the reason, not just the absence.
     */
    private static void splitCapabilities(
            final StaticVerificationRecord record,
            final boolean compatible,
            final boolean declaredGenerationBound,
            final Set<String> admitted,
            final Map<String, String> dropped) {
        for (final String id : record.capabilityIds()) {
            final List<String> conditions =
                    record.capabilityConditions().getOrDefault(id, List.of("declaredGeneration"));
            final String failed = compatible ? unsatisfiedCondition(conditions, declaredGenerationBound) : null;
            if (failed == null) {
                admitted.add(id);
            } else {
                dropped.put(id, failed);
            }
        }
    }

    private static String unsatisfiedCondition(final List<String> conditions, final boolean declaredGenerationBound) {
        for (final String condition : conditions) {
            if ("structure".equals(condition)) {
                continue;
            }
            if ("declaredGeneration".equals(condition)) {
                if (!declaredGenerationBound) {
                    return condition;
                }
                continue;
            }
            if (condition.startsWith("hook:")) {
                final String hookId = condition.substring("hook:".length());
                final var kind = CubismSliceCatalog.hookContract(hookId);
                if (kind.isEmpty()) {
                    return condition;
                }
                final boolean satisfied =
                        switch (kind.orElseThrow()) {
                            case TRANSFORMED_TARGET -> true;
                            case DECLARED_GENERATION -> declaredGenerationBound;
                            case ARTIFACT_DIGEST -> false;
                        };
                if (!satisfied) {
                    return condition;
                }
                continue;
            }
            return condition;
        }
        return null;
    }

    private static SliceContract contract(
            final String sliceId,
            final CubismSliceCatalog.Candidate candidate,
            final StaticVerificationRecord record,
            final CubismHostIdentity identity,
            final boolean compatible,
            final HostArtifactDigest probedArtifact,
            final Set<String> verifiedAliases) {
        final boolean declaredGenerationBound = compatible
                && candidate.cubismVersion().equals(identity.version())
                && ReviewedCubismReleases.isReviewed(identity.version(), identity.build())
                && ReviewedHostArtifacts.admitsFullRuntime(identity.version());
        final Set<String> admitted = new LinkedHashSet<>();
        final Map<String, String> dropped = new LinkedHashMap<>();
        splitCapabilities(record, compatible, declaredGenerationBound, admitted, dropped);
        final Set<String> structural = CapabilitySelectorDependencies.capabilities(record, verifiedAliases);
        for (final String capability : record.capabilityIds()) {
            if (!structural.contains(capability)) {
                admitted.remove(capability);
                final String missing = CapabilitySelectorDependencies.requiredAliases(record, capability).stream()
                        .filter(alias -> !verifiedAliases.contains(alias))
                        .sorted()
                        .findFirst()
                        .orElse("unresolved");
                dropped.put(capability, "selector:" + missing);
            }
        }
        return new SliceContract(
                sliceId,
                candidate.cubismVersion(),
                candidate.recordFileName(),
                candidate.recordSha256(),
                candidate.verificationId(),
                candidate.adapterSliceId(),
                identity.version(),
                identity.build(),
                probedArtifact,
                compatible,
                Set.copyOf(admitted),
                Map.copyOf(dropped));
    }

    private static byte[] embeddedRecord(final String fileName) {
        try (InputStream input =
                CubismHostCompatibilityResolver.class.getResourceAsStream(RECORD_RESOURCE_PREFIX + fileName)) {
            return input == null ? null : input.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Per-candidate probe outcome with the verified record kept for contract equality. */
    record CandidateOutcome(
            CubismSliceCatalog.Candidate candidate,
            StaticVerificationRecord record,
            HostArtifactDigest artifact,
            Set<String> verifiedAliases,
            Set<String> ambiguousCapabilities,
            String detail) {
        /** Package-visible factory used by focused resolver tests. */
        static CandidateOutcome passed(
                final CubismSliceCatalog.Candidate candidate,
                final StaticVerificationRecord record,
                final HostArtifactDigest artifact) {
            return new CandidateOutcome(
                    candidate, record, artifact, CapabilitySelectorDependencies.allAliases(record), Set.of(), "passed");
        }

        static CandidateOutcome failed(final CubismSliceCatalog.Candidate candidate, final String detail) {
            return new CandidateOutcome(candidate, null, null, Set.of(), Set.of(), detail);
        }

        CandidateOutcome withAmbiguities(final Set<String> capabilities) {
            return new CandidateOutcome(candidate, record, artifact, verifiedAliases, capabilities, detail);
        }

        boolean passed() {
            return record != null;
        }

        /**
         * Two candidates bind equivalently only when slice identity, capability
         * set, capability prerequisites and the full selector binding surface are identical. {@code
         * mappingId} is provenance naming stamped per generation — the runtime
         * resolves members by alias, so candidates that differ only in
         * mapping-pack labels bind the same contract and merge.
         */
        boolean equivalentContract(final CandidateOutcome other) {
            return record != null
                    && other.record != null
                    && record.adapterSliceId().equals(other.record.adapterSliceId())
                    && Set.copyOf(record.capabilityIds()).equals(Set.copyOf(other.record.capabilityIds()))
                    && conditionSets(record).equals(conditionSets(other.record))
                    && verifiedAliases.equals(other.verifiedAliases)
                    && bindingKeys(record).equals(bindingKeys(other.record));
        }

        private static Map<String, Set<String>> conditionSets(final StaticVerificationRecord record) {
            final Map<String, Set<String>> conditions = new LinkedHashMap<>();
            record.capabilityConditions().forEach((id, requirements) -> conditions.put(id, Set.copyOf(requirements)));
            return conditions;
        }

        private static Set<String> bindingKeys(final StaticVerificationRecord record) {
            final Set<String> keys = new LinkedHashSet<>();
            for (final StaticSelector selector : record.selectors()) {
                keys.add(bindingKey(selector));
            }
            return keys;
        }

        private static String bindingKey(final StaticSelector selector) {
            return selector.alias() + "|" + selector.kind() + "|" + selector.ownerInternalName()
                    + "|" + selector.memberName() + "|" + selector.descriptor()
                    + "|" + selector.requiredAccessFlags() + "|" + selector.forbiddenAccessFlags();
        }

        SliceContract contract(final String sliceId, final CubismHostIdentity identity) {
            final SliceContract contract = CubismHostCompatibilityResolver.contract(
                    sliceId, candidate, record, identity, true, artifact, verifiedAliases);
            if (ambiguousCapabilities.isEmpty()) return contract;
            final Set<String> admitted = new LinkedHashSet<>(contract.capabilities());
            final Map<String, String> dropped = new LinkedHashMap<>(contract.droppedCapabilities());
            for (final String capability : ambiguousCapabilities) {
                if (admitted.remove(capability)) dropped.put(capability, "ambiguous:candidate-bindings");
            }
            return new SliceContract(
                    contract.sliceId(),
                    contract.sourceVersion(),
                    contract.recordFileName(),
                    contract.recordSha256(),
                    contract.verificationId(),
                    contract.adapterSliceId(),
                    contract.declaredVersion(),
                    contract.declaredBuild(),
                    contract.probedArtifact(),
                    contract.compatible(),
                    admitted,
                    dropped);
        }
    }
}
