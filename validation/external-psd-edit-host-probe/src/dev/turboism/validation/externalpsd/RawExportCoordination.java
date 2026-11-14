package dev.turboism.validation.externalpsd;

import static dev.turboism.validation.externalpsd.PsdStructureInspection.sha256;

import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot;
import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot.PsdLayerSnapshot;
import dev.turboism.sdk.cubism.model.ModelImageRelation;
import dev.turboism.sdk.cubism.model.RawLayerBinding;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import dev.turboism.sdk.cubism.psd.PsdFileOperationResult;
import dev.turboism.sdk.cubism.id.RawImageId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Raw-image relation projection and new-raw export coordination.
 *
 * <p>Each cycle must observe exactly one new raw image and must prove the relation delta that
 * made it new. The coordinator records the before/after projection so a rejected delta names the
 * relation that changed.</p>
 */
final class RawExportCoordination {
    static void recordRelationProjection(final Properties result, final String prefix,
        final TextureRelationsSnapshot relations) {
        final String normalizedPrefix = normalizePrefix(prefix);
        if (relations == null || !relations.isAvailable()) {
            recordUnavailableRelationProjection(result, normalizedPrefix);
            return;
        }
        result.setProperty(normalizedPrefix + ".status", "AVAILABLE");
        result.setProperty(normalizedPrefix + ".rawIds", rawImageIds(relations));
        result.setProperty(normalizedPrefix + ".modelImageIds", modelImageIds(relations));
        result.setProperty(normalizedPrefix + ".selectorProjection", selectorProjection(relations));
    }

    /** Records a relation snapshot with the generation used to bind its raw-image set. */
    static void recordRawImageRelationSnapshot(final Properties result, final String prefix,
        final TextureRelationsSnapshot relations, final String diagnostic) {
        Objects.requireNonNull(result, "result");
        final String normalizedPrefix = normalizePrefix(prefix);
        recordRelationProjection(result, normalizedPrefix, relations);
        if (relations == null || !relations.isAvailable()) {
            result.setProperty(normalizedPrefix + ".binding", UNAVAILABLE_VALUE);
            result.setProperty(normalizedPrefix + ".generation", UNAVAILABLE_VALUE);
            result.setProperty(normalizedPrefix + ".revision", UNAVAILABLE_VALUE);
            if (diagnostic != null && !diagnostic.isBlank()) {
                result.setProperty(normalizedPrefix + ".diagnostic", diagnostic);
            }
            return;
        }
        result.setProperty(normalizedPrefix + ".binding", relations.binding());
        result.setProperty(normalizedPrefix + ".generation",
            Long.toString(relations.generation()));
        result.setProperty(normalizedPrefix + ".revision",
            Long.toString(relations.revision()));
        if (diagnostic != null && !diagnostic.isBlank()) {
            result.setProperty(normalizedPrefix + ".diagnostic", diagnostic);
        }
    }

    /** Computes a fail-closed raw-image set difference between two live relation snapshots. */
    static RawImageRelationDelta rawImageRelationDelta(
        final TextureRelationsSnapshot before, final TextureRelationsSnapshot after) {
        if (before == null || after == null || !before.isAvailable() || !after.isAvailable()) {
            return RawImageRelationDelta.unavailable(
                "raw relation snapshot before or after import is unavailable");
        }
        final List<String> beforeIds = rawImageIdValues(before);
        final List<String> afterIds = rawImageIdValues(after);
        if (before.binding().isBlank() || after.binding().isBlank()) {
            return RawImageRelationDelta.unavailable(
                beforeIds, afterIds, "raw relation snapshot binding is unavailable");
        }
        final Set<RawImageId> beforeSet = new LinkedHashSet<>();
        before.rawImages().forEach(raw -> beforeSet.add(raw.id()));
        final Set<RawImageId> afterSet = new LinkedHashSet<>();
        after.rawImages().forEach(raw -> afterSet.add(raw.id()));
        final List<RawImageId> added = new ArrayList<>();
        for (final RawImageId raw : afterSet) {
            if (!beforeSet.contains(raw)) added.add(raw);
        }
        if (!before.binding().equals(after.binding())
            || before.generation() != after.generation()) {
            return new RawImageRelationDelta(RawImageDeltaStatus.IDENTITY_CHANGED,
                beforeIds, afterIds, added,
                "raw relation binding or generation changed across import");
        }
        final RawImageDeltaStatus status = switch (added.size()) {
            case 0 -> RawImageDeltaStatus.ZERO;
            case 1 -> RawImageDeltaStatus.UNIQUE;
            default -> RawImageDeltaStatus.MULTIPLE;
        };
        return new RawImageRelationDelta(status, beforeIds, afterIds, added,
            "raw image set difference contains " + added.size() + " new candidate(s)");
    }

