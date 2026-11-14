package dev.turboism.validation.externalpsd;

import static dev.turboism.validation.externalpsd.PsdStructureInspection.sha256;

import dev.turboism.sdk.cubism.model.ArtMeshTextureInputs;
import dev.turboism.sdk.cubism.model.ModelImageRelation;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Task target resolution, relation projection, and shared structural EDT helpers.
 *
 * <p>The probe binds one reviewed art-mesh texture input as its target and refuses to continue
 * when the relation set is ambiguous or when the target identity moves during a phase.</p>
 */
final class TargetResolution {
    static final String CONTENT_PROFILE_PROPERTY =
        "turboism.validation.externalpsd.contentProfile";

    static final String CONTROL_CONTENT_PROFILE = "control7";
    static final String F1_CONTENT_PROFILE = "f1";

    static boolean isPrepareFixturePhase(final String phase) {
        return "prepare-fixture".equals(phase);
    }

    /** Resolves the namespaced content profile before readiness, export, or mutation begins. */
    static PsdValidationContent.Profile parseContentProfile(final String requested) {
        final String value = requested == null || requested.isBlank()
            ? CONTROL_CONTENT_PROFILE : requested.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case CONTROL_CONTENT_PROFILE -> PsdValidationContent.Profile.SEVEN_LAYER_CONTROL;
            case F1_CONTENT_PROFILE -> PsdValidationContent.Profile.F1_2048_20;
            default -> throw new IllegalArgumentException(
                "unknown content profile '" + requested + "'; expected control7 or f1");
        };
    }

    static String contentProfileName(final PsdValidationContent.Profile profile) {
        Objects.requireNonNull(profile, "profile");
        return profile == PsdValidationContent.Profile.F1_2048_20
            ? F1_CONTENT_PROFILE : CONTROL_CONTENT_PROFILE;
    }

    static boolean contentValidationRequired(final PsdValidationContent.Profile profile,
        final boolean persistValidation) {
        Objects.requireNonNull(profile, "profile");
        return persistValidation || profile == PsdValidationContent.Profile.F1_2048_20;
    }

    /** The F2 acceptance slice is explicit opt-in; preparation-only runs stay NOT_CLAIMED. */
    static boolean f2AcceptanceRequested() {
        return Boolean.parseBoolean(System.getProperty(
            "turboism.validation.externalpsd.f2Acceptance", "false").strip());
    }

    static void requireStaleEditorReadRejected(final Runnable read) {
        try {
            read.run();
        } catch (IllegalStateException failure) {
            if ("Cubism model reference is stale for the active Editor model generation."
                .equals(failure.getMessage())) return;
            throw failure;
        }
        throw new IllegalStateException("old Editor model reference remained readable after document switch");
    }

    static void recordStructuralObservation(final Properties result, final String stage,
        final StructuralObservation value) {
        value.values().forEach((key, observed) -> result.setProperty("structure." + stage + "." + key, observed));
        result.setProperty("structure." + stage + ".historyPosition", Integer.toString(value.history().position()));
        result.setProperty("structure." + stage + ".historyEntries", Integer.toString(value.history().entries().size()));
        result.setProperty("structure." + stage + ".historyDocument", value.history().documentBindingId());
        result.setProperty("structure." + stage + ".historyManager", value.history().managerBindingId());
    }

    static long remainingStructuralMillis(final long deadline) {
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) throw new IllegalStateException("structural deadline exhausted");
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    record StructuralObservation(Map<String, String> values,
        dev.turboism.sdk.cubism.history.HistorySnapshot history, Set<String> rawIds,
        boolean modalShowing) { }

    /**
     * Validates the evidence that licenses a task-window close. The production caller supplies
     * rows captured from the exact reviewed table in the active window; this seam also keeps the
     * fixture/document/domain constraints executable without manufacturing an AWT host window.
     */
    static TaskWindowBindingDecision validateTaskWindowBinding(
        final String expectedFixture, final String actualFixture, final String documentId,
        final String modelId, final String windowIdentity, final String expectedDomainId,
        final List<ExactHostRowDispatch.ExactCapturedRow> rows) {
        if (expectedFixture == null || expectedFixture.isBlank()
            || actualFixture == null || actualFixture.isBlank()
            || !expectedFixture.equals(actualFixture)) {
            return TaskWindowBindingDecision.rejected(
                "task fixture identity is not proven for the host window");
        }
        if (documentId == null || documentId.isBlank()) {
            return TaskWindowBindingDecision.rejected(
                "task document identity is unavailable for the host window");
        }
        if (modelId == null || modelId.isBlank()) {
            return TaskWindowBindingDecision.rejected(
                "task model identity is unavailable for the host window");
        }
        if (windowIdentity == null || windowIdentity.isBlank()) {
            return TaskWindowBindingDecision.rejected(
                "host window identity is unavailable for the task document");
        }
        if (expectedDomainId == null || expectedDomainId.isBlank()) {
            return TaskWindowBindingDecision.rejected(
                "task ArtMesh domain identity is unavailable for the host window");
        }
        final ExactHostRowDispatch.ExactTargetSelection selection = ExactHostRowDispatch.selectExactTargetRows(rows, windowIdentity,
            expectedDomainId);
        if (!selection.available() || selection.rows().isEmpty()) {
            return TaskWindowBindingDecision.rejected(
                "exact task window ArtMesh evidence is unavailable: " + selection.reason());
        }
        return TaskWindowBindingDecision.bound(
            "fixture=" + actualFixture + " document=" + documentId + " model=" + modelId
                + " window=" + windowIdentity + " domain=" + expectedDomainId
                + " entrances=" + selection.rows().size());
    }

    static TaskWindowBindingDecision validateTaskWindowBindingForTest(
        final String expectedFixture, final String actualFixture, final String documentId,
        final String modelId, final String windowIdentity, final String expectedDomainId,
        final List<ExactHostRowDispatch.ExactCapturedRow> rows) {
        return validateTaskWindowBinding(expectedFixture, actualFixture, documentId, modelId,
            windowIdentity, expectedDomainId, rows);
    }

    static Optional<ModelImageRelation> uniqueModelImageRelation(
        final TextureRelationsSnapshot relations, final ModelImageId modelImageId) {
        final List<ModelImageRelation> matches = relations.modelImages().stream()
            .filter(relation -> relation != null && relation.id().equals(modelImageId))
            .toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    /**
     * Records the live users of the selected model image and, for F1, requires the reviewed
     * shared-model-image relation before any PSD export or mutation is attempted.
     */
    static int requireSharedModelImageRelation(final Properties result, final String prefix,
        final TargetIdentity target, final TextureRelationsSnapshot relations,
        final PsdValidationContent.Profile profile) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(relations, "relations");
        Objects.requireNonNull(profile, "profile");
        final String normalizedPrefix = RawExportCoordination.normalizePrefix(prefix);
        if (!relations.isAvailable()) {
            result.setProperty(normalizedPrefix + ".status", "UNAVAILABLE");
            throw new IllegalStateException("model-image sharing relation is unavailable");
        }
        if (RawExportCoordination.UNAVAILABLE_VALUE.equals(target.modelImageId())) {
            result.setProperty(normalizedPrefix + ".status", "UNAVAILABLE");
            throw new IllegalStateException("model-image identity is unavailable");
        }
        final List<String> users;
        try {
            users = sharedModelImageUserIds(target, relations);
        } catch (IllegalStateException failure) {
            result.setProperty(normalizedPrefix + ".status", "REJECTED");
            throw failure;
        }
        final boolean containsTarget = !RawExportCoordination.UNAVAILABLE_VALUE.equals(target.artMeshId())
            && users.contains(target.artMeshId());
        result.setProperty(normalizedPrefix + ".status", "AVAILABLE");
        result.setProperty(normalizedPrefix + ".modelImageId", target.modelImageId());
        result.setProperty(normalizedPrefix + ".usingArtMeshIds", users.toString());
        result.setProperty(normalizedPrefix + ".usingArtMeshCount",
            Integer.toString(users.size()));
        result.setProperty(normalizedPrefix + ".containsTargetArtMesh",
            Boolean.toString(containsTarget));
        if (profile == PsdValidationContent.Profile.F1_2048_20
            && (users.size() < 2 || !containsTarget)) {
            result.setProperty(normalizedPrefix + ".status", "REJECTED");
            throw new IllegalStateException(
                "F1 requires the target model image to be shared by at least two ArtMeshes: "
                    + users);
        }
        return users.size();
    }

    static List<String> sharedModelImageUserIds(final TargetIdentity target,
        final TextureRelationsSnapshot relations) {
        if (!relations.isAvailable()) {
            throw new IllegalStateException("model-image sharing relation is unavailable");
        }
        if (RawExportCoordination.UNAVAILABLE_VALUE.equals(target.modelImageId())) {
            throw new IllegalStateException("model-image identity is unavailable");
        }
        final List<ModelImageRelation> matches = relations.modelImages().stream()
            .filter(relation -> relation != null
                && target.modelImageId().equals(relation.id().value()))
            .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("target model-image relation occurrence count="
                + matches.size() + " (expected exactly one)");
        }
        return matches.get(0).usingArtMeshIds().stream()
            .map(ArtMeshId::value).distinct().toList();
    }

    static void requireStableSharedModelImageRelation(final Properties result,
        final String prefix, final TargetIdentity target, final TextureRelationsSnapshot relations,
        final PsdValidationContent.Profile profile, final List<String> expectedUsers) {
        Objects.requireNonNull(expectedUsers, "expectedUsers");
        requireSharedModelImageRelation(result, prefix, target, relations, profile);
        if (profile != PsdValidationContent.Profile.F1_2048_20) return;
        final List<String> actualUsers = sharedModelImageUserIds(target, relations);
        final Set<String> expectedSet = new HashSet<>(expectedUsers);
        final Set<String> actualSet = new HashSet<>(actualUsers);
        result.setProperty(RawExportCoordination.normalizePrefix(prefix) + ".expectedUsingArtMeshIds",
            expectedUsers.toString());
        result.setProperty(RawExportCoordination.normalizePrefix(prefix) + ".actualUsingArtMeshIds",
            actualUsers.toString());
        if (!expectedSet.equals(actualSet) || expectedSet.size() != expectedUsers.size()
            || actualSet.size() != actualUsers.size()) {
            result.setProperty(RawExportCoordination.normalizePrefix(prefix) + ".status", "REJECTED");
            throw new IllegalStateException(
                "F1 shared model-image users changed expected=" + expectedUsers
                    + " actual=" + actualUsers);
        }
        result.setProperty(RawExportCoordination.normalizePrefix(prefix) + ".stable", "true");
    }

    /**
     * Compares the document/model/binding/generation/model-image/ArtMesh anchor only.  A raw
     * image is deliberately not part of this comparison: native replacement is allowed to move
     * the model-image lineage to a newly-created raw image.
     */
    static boolean stableTargetIdentityMatches(final TargetIdentity expected,
        final TargetIdentity actual) {
        return expected != null && actual != null
            && !RawExportCoordination.UNAVAILABLE_VALUE.equals(expected.documentId())
            && !RawExportCoordination.UNAVAILABLE_VALUE.equals(expected.modelId())
            && !RawExportCoordination.UNAVAILABLE_VALUE.equals(expected.binding())
            && expected.generation() >= 0L
            && expected.generation() == actual.generation()
            && !RawExportCoordination.UNAVAILABLE_VALUE.equals(expected.modelImageId())
            && !RawExportCoordination.UNAVAILABLE_VALUE.equals(expected.artMeshId())
            && expected.documentId().equals(actual.documentId())
            && expected.modelId().equals(actual.modelId())
            && expected.binding().equals(actual.binding())
            && expected.modelImageId().equals(actual.modelImageId())
            && expected.artMeshId().equals(actual.artMeshId());
    }

    /** Compares the complete identity, including the raw-image lineage at this boundary. */
    static boolean targetIdentityMatches(final TargetIdentity expected,
        final TargetIdentity actual) {
        return stableTargetIdentityMatches(expected, actual)
            && !RawExportCoordination.UNAVAILABLE_VALUE.equals(expected.rawId())
            && expected.rawId().equals(actual.rawId());
    }

    static long remainingMillis(final long started, final long maxDurationMillis) {
        final long elapsedNanos = Math.max(0L, System.nanoTime() - started);
        final long durationNanos = TimeUnit.MILLISECONDS.toNanos(maxDurationMillis);
        final long remainingNanos = durationNanos - elapsedNanos;
        if (remainingNanos <= 0L) return 0L;
        return Math.max(1L, (remainingNanos + 999_999L) / 1_000_000L);
    }

    static void recordCycleWrittenPsd(final Properties result, final String prefix,
        final byte[] bytes) throws Exception {
        recordCycleWrittenPsd(result, prefix, bytes,
            PsdValidationContent.Profile.SEVEN_LAYER_CONTROL);
    }

    static void recordCycleWrittenPsd(final Properties result, final String prefix,
        final byte[] bytes, final PsdValidationContent.Profile profile) throws Exception {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(profile, "profile");
        final String normalizedPrefix = RawExportCoordination.normalizePrefix(prefix) + ".write";
        result.setProperty(normalizedPrefix + ".bytes", Integer.toString(bytes.length));
        result.setProperty(normalizedPrefix + ".sha256", sha256(bytes));
        try {
            final PsdValidationContent.Fingerprint fingerprint = DiagnosticObservationRecorder.targetFingerprint(
                bytes, normalizedPrefix, profile);
            result.setProperty(normalizedPrefix + ".targetRgb.status", "AVAILABLE");
            result.setProperty(normalizedPrefix + ".targetRgb.source",
                "decoded bytes written by this validation cycle");
            DiagnosticObservationRecorder.recordTargetFingerprint(result, normalizedPrefix + ".targetRgb", fingerprint);
        } catch (RuntimeException failure) {
            result.setProperty(normalizedPrefix + ".targetRgb.status", "UNAVAILABLE");
            result.setProperty(normalizedPrefix + ".diagnostic", failure.toString());
            throw failure;
        }
    }

    static boolean recordExpectedRawPresence(final Properties result, final String prefix,
        final TextureRelationsSnapshot relations, final RawImageId expectedRaw) {
        final String normalizedPrefix = RawExportCoordination.normalizePrefix(prefix);
        result.setProperty(normalizedPrefix + ".expectedRawId", expectedRaw.value());
        final boolean present = relations != null && relations.isAvailable()
            && relations.rawImage(expectedRaw).isPresent();
        result.setProperty(normalizedPrefix + ".expectedRawPresent", Boolean.toString(present));
        if (!present) {
            result.setProperty(normalizedPrefix + ".expectedRawDiagnostic",
                "expected raw is absent or relation snapshot is unavailable");
        }
        return present;
    }

    static void requireExpectedRawForDelta(final Properties result,
        final String relationPrefix, final String rawNewPrefix, final RawImageId expectedRaw,
        final TextureRelationsSnapshot before, final TextureRelationsSnapshot after,
        final RawExportCoordination.RawImageRelationDelta delta) {
        final boolean beforePresent = before != null && before.isAvailable()
            && before.rawImage(expectedRaw).isPresent();
        final boolean afterAvailable = after != null && after.isAvailable();
        if (beforePresent && afterAvailable) return;
        final String normalizedPrefix = RawExportCoordination.normalizePrefix(relationPrefix);
        final String normalizedRawNewPrefix = RawExportCoordination.normalizePrefix(rawNewPrefix);
        final String diagnostic = "expected old raw must be present before import and after relations "
            + "must be available: before=" + beforePresent + " afterRelations=" + afterAvailable
            + " delta=" + delta.status();
        result.setProperty(normalizedPrefix + ".expectedRawPresent", "false");
        result.setProperty(normalizedPrefix + ".expectedRawDiagnostic", diagnostic);
        result.setProperty(normalizedRawNewPrefix + ".status", "REJECTED");
        result.setProperty(normalizedRawNewPrefix + ".diagnostic", diagnostic);
        throw new IllegalStateException(diagnostic);
    }

    /**
     * Applies the cycle's fail-closed raw-delta gate. ZERO is diagnostic-only and keeps the
     * historical NOT_ATTEMPTED result; every ambiguous, unavailable, identity-changed, or
     * mismatched unique candidate is recorded as REJECTED and aborts the cycle. A unique
     * candidate is returned only when it is the explicit APPLIED after raw.
     */
    static Optional<RawImageId> requireRawImageDeltaForCycle(final Properties result,
        final String prefix, final RawExportCoordination.RawImageRelationDelta delta, final RawImageId explicitAfter) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(delta, "delta");
        final String normalizedPrefix = RawExportCoordination.normalizePrefix(prefix);
        switch (delta.status()) {
            case ZERO -> {
                if (!delta.addedRawImages().isEmpty()) {
                    final String diagnostic = "ZERO raw delta contains unexpected candidates: "
                        + delta.addedRawImages();
                    recordRejectedRawDelta(result, normalizedPrefix, diagnostic);
                    throw new IllegalStateException(diagnostic);
                }
                recordNoNewRawObserved(result, normalizedPrefix,
                    "no new raw image was present in the import relation difference");
                return Optional.empty();
            }
            case UNIQUE -> {
                if (delta.addedRawImages().size() != 1) {
                    final String diagnostic = "UNIQUE raw delta does not contain exactly one "
                        + "candidate: " + delta.addedRawImages();
                    recordRejectedRawDelta(result, normalizedPrefix, diagnostic);
                    throw new IllegalStateException(diagnostic);
                }
                final RawImageId candidate = delta.addedRawImages().get(0);
                if (explicitAfter == null || !candidate.equals(explicitAfter)) {
                    final String diagnostic = "unique raw-set candidate is not the explicit "
                        + "APPLIED after raw; no guess made: candidate=" + candidate
                        + " explicitAfter=" + explicitAfter;
                    recordRejectedRawDelta(result, normalizedPrefix, diagnostic);
                    throw new IllegalStateException(diagnostic);
                }
                return Optional.of(candidate);
            }
            case MULTIPLE, IDENTITY_CHANGED, UNAVAILABLE -> {
                final String diagnostic = "raw relation delta is not uniquely attributable: "
                    + delta.status() + " " + delta.diagnostic();
                recordRejectedRawDelta(result, normalizedPrefix, diagnostic);
                throw new IllegalStateException(diagnostic);
            }
        }
        throw new AssertionError("unhandled raw relation delta status: " + delta.status());
    }

    static void recordRejectedRawDelta(final Properties result, final String prefix,
        final String diagnostic) {
        final String normalizedPrefix = RawExportCoordination.normalizePrefix(prefix);
        result.setProperty(normalizedPrefix + ".raw.new.status", "REJECTED");
        result.setProperty(normalizedPrefix + ".raw.new.diagnostic", diagnostic);
    }

    static void recordRawRelationImportFailure(final Properties result,
        final String prefix, final Throwable failure) {
        final String normalizedPrefix = RawExportCoordination.normalizePrefix(prefix);
        final String relationPrefix = RawExportCoordination.normalizePrefix(prefix) + ".rawRelation";
        result.setProperty(relationPrefix + ".status", "UNAVAILABLE");
        result.setProperty(relationPrefix + ".diagnostic",
            "relation observation around import failed: " + failure);
        result.setProperty(relationPrefix + ".after.status", "UNAVAILABLE");
        result.setProperty(relationPrefix + ".after.diagnostic", failure.toString());
        result.setProperty(normalizedPrefix + ".raw.new.status", "NOT_ATTEMPTED");
        result.setProperty(normalizedPrefix + ".raw.new.diagnostic",
            "new raw export not attempted because import relation observation failed");
        final String completionPrefix = normalizedPrefix + ".importCompletion";
        if (!result.containsKey(completionPrefix + ".publicCompletion.status")) {
            result.setProperty(completionPrefix + ".observed", "false");
            result.setProperty(completionPrefix + ".publicCompletion.observed", "false");
            result.setProperty(completionPrefix + ".publicCompletion.status", "UNAVAILABLE");
            result.setProperty(completionPrefix + ".status", "UNAVAILABLE");
            result.setProperty(completionPrefix + ".diagnostic", failure.toString());
            result.setProperty(completionPrefix + ".consumedRevision", "false");
            DiagnosticObservationRecorder.recordNativeReturnUnavailable(result, completionPrefix);
        }
    }

    static void recordNoNewRawObserved(final Properties result, final String prefix,
        final String diagnostic) {
        final String normalizedPrefix = RawExportCoordination.normalizePrefix(prefix) + ".raw.new";
        result.setProperty(normalizedPrefix + ".status", "NOT_ATTEMPTED");
        result.setProperty(normalizedPrefix + ".diagnostic", diagnostic);
    }

    static void requireCompositionProfile(
        final OfficialPsdReplacementBaseline.CompositionObservation value, final String profile) {
        final Set<String> colors = value.meshes().values().stream()
            .map(OfficialPsdReplacementBaseline.Composition::color)
            .collect(java.util.stream.Collectors.toSet());
        final boolean legacy = colors.contains("ADD") || colors.contains("MULTIPLY");
        final boolean modern = colors.contains("ADD_R2_TSL") || colors.contains("ADD_R2")
            || colors.contains("MULTIPLY_R2");
        if (!("legacy".equals(profile) && legacy && !modern)
            && !("normal".equals(profile) && modern && !legacy)) {
            throw new IllegalStateException("native composition does not prove profile "
                + profile + ": " + colors);
        }
    }

    static void requireSameComposition(
        final OfficialPsdReplacementBaseline.CompositionObservation before,
        final OfficialPsdReplacementBaseline.CompositionObservation after) {
        if (before == null || after == null || before.meshes().isEmpty() || !before.equals(after)) {
            throw new IllegalStateException("native target version/ArtMesh composition changed");
        }
    }

    static Target targetWithIdentity(final Target previous,
        final TargetIdentity identity, final TextureRelationsSnapshot relations) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(relations, "relations");
        final RawImageId raw = new RawImageId(identity.rawId());
        final boolean replaced = relations.rawImage(raw)
            .map(dev.turboism.sdk.cubism.model.RawImageDetails::isReplaced)
            .orElseThrow(() -> new IllegalStateException(
                "accepted lineage raw is absent from the after relation snapshot"));
        return new Target(previous.artMesh(), previous.modelImage(), raw, replaced, identity);
    }

    /**
     * Keeps a GUI attempt tied to the first window that actually contains the exact target.  A
     * later active window is never a replacement candidate; the caller must wait for the bound
     * window to become active again or time out to BLOCKED.
     */
    static GuiWindowBindingDecision decideGuiWindowBinding(
        final String boundWindowIdentity, final String currentWindowIdentity,
        final boolean currentWindowProven, final boolean currentTargetMatched) {
        final String bound = boundWindowIdentity == null ? "" : boundWindowIdentity;
        final String current = currentWindowIdentity == null ? "" : currentWindowIdentity;
        if (!currentWindowProven || current.isBlank()) {
            return bound.isBlank()
                ? GuiWindowBindingDecision.unbound(
                    "active target window cannot be proven yet")
                : GuiWindowBindingDecision.waiting(
                    "bound target window is not currently active; waiting for " + bound);
        }
        if (!bound.isBlank() && !bound.equals(current)) {
            return GuiWindowBindingDecision.waiting(
                "active window changed from bound target " + bound + " to " + current
                    + "; waiting for the original window");
        }
        if (!bound.isBlank()) {
            return GuiWindowBindingDecision.stable(
                "active window remains bound target " + bound);
        }
        if (!currentTargetMatched) {
            return GuiWindowBindingDecision.unbound(
                "reviewed window has no exact target; window remains unbound");
        }
        return GuiWindowBindingDecision.bind(
            "bound first reviewed window containing the exact target " + current);
    }

    static GuiWindowBindingDecision decideGuiWindowBindingForTest(
        final String boundWindowIdentity, final String currentWindowIdentity,
        final boolean currentWindowProven, final boolean currentTargetMatched) {
        return decideGuiWindowBinding(boundWindowIdentity, currentWindowIdentity,
            currentWindowProven, currentTargetMatched);
    }

    static Target targetAtGuiIdentity(final Target previous,
        final TargetIdentity identity, final boolean rawReplaced) {
        Objects.requireNonNull(previous, "previous GUI target");
        Objects.requireNonNull(identity, "observed GUI identity");
        if (!stableTargetIdentityMatches(previous.identity(), identity)) {
            throw new IllegalStateException("GUI stable target identity changed expected="
                + previous.identity() + " actual=" + identity);
        }
        if (identity.rawId().isBlank() || RawExportCoordination.UNAVAILABLE_VALUE.equals(identity.rawId())) {
            throw new IllegalStateException("GUI current raw identity is unavailable");
        }
        final RawImageId raw = new RawImageId(identity.rawId());
        return new Target(previous.artMesh(), previous.modelImage(), raw, rawReplaced, identity);
    }

    record GuiWindowBindingDecision(boolean bindNow, boolean waitForBoundWindow,
        String diagnostic) {
        GuiWindowBindingDecision {
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static GuiWindowBindingDecision bind(final String diagnostic) {
            return new GuiWindowBindingDecision(true, false, diagnostic);
        }

        static GuiWindowBindingDecision stable(final String diagnostic) {
            return new GuiWindowBindingDecision(false, false, diagnostic);
        }

        static GuiWindowBindingDecision waiting(final String diagnostic) {
            return new GuiWindowBindingDecision(false, true, diagnostic);
        }

        static GuiWindowBindingDecision unbound(final String diagnostic) {
            return new GuiWindowBindingDecision(false, false, diagnostic);
        }
    }

    record TaskWindowBindingDecision(boolean bound, String diagnostic) {
        TaskWindowBindingDecision {
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static TaskWindowBindingDecision bound(final String diagnostic) {
            return new TaskWindowBindingDecision(true, diagnostic);
        }

        static TaskWindowBindingDecision rejected(final String diagnostic) {
            return new TaskWindowBindingDecision(false, diagnostic);
        }
    }

    /**
     * Identity captured from the task target.  The first six fields are the stable anchor; rawId
     * is the current resource in the target's lineage and is expected to change after a verified
     * native replacement.
     */
    record TargetIdentity(String documentId, String modelId, String binding,
        long generation, String modelImageId, String artMeshId, String rawId) {
        TargetIdentity {
            documentId = RawExportCoordination.valueOrUnavailable(documentId);
            modelId = RawExportCoordination.valueOrUnavailable(modelId);
            binding = RawExportCoordination.valueOrUnavailable(binding);
            if (generation < -1L) {
                throw new IllegalArgumentException("target generation must be -1 or greater");
            }
            modelImageId = RawExportCoordination.valueOrUnavailable(modelImageId);
            artMeshId = RawExportCoordination.valueOrUnavailable(artMeshId);
            rawId = RawExportCoordination.valueOrUnavailable(rawId);
        }

        /**
         * Source-compatible constructor for unavailable/negative legacy fixtures only.  The
         * resulting identity is deliberately incomplete and no production identity helper may
         * accept it as a match; valid targets must use the complete seven-field constructor.
         */
        TargetIdentity(final String documentId, final String modelId, final String binding,
            final String modelImageId, final String rawId) {
            this(documentId, modelId, binding, -1L, modelImageId, RawExportCoordination.UNAVAILABLE_VALUE, rawId);
        }

        static TargetIdentity unavailable() {
            return new TargetIdentity(
                RawExportCoordination.UNAVAILABLE_VALUE,
                RawExportCoordination.UNAVAILABLE_VALUE,
                RawExportCoordination.UNAVAILABLE_VALUE,
                -1L,
                RawExportCoordination.UNAVAILABLE_VALUE,
                RawExportCoordination.UNAVAILABLE_VALUE,
                RawExportCoordination.UNAVAILABLE_VALUE
            );
        }
    }

    record TargetSelection(String modelImageId, String artMeshId, String rawId) {}

    record Target(ArtMeshTextureInputs artMesh,
        dev.turboism.sdk.cubism.id.ModelImageId modelImage, RawImageId raw, boolean rawReplaced,
        TargetIdentity identity) {}

    /** Reads the replacement flag that the record keeps private to this container. */
    static boolean rawReplaced(final Target target) {
        return target.rawReplaced();
    }
}