    static void recordRawImageRelationDelta(final Properties result, final String prefix,
        final RawImageRelationDelta delta) {
        Objects.requireNonNull(result, "result");
        final RawImageRelationDelta value = Objects.requireNonNull(delta, "delta");
        final String normalizedPrefix = normalizePrefix(prefix);
        result.setProperty(normalizedPrefix + ".status", value.status().name());
        result.setProperty(normalizedPrefix + ".candidateCount",
            Integer.toString(value.candidateCount()));
        result.setProperty(normalizedPrefix + ".beforeRawIds", value.beforeRawIds().toString());
        result.setProperty(normalizedPrefix + ".afterRawIds", value.afterRawIds().toString());
        result.setProperty(normalizedPrefix + ".addedRawIds",
            value.addedRawImages().stream().map(RawImageId::value).toList().toString());
        result.setProperty(normalizedPrefix + ".diagnostic", value.diagnostic());
        if (value.status() == RawImageDeltaStatus.UNIQUE && value.candidateCount() == 1) {
            result.setProperty(normalizedPrefix + ".newRawId",
                value.addedRawImages().get(0).value());
        }
    }

    /**
     * Coordinates the validation-only export of a raw image added by the preceding import.
     * The starter owns the single-EDT preflight-and-export operation; this method owns the
     * before/after identity gate and stops a handle that was created before a postflight failure.
     * A successful return licenses the caller to read the handle bytes. The candidate must also
     * be the expected current raw of the stable model-image relation in both observations; a
     * raw-set candidate alone cannot license an export.
     */
    static RawImageExportHandle coordinateNewRawExport(final Properties result,
        final String prefix, final TargetResolution.TargetIdentity expected, final RawImageId candidate,
        final RawExportStarter starter, final RawExportObservationSupplier afterSupplier,
        final RawExportStopper stopper) throws Exception {
        Objects.requireNonNull(result, "result");
        final String normalizedPrefix = normalizePrefix(prefix);
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(starter, "starter");
        Objects.requireNonNull(afterSupplier, "afterSupplier");
        Objects.requireNonNull(stopper, "stopper");
        result.setProperty(normalizedPrefix + ".candidateRawId", candidate.value());
        result.setProperty(normalizedPrefix + ".coordination.status", "STARTED");
        final AtomicBoolean preflightInvoked = new AtomicBoolean();
        final AtomicReference<RawExportObservation> preflightObservation =
            new AtomicReference<>();
        RawImageExportHandle handle = null;
        Throwable primary = null;
        try {
            final RawExportStarted started = starter.start(candidate, observation -> {
                preflightInvoked.set(true);
                preflightObservation.set(observation);
                recordRawExportObservation(
                    result, normalizedPrefix, "before", candidate, observation);
                requireRawExportObservation(expected, candidate, observation, "before-export");
            });
            if (started != null) {
                handle = Objects.requireNonNull(started.handle(),
                    "raw export starter returned no handle");
            }
            if (!preflightInvoked.get()) {
                throw rawExportUnavailable(
                    "raw export starter did not run the preflight before invoking export");
            }
            if (started == null) {
                throw rawExportUnavailable("raw export starter returned no completed export");
            }
            if (started.before() == null
                || !Objects.equals(preflightObservation.get(), started.before())) {
                throw rawExportUnavailable(
                    "raw export starter returned a before observation different from its preflight");
            }
            final RawExportObservation after = afterSupplier.observe(candidate);
            if (after == null) {
                throw rawExportUnavailable("post-export target observation returned no value");
            }
            recordRawExportObservation(result, normalizedPrefix, "after", candidate, after);
            requireRawExportObservation(expected, candidate, after, "after-export");
            if (!sameRawExportSession(started.before(), after)) {
                throw rawExportUnavailable(
                    "document/model/binding/model-image/generation changed during raw export"
                        + ": before=" + started.before() + " after=" + after);
            }
            result.setProperty(normalizedPrefix + ".coordination.status", "AVAILABLE");
            result.setProperty(normalizedPrefix + ".coordination.diagnostic",
                "same target and one candidate observed before and after completed export");
            return handle;
        } catch (Exception failure) {
            primary = failure;
            recordRawExportCoordinationFailure(result, normalizedPrefix, failure);
            throw failure;
        } catch (Error failure) {
            primary = failure;
            recordRawExportCoordinationFailure(result, normalizedPrefix, failure);
            throw failure;
        } finally {
            if (primary != null && handle != null) {
                stopFailedRawExport(result, normalizedPrefix, handle, stopper, primary);
            }
        }
    }

    static void requireRawExportObservation(final TargetResolution.TargetIdentity expected,
        final RawImageId candidate, final RawExportObservation observation, final String phase) {
        if (observation == null) {
            throw rawExportUnavailable(phase + " observation is unavailable");
        }
        final List<String> reasons = new ArrayList<>();
        if (!observation.relationsAvailable()) reasons.add("relations unavailable");
        if (observation.candidateRawId() == null
            || !candidate.value().equals(observation.candidateRawId())) {
            reasons.add("observed candidate raw identity does not match requested candidate");
        }
        if (observation.candidateCount() != 1) {
            reasons.add("candidate raw occurrence count=" + observation.candidateCount()
                + " (expected exactly one)");
        }
        if (observation.modelImageCount() != 1) {
            reasons.add("target model-image occurrence count=" + observation.modelImageCount()
                + " (expected exactly one)");
        }
        if (observation.generation() < 0L) reasons.add("relation generation unavailable");
        if (expected.generation() < 0L || expected.generation() != observation.generation()) {
            reasons.add("relation generation changed");
        }
        if (!expected.rawId().equals(observation.currentRawId())) {
            reasons.add("current raw is not the requested current-lineage raw");
        }
        requireIdentityField(expected.documentId(), observation.documentId(), "document", reasons);
        requireIdentityField(expected.modelId(), observation.modelId(), "model", reasons);
        requireIdentityField(expected.binding(), observation.binding(), "binding", reasons);
        requireIdentityField(expected.modelImageId(), observation.modelImageId(),
            "model-image", reasons);
        requireIdentityField(expected.artMeshId(), observation.artMeshId(), "ArtMesh", reasons);
        if (!reasons.isEmpty()) {
            throw rawExportUnavailable(phase + " raw export target is unavailable: "
                + String.join("; ", reasons) + "; observed=" + observation);
        }
    }

    static void requireIdentityField(final String expected, final String actual,
        final String label, final List<String> reasons) {
        if (expected == null || expected.isBlank() || UNAVAILABLE_VALUE.equals(expected)) {
            reasons.add("expected " + label + " identity unavailable");
        } else if (actual == null || !expected.equals(actual)) {
            reasons.add(label + " identity changed");
        }
    }

    static boolean sameRawExportSession(final RawExportObservation before,
        final RawExportObservation after) {
        return before.documentId().equals(after.documentId())
            && before.modelId().equals(after.modelId())
            && before.binding().equals(after.binding())
            && before.modelImageId().equals(after.modelImageId())
            && before.artMeshId().equals(after.artMeshId())
            && before.candidateRawId().equals(after.candidateRawId())
            && before.currentRawId().equals(after.currentRawId())
            && before.generation() == after.generation()
            && before.candidateCount() == after.candidateCount()
            && before.modelImageCount() == after.modelImageCount();
    }

    static RawExportTargetUnavailableException rawExportUnavailable(
        final String diagnostic) {
        return new RawExportTargetUnavailableException(diagnostic);
    }

    static void recordRawExportObservation(final Properties result, final String prefix,
        final String phase, final RawImageId candidate, final RawExportObservation observation) {
        final String observationPrefix = normalizePrefix(prefix) + ".coordination." + phase;
        if (observation == null) {
            result.setProperty(observationPrefix + ".status", "UNAVAILABLE");
            result.setProperty(observationPrefix + ".diagnostic", "null observation");
            return;
        }
        result.setProperty(observationPrefix + ".status",
            observation.relationsAvailable() ? "OBSERVED" : "UNAVAILABLE");
        result.setProperty(observationPrefix + ".documentId", observation.documentId());
        result.setProperty(observationPrefix + ".modelId", observation.modelId());
        result.setProperty(observationPrefix + ".binding", observation.binding());
        result.setProperty(observationPrefix + ".modelImageId", observation.modelImageId());
        result.setProperty(observationPrefix + ".artMeshId", observation.artMeshId());
        result.setProperty(observationPrefix + ".candidateRawId", observation.candidateRawId());
        result.setProperty(observationPrefix + ".currentRawId", observation.currentRawId());
        result.setProperty(observationPrefix + ".generation",
            Long.toString(observation.generation()));
        result.setProperty(observationPrefix + ".modelImageCount",
            Integer.toString(observation.modelImageCount()));
        result.setProperty(observationPrefix + ".candidateCount",
            Integer.toString(observation.candidateCount()));
        result.setProperty(observationPrefix + ".requestedCandidateRawId", candidate.value());
        result.setProperty(observationPrefix + ".diagnostic", observation.diagnostic());
    }

    static void recordRawExportCoordinationFailure(final Properties result,
        final String prefix, final Throwable failure) {
        final boolean unavailable = hasCause(failure, RawExportTargetUnavailableException.class);
        result.setProperty(prefix + ".coordination.status",
            unavailable ? "UNAVAILABLE" : "FAILED");
        result.setProperty(prefix + ".coordination.diagnostic", throwableSummary(failure));
        result.setProperty(prefix + ".rgb.status", "UNAVAILABLE");
    }

    static boolean hasCause(final Throwable failure,
        final Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) return true;
            current = current.getCause();
        }
        return false;
    }

    static String throwableSummary(final Throwable failure) {
        final StringBuilder summary = new StringBuilder();
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 16) {
            if (summary.length() > 0) summary.append(" <- ");
            summary.append(current);
            current = current.getCause();
        }
        if (current != null) summary.append(" <- cause-chain-truncated");
        return summary.toString();
    }

    static void stopFailedRawExport(final Properties result, final String prefix,
        final RawImageExportHandle handle, final RawExportStopper stopper,
        final Throwable primary) {
        try {
            final PsdFileOperationResult stopped = stopper.stop(handle);
            if (stopped == null) {
                throw new IllegalStateException("raw export coordination stop returned null");
            }
            result.setProperty(prefix + ".stopStatus", stopped.status().name());
            result.setProperty(prefix + ".stopDiagnostic", stopped.diagnostic());
            if (stopped.status() != PsdFileOperationResult.Status.STOPPED) {
                throw new IllegalStateException("raw export coordination handle did not stop: "
                    + stopped.status());
            }
        } catch (Throwable cleanup) {
            result.setProperty(prefix + ".stopStatus", "FAILED");
            result.setProperty(prefix + ".stopDiagnostic", cleanup.toString());
            TempExportTracker.addSuppressed(primary, cleanup);
        }
    }

    /**
     * Decodes exactly one newly-added raw image and always stops the supplied export handle before
     * returning. This is validation-only; the caller supplies the existing public export path.
     */
    static PsdValidationContent.Fingerprint inspectUniqueNewRaw(final Properties result,
        final String prefix, final RawImageId oldRaw, final RawImageRelationDelta delta,
        final RawImageExportSupplier exporter) throws Exception {
        return inspectUniqueNewRaw(result, prefix, oldRaw, delta,
            PsdValidationContent.Profile.SEVEN_LAYER_CONTROL, exporter);
    }

    static PsdValidationContent.Fingerprint inspectUniqueNewRaw(final Properties result,
        final String prefix, final RawImageId oldRaw, final RawImageRelationDelta delta,
        final PsdValidationContent.Profile profile, final RawImageExportSupplier exporter)
        throws Exception {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(oldRaw, "oldRaw");
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(exporter, "exporter");
        final String normalizedPrefix = normalizePrefix(prefix);
        if (delta.status() != RawImageDeltaStatus.UNIQUE
            || delta.addedRawImages().size() != 1) {
            result.setProperty(normalizedPrefix + ".status", "NOT_ATTEMPTED");
            result.setProperty(normalizedPrefix + ".diagnostic",
                "new raw export requires exactly one candidate: " + delta.status());
            throw new IllegalStateException(
                "new raw export requires exactly one candidate: " + delta.status());
        }
        final RawImageId newRaw = delta.addedRawImages().get(0);
        if (oldRaw.equals(newRaw)) {
            result.setProperty(normalizedPrefix + ".status", "FAILED");
            result.setProperty(normalizedPrefix + ".diagnostic",
                "new raw candidate equals the pre-import raw identity");
            throw new IllegalStateException("new raw candidate equals the old raw identity");
        }
        result.setProperty(normalizedPrefix + ".id", newRaw.value());
        result.setProperty(normalizedPrefix + ".requestedRawId", newRaw.value());
        result.setProperty(normalizedPrefix + ".status", "STARTED");
        RawImageExportHandle handle = null;
        Throwable primary = null;
        try {
            handle = Objects.requireNonNull(exporter.export(newRaw),
                "new raw exporter returned null handle");
            if (!newRaw.equals(handle.rawId())) {
                throw new IllegalStateException("new raw exporter returned unexpected raw identity: "
                    + handle.rawId());
            }
            final byte[] bytes = Objects.requireNonNull(handle.bytes(),
                "new raw export returned null bytes");
            result.setProperty(normalizedPrefix + ".bytes", Integer.toString(bytes.length));
            result.setProperty(normalizedPrefix + ".sha256", sha256(bytes));
            final PsdValidationContent.Fingerprint fingerprint = DiagnosticObservationRecorder.targetFingerprint(
                bytes, normalizedPrefix + " new raw", profile);
            result.setProperty(normalizedPrefix + ".rgb.status", "AVAILABLE");
            result.setProperty(normalizedPrefix + ".rgb.source",
                "fresh public export of the unique newly-added raw image");
            DiagnosticObservationRecorder.recordTargetFingerprint(result, normalizedPrefix + ".rgb", fingerprint);
            result.setProperty(normalizedPrefix + ".status", "AVAILABLE");
            return fingerprint;
        } catch (Exception failure) {
            primary = failure;
            recordRawImageExportFailure(result, normalizedPrefix, failure);
            throw failure;
        } catch (Error failure) {
            primary = failure;
            recordRawImageExportFailure(result, normalizedPrefix, failure);
            throw failure;
        } finally {
            if (handle != null) {
                try {
                    final PsdFileOperationResult stopped = handle.stop();
                    if (stopped == null) {
                        throw new IllegalStateException("new raw export stop returned null");
                    }
                    result.setProperty(normalizedPrefix + ".stopStatus",
                        stopped.status().name());
                    result.setProperty(normalizedPrefix + ".stopDiagnostic",
                        stopped.diagnostic());
                    if (stopped.status() != PsdFileOperationResult.Status.STOPPED) {
                        throw new IllegalStateException("new raw export handle did not stop: "
                            + stopped.status());
                    }
                } catch (Throwable cleanup) {
                    result.setProperty(normalizedPrefix + ".stopStatus", "FAILED");
                    result.setProperty(normalizedPrefix + ".stopDiagnostic",
                        cleanup.toString());
                    if (primary != null) {
                        TempExportTracker.addSuppressed(primary, cleanup);
                    } else {
                        result.setProperty(normalizedPrefix + ".status", "FAILED");
                        result.setProperty(normalizedPrefix + ".diagnostic",
                            cleanup.toString());
                        TempExportTracker.rethrowCleanup(cleanup);
                    }
                }
            } else if (!result.containsKey(normalizedPrefix + ".stopStatus")) {
                result.setProperty(normalizedPrefix + ".stopStatus", "NOT_CREATED");
            }
        }
    }

    static void recordRawImageExportFailure(final Properties result,
        final String prefix, final Throwable failure) {
        result.setProperty(prefix + ".status",
            hasCause(failure, RawExportTargetUnavailableException.class)
                ? "UNAVAILABLE" : "FAILED");
        result.setProperty(prefix + ".diagnostic", throwableSummary(failure));
        result.setProperty(prefix + ".failure", throwableSummary(failure));
        result.setProperty(prefix + ".rgb.status", "UNAVAILABLE");
    }

    static List<String> rawImageIdValues(final TextureRelationsSnapshot relations) {
        return relations.rawImages().stream().map(raw -> raw.id().value()).distinct().toList();
    }

    static void recordUnavailableRelationProjection(final Properties result,
        final String prefix) {
        final String normalizedPrefix = normalizePrefix(prefix);
        result.setProperty(normalizedPrefix + ".status", "UNAVAILABLE");
        result.setProperty(normalizedPrefix + ".rawIds", UNAVAILABLE_VALUE);
        result.setProperty(normalizedPrefix + ".modelImageIds", UNAVAILABLE_VALUE);
        result.setProperty(normalizedPrefix + ".selectorProjection", UNAVAILABLE_VALUE);
    }

    static String rawImageIds(final TextureRelationsSnapshot relations) {
        return relations.rawImages().stream()
            .map(raw -> raw.id().value())
            .toList().toString();
    }

    static String modelImageIds(final TextureRelationsSnapshot relations) {
        return relations.modelImages().stream()
            .map(ModelImageRelation::id)
            .map(id -> id.value())
            .toList().toString();
    }

    /** Explicitly labels layerInputsByRawImage as a selector projection, not live PSD evidence. */
    static String selectorProjection(final TextureRelationsSnapshot relations) {
        final List<String> projections = new ArrayList<>();
        for (final ModelImageRelation relation : relations.modelImages()) {
            final List<String> byRaw = new ArrayList<>();
            for (final Map.Entry<RawImageId, List<RawLayerBinding>> entry
                : relation.layerInputsByRawImage().entrySet()) {
                final List<String> bindings = entry.getValue().stream()
                    .map(binding -> binding.rawLayerId().value() + "@" + binding.inputOrder()
                        + ":transform=" + binding.transformAvailability()
                        + ":clipping=" + binding.clippingAvailability())
                    .toList();
                byRaw.add(entry.getKey().value() + "=" + bindings);
            }
            projections.add("modelImage=" + relation.id().value()
                + "/currentRaw=" + relation.currentRawImageId().map(RawImageId::value)
                    .orElse(UNAVAILABLE_VALUE)
                + "/linkedRaw=" + relation.linkedRawImageIds().stream()
                    .map(RawImageId::value).toList()
                + "/rawLayerBindings=" + byRaw
                + "/artMeshIds=" + relation.usingArtMeshIds().stream()
                    .map(id -> id.value()).toList());
        }
        return projections.toString();
    }

    static String livePsdDocuments(final List<PsdClipMaskDocumentSnapshot> documents) {
        final List<String> layers = new ArrayList<>();
        for (final PsdClipMaskDocumentSnapshot document : documents) {
            for (int index = 0; index < document.layers().size(); index++) {
                appendLiveLayer(layers, document, document.layers().get(index), Integer.toString(index));
            }
        }
        return layers.toString();
    }

    static void appendLiveLayer(final List<String> layers,
        final PsdClipMaskDocumentSnapshot document, final PsdLayerSnapshot layer,
        final String path) {
        layers.add("documentId=" + diagnosticToken(document.documentId())
            + "/relativePath=" + diagnosticToken(document.relativePath())
            + "/layerPath=" + path
            + "/layerId=" + diagnosticToken(layer.layerId())
            + "/name=" + diagnosticToken(layer.name())
            + "/artMeshIds=" + layer.artMeshIds().stream().map(id -> id.value()).toList());
        for (int index = 0; index < layer.children().size(); index++) {
            appendLiveLayer(layers, document, layer.children().get(index), path + "." + index);
        }
    }

    static String diagnosticToken(final String value) {
        return value.replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("|", "\\|");
    }

    static String joinPrefix(final String prefix, final String suffix) {
        return normalizePrefix(prefix) + "." + suffix;
    }

    static String diagnosticExportLabel(final String prefix) {
        return "diagnostic-" + normalizePrefix(prefix).replaceAll("[^A-Za-z0-9._-]", "_");
    }

    static String normalizePrefix(final String prefix) {
        if (prefix == null || prefix.isBlank()) throw new IllegalArgumentException("prefix is required");
        String normalized = prefix;
        while (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.isBlank()) throw new IllegalArgumentException("prefix is required");
        return normalized;
    }

    static String valueOrUnavailable(final String value) {
        return value == null || value.isBlank() ? UNAVAILABLE_VALUE : value;
    }

    static final String UNAVAILABLE_VALUE = "unavailable";

    static boolean isCanonicalSha256(final String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    enum RawImageDeltaStatus {
        ZERO,
        UNIQUE,
        MULTIPLE,
        UNAVAILABLE,
        IDENTITY_CHANGED
    }

    record RawImageRelationDelta(RawImageDeltaStatus status,
        List<String> beforeRawIds, List<String> afterRawIds,
        List<RawImageId> addedRawImages, String diagnostic) {
        RawImageRelationDelta {
            status = Objects.requireNonNull(status, "status");
            beforeRawIds = List.copyOf(Objects.requireNonNull(beforeRawIds, "beforeRawIds"));
            afterRawIds = List.copyOf(Objects.requireNonNull(afterRawIds, "afterRawIds"));
            addedRawImages = List.copyOf(
                Objects.requireNonNull(addedRawImages, "addedRawImages"));
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static RawImageRelationDelta unavailable(final String diagnostic) {
            return new RawImageRelationDelta(
                RawImageDeltaStatus.UNAVAILABLE, List.of(), List.of(), List.of(), diagnostic);
        }

        static RawImageRelationDelta unavailable(final List<String> beforeRawIds,
            final List<String> afterRawIds, final String diagnostic) {
            return new RawImageRelationDelta(
                RawImageDeltaStatus.UNAVAILABLE, beforeRawIds, afterRawIds, List.of(), diagnostic);
        }

        int candidateCount() { return addedRawImages.size(); }
    }

    interface RawImageExportHandle {
        RawImageId rawId();
        byte[] bytes() throws Exception;
        PsdFileOperationResult stop() throws Exception;
    }

    @FunctionalInterface
    interface RawImageExportSupplier {
        RawImageExportHandle export(RawImageId raw) throws Exception;
    }

    /** Starts a raw export only after invoking the supplied preflight on the same EDT as export. */
    @FunctionalInterface
    interface RawExportStarter {
        RawExportStarted start(RawImageId raw, RawExportPreflight preflight) throws Exception;
    }

    @FunctionalInterface
    interface RawExportPreflight {
        void verify(RawExportObservation observation) throws Exception;
    }

    @FunctionalInterface
    interface RawExportObservationSupplier {
        RawExportObservation observe(RawImageId raw) throws Exception;
    }

    @FunctionalInterface
    interface RawExportStopper {
        PsdFileOperationResult stop(RawImageExportHandle handle) throws Exception;
    }

    /** Public-relation identity captured before or after one completed raw export. */
    record RawExportObservation(String documentId, String modelId, String binding,
        String modelImageId, String artMeshId, String candidateRawId, String currentRawId,
        long generation, int modelImageCount, int candidateCount, boolean relationsAvailable,
        String diagnostic) {
        RawExportObservation {
            documentId = valueOrUnavailable(documentId);
            modelId = valueOrUnavailable(modelId);
            binding = valueOrUnavailable(binding);
            modelImageId = valueOrUnavailable(modelImageId);
            artMeshId = valueOrUnavailable(artMeshId);
            candidateRawId = valueOrUnavailable(candidateRawId);
            currentRawId = valueOrUnavailable(currentRawId);
            diagnostic = diagnostic == null ? "" : diagnostic;
            if (generation < -1L) throw new IllegalArgumentException(
                "raw export observation generation must be -1 or greater");
            if (modelImageCount < -1 || candidateCount < -1) {
                throw new IllegalArgumentException(
                    "raw export observation counts must be -1 or greater");
            }
        }

        /** Source-compatible constructor for legacy unavailable-only offline fixtures. */
        RawExportObservation(final String documentId, final String modelId, final String binding,
            final String modelImageId, final String candidateRawId, final String currentRawId,
            final long generation, final int modelImageCount, final int candidateCount,
            final boolean relationsAvailable, final String diagnostic) {
            this(documentId, modelId, binding, modelImageId, UNAVAILABLE_VALUE,
                candidateRawId, currentRawId, generation, modelImageCount, candidateCount,
                relationsAvailable, diagnostic);
        }

        static RawExportObservation unavailable(final RawImageId candidate,
            final String diagnostic) {
            return new RawExportObservation(
                UNAVAILABLE_VALUE,
                UNAVAILABLE_VALUE,
                UNAVAILABLE_VALUE,
                UNAVAILABLE_VALUE,
                UNAVAILABLE_VALUE,
                candidate == null ? UNAVAILABLE_VALUE : candidate.value(),
                UNAVAILABLE_VALUE,
                -1L,
                -1,
                -1,
                false,
                diagnostic
            );
        }
    }

    record RawExportStarted(RawExportObservation before, RawImageExportHandle handle) {
        RawExportStarted {
            before = Objects.requireNonNull(before, "before");
            handle = Objects.requireNonNull(handle, "handle");
        }
    }

    static final class RawExportTargetUnavailableException
        extends IllegalStateException {
        static final long serialVersionUID = 1L;

        RawExportTargetUnavailableException(final String message) {
            super(message);
        }
    }
}
