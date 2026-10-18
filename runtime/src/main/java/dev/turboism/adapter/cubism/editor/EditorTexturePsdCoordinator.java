package dev.turboism.adapter.cubism.editor;

import dev.turboism.core.runtime.psd.PsdExportHost;
import dev.turboism.core.runtime.psd.PsdReplaceHost;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.model.ModelImageRelation;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Coordinates native PSD export, replacement, observation, and session diagnostic gating.
 */
final class EditorTexturePsdCoordinator {

    private static final String APP_INSTANCE = "cubism.editor-model.app-controller.instance";
    private static final String CURRENT_DOCUMENT = "cubism.editor-model.app-controller.current-document";
    private static final String LAYERED_IMAGE_GUID = "cubism.editor-model.layered-image.guid";
    private static final String GUID_VALUE = "cubism.editor-model.guid.value";

    private static final int MAX_EXPORT_DIAGNOSTIC_SESSIONS = 8;
    private final Object exportDiagnosticGateLock = new Object();
    private final LinkedHashMap<ExportSessionKey, ExportDiagnosticGate> exportDiagnosticGates =
            new LinkedHashMap<>(MAX_EXPORT_DIAGNOSTIC_SESSIONS, 0.75f, true);

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard modelGuard;
    private final LongSupplier generationSupplier;
    private final EditorTextureRelationsAccess relationAccess;
    private final EditorRawImagePsdAccess psdAccess;
    private final EditorRawImagePsdSourceBinding psdSourceBinding;
    private final EditorRawImagePsdReplaceAccess psdReplaceAccess;

    EditorTexturePsdCoordinator(
            final VerifiedMemberResolver resolver,
            final EditorParameterCombinedAccess.ModelGuard modelGuard,
            final LongSupplier generationSupplier,
            final EditorTextureRelationsAccess relationAccess) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
        this.generationSupplier = Objects.requireNonNull(generationSupplier, "generationSupplier");
        this.relationAccess = Objects.requireNonNull(relationAccess, "relationAccess");
        this.psdAccess = new EditorRawImagePsdAccess(resolver, modelGuard::requireCurrent);
        this.psdSourceBinding =
                new EditorRawImagePsdSourceBinding(resolver, new EditorRawImagePsdIntegrityAccess(resolver));
        this.psdReplaceAccess = new EditorRawImagePsdReplaceAccess(resolver, modelGuard::requireCurrent);
    }

    PsdExportHost.Observation exportPsdTo(
            final String identity,
            final Object source,
            final Object model,
            final RawImageId sourceId,
            final Path destination,
            final Runnable admission) {
        Objects.requireNonNull(admission, "admission");
        final AtomicBoolean exportGuardSeen = new AtomicBoolean();
        final AtomicBoolean postGuardStarted = new AtomicBoolean();
        final AtomicReference<EditorTextureReplacementDiagnostic.ExportSession> diagnosticSession =
                new AtomicReference<>();
        final EditorRawImagePsdAccess access = new EditorRawImagePsdAccess(resolver, (id, currentModel) -> {
            admission.run();
            final boolean firstGuard = exportGuardSeen.compareAndSet(false, true);
            if (!firstGuard) postGuardStarted.set(true);
            modelGuard.requireCurrent(id, currentModel);
            if (!EditorTextureReplacementDiagnostic.enabled()) return;
            if (firstGuard) {
                try {
                    final ExportDiagnosticGate gate = exportDiagnosticGate(identity, source, model);
                    final EditorRawImagePsdSourceBinding.BindingResult binding =
                            psdSourceBinding.bindOnHostThread(source, sourceId);
                    if (binding.status() != EditorRawImagePsdSourceBinding.BindingStatus.MATCHED || !gate.consume()) {
                        return;
                    }
                    final Object document = diagnosticDocument();
                    EditorTextureReplacementDiagnostic.beginExport(
                                    resolver, identity, source, document, model, sourceId)
                            .ifPresent(diagnosticSession::set);
                } catch (RuntimeException | LinkageError ignored) {
                    // Diagnostic setup must not change admission or ordinary export behavior.
                }
                return;
            }
            final EditorTextureReplacementDiagnostic.ExportSession session = diagnosticSession.get();
            if (session != null) session.finish(true, null);
        });
        try {
            final EditorRawImagePsdAccess.ExportResult result =
                    access.exportPsd(identity, source, model, sourceId, destination);
            final EditorTextureReplacementDiagnostic.ExportSession session = diagnosticSession.getAndSet(null);
            if (session != null) {
                // A save that did not reach the access' second current-guard has no valid
                // post native read; the finish method records UNAVAILABLE without reading host.
                session.finish(false, "export-post-guard-not-observed");
            }
            return result.observation();
        } catch (RuntimeException | LinkageError exportFailure) {
            final EditorTextureReplacementDiagnostic.ExportSession session = diagnosticSession.getAndSet(null);
            if (session != null) {
                final String cause = postGuardStarted.get()
                        ? "post-current-guard-failed:" + message(exportFailure)
                        : "export-call-threw:" + message(exportFailure);
                session.finish(false, cause);
            }
            throw exportFailure;
        }
    }

    PsdReplaceHost.Replacement replaceWithStagedPsd(
            final String identity,
            final Object source,
            final Object model,
            final RawImageId target,
            final Path stage,
            final String sourceFileName,
            final Runnable admission) {
        Objects.requireNonNull(admission, "admission");
        return EditorHostThread.dispatch(
                "Cubism PSD raw-image replace",
                () -> replaceOnHostThread(identity, source, model, target, stage, sourceFileName, admission));
    }

    private PsdReplaceHost.Replacement replaceOnHostThread(
            final String identity,
            final Object source,
            final Object model,
            final RawImageId target,
            final Path stage,
            final String sourceFileName,
            final Runnable admission) {
        admission.run();
        modelGuard.requireCurrent(identity, model);

        final TextureRelationsSnapshot before = relationAccess.relations(identity, source, model);
        final EditorRawImagePsdSourceBinding.BindingResult binding =
                psdSourceBinding.bindIdentityOnHostThread(source, target);
        if (binding.status() != EditorRawImagePsdSourceBinding.BindingStatus.MATCHED) {
            return new PsdReplaceHost.Replacement(
                    "TARGET_NOT_FOUND",
                    true,
                    false,
                    false,
                    false,
                    false,
                    Optional.empty(),
                    "The requested raw image is not exactly resolvable in the current model source.");
        }

        final Object appController;
        final Object document;
        try {
            appController = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
            document = appController == null
                    ? null
                    : resolver.invoke("cubism.editor-model.app-controller.current-document", appController);
        } catch (RuntimeException resolutionFailure) {
            return new PsdReplaceHost.Replacement(
                    "DOCUMENT_UNAVAILABLE",
                    true,
                    false,
                    false,
                    false,
                    false,
                    Optional.empty(),
                    "The current Cubism document could not be resolved for replacement.");
        }

        final Object incoming;
        try {
            incoming = psdAccess.parseStageOnHostThread(stage, sourceFileName);
        } catch (IOException | RuntimeException parseFailure) {
            return EditorRawImagePsdAccess.unreadableStage(parseFailure);
        }

        final RawImageId incomingRaw;
        try {
            // This is production application evidence, not opt-in diagnostic capture. Keep
            // the exact parsed identity fixed across the one native call and fresh reread.
            incomingRaw =
                    new RawImageId(guidValue(resolver.invoke(LAYERED_IMAGE_GUID, incoming), "incoming raw image"));
        } catch (RuntimeException | LinkageError identityFailure) {
            return new PsdReplaceHost.Replacement(
                    "UNAVAILABLE",
                    true,
                    false,
                    false,
                    false,
                    false,
                    Optional.empty(),
                    "The parsed incoming raw image GUID could not be verified before native replace.");
        }
        if (target.equals(incomingRaw)) {
            return new PsdReplaceHost.Replacement(
                    "UNAVAILABLE",
                    true,
                    false,
                    false,
                    false,
                    false,
                    Optional.empty(),
                    "The parsed incoming raw image is not distinct from the requested target.");
        }

        final EditorTextureReplacementDiagnostic.Session diagnostic =
                replacementDiagnostic(identity, source, model, before, document, target, incoming);
        final EditorRawImagePsdReplaceAccess.ReplaceResult nativeResult;
        try {
            nativeResult = psdReplaceAccess.replacePsd(
                    identity,
                    model,
                    appController,
                    document,
                    List.of(binding.candidate().nativeSource()),
                    incoming,
                    stage);
        } catch (RuntimeException | LinkageError nativeCallFailure) {
            finishDiagnostic(diagnostic, null, null, "replace-call-threw:" + message(nativeCallFailure));
            throw nativeCallFailure;
        }

        if (nativeResult.status() == EditorRawImagePsdReplaceAccess.ReplaceStatus.EDITING_REJECTED) {
            finishDiagnostic(diagnostic, null, nativeResult, "native-not-invoked:editing-rejected");
            return new PsdReplaceHost.Replacement(
                    "HOST_EDIT_IN_PROGRESS",
                    nativeResult.postCurrentGuardPassed(),
                    false,
                    false,
                    true,
                    false,
                    Optional.empty(),
                    "Cubism is currently applying another edit; no replacement was attempted.");
        }
        if (nativeResult.status() == EditorRawImagePsdReplaceAccess.ReplaceStatus.UNAVAILABLE) {
            finishDiagnostic(diagnostic, null, nativeResult, "native-not-invoked:unavailable");
            return new PsdReplaceHost.Replacement(
                    "UNAVAILABLE",
                    nativeResult.postCurrentGuardPassed(),
                    false,
                    false,
                    false,
                    false,
                    Optional.empty(),
                    "The native replacement seam is unavailable for this host build.");
        }
        if (nativeResult.status() == EditorRawImagePsdReplaceAccess.ReplaceStatus.INVALID_INPUT) {
            finishDiagnostic(diagnostic, null, nativeResult, "native-not-invoked:invalid-input");
            return new PsdReplaceHost.Replacement(
                    "INVALID_INPUT",
                    nativeResult.postCurrentGuardPassed(),
                    false,
                    false,
                    false,
                    false,
                    Optional.empty(),
                    "The native replacement inputs were rejected before any mutation.");
        }
        if (nativeResult.status() == EditorRawImagePsdReplaceAccess.ReplaceStatus.PARTIAL_FAILURE) {
            finishDiagnosticAfterAttempt(identity, source, model, diagnostic, nativeResult);
            return new PsdReplaceHost.Replacement(
                    "NATIVE_OUTCOME_UNKNOWN",
                    nativeResult.postCurrentGuardPassed(),
                    false,
                    true,
                    false,
                    false,
                    Optional.empty(),
                    "The native replacement did not report a usable outcome; mutation state is unknown.");
        }

        // The native call returned. Application is claimed only from a fresh observation.
        final TextureRelationsSnapshot after;
        try {
            after = relationAccess.relations(identity, source, model);
        } catch (RuntimeException rereadFailure) {
            finishDiagnostic(diagnostic, null, nativeResult, "post-relation-read-failed:" + message(rereadFailure));
            return new PsdReplaceHost.Replacement(
                    "NATIVE_RETURNED_UNOBSERVED",
                    nativeResult.postCurrentGuardPassed(),
                    true,
                    false,
                    false,
                    false,
                    Optional.empty(),
                    "The native replacement returned but current state could not be re-read.");
        }
        finishDiagnostic(diagnostic, after, nativeResult, null);
        if (!after.isAvailable()) {
            return new PsdReplaceHost.Replacement(
                    "NATIVE_RETURNED_UNOBSERVED",
                    nativeResult.postCurrentGuardPassed(),
                    true,
                    false,
                    false,
                    false,
                    Optional.empty(),
                    "The native replacement returned but the relation projection is unavailable.");
        }
        final Optional<RawImageId> observed = observedRawImage(after, modelImagesUsing(before, target), incomingRaw);
        return new PsdReplaceHost.Replacement(
                "NATIVE_RETURNED",
                nativeResult.postCurrentGuardPassed(),
                true,
                false,
                false,
                true,
                observed,
                "The native replacement returned and current state was re-read; application evidence "
                        + "requires an observed incoming binding on a previously targeted model image.",
                observed.isPresent()
                        ? Optional.empty()
                        : Optional.of(replacementObservationFailure(before, after, target, incomingRaw)));
    }

    private Object diagnosticDocument() {
        try {
            final Object app = resolver.invokeStatic(APP_INSTANCE);
            return app == null ? null : resolver.invoke(CURRENT_DOCUMENT, app);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private EditorTextureReplacementDiagnostic.Session replacementDiagnostic(
            final String identity,
            final Object source,
            final Object model,
            final TextureRelationsSnapshot before,
            final Object document,
            final RawImageId target,
            final Object incoming) {
        try {
            if (!EditorTextureReplacementDiagnostic.enabled()) return null;
            final EditorTextureReplacementDiagnostic.RawIdentity incomingRaw =
                    EditorTextureReplacementDiagnostic.resolveIncomingRaw(resolver, incoming);
            return EditorTextureReplacementDiagnostic.begin(
                            resolver, identity, source, document, model, target, incomingRaw, before)
                    .orElse(null);
        } catch (RuntimeException | LinkageError diagnosticFailure) {
            // Diagnostic setup must not move, duplicate, classify, or mask the native call.
            return null;
        }
    }

    private void finishDiagnosticAfterAttempt(
            final String identity,
            final Object source,
            final Object model,
            final EditorTextureReplacementDiagnostic.Session diagnostic,
            final EditorRawImagePsdReplaceAccess.ReplaceResult nativeResult) {
        if (diagnostic == null) return;
        if (!nativeResult.postCurrentGuardPassed()) {
            finishDiagnostic(diagnostic, null, nativeResult, "post-current-guard-failed");
            return;
        }
        try {
            final TextureRelationsSnapshot after = relationAccess.relations(identity, source, model);
            finishDiagnostic(diagnostic, after, nativeResult, null);
        } catch (RuntimeException | LinkageError rereadFailure) {
            finishDiagnostic(diagnostic, null, nativeResult, "post-relation-read-failed:" + message(rereadFailure));
        }
    }

    private void finishDiagnostic(
            final EditorTextureReplacementDiagnostic.Session diagnostic,
            final TextureRelationsSnapshot after,
            final EditorRawImagePsdReplaceAccess.ReplaceResult nativeResult,
            final String cause) {
        if (diagnostic != null) diagnostic.finish(after, nativeResult, cause);
    }

    /**
     * Shares the one-shot export budget across every texture facade for one exact model session.
     * The bounded LRU is keyed by object identity for source/model, plus the runtime binding and
     * generation; no facade from another session can consume or observe this gate.
     */
    private ExportDiagnosticGate exportDiagnosticGate(final String identity, final Object source, final Object model) {
        final ExportSessionKey key = new ExportSessionKey(identity, generationSupplier.getAsLong(), source, model);
        synchronized (exportDiagnosticGateLock) {
            final ExportDiagnosticGate existing = exportDiagnosticGates.get(key);
            if (existing != null) return existing;
            if (exportDiagnosticGates.size() >= MAX_EXPORT_DIAGNOSTIC_SESSIONS) {
                final var oldest = exportDiagnosticGates.entrySet().iterator();
                if (oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
            }
            final ExportDiagnosticGate created = new ExportDiagnosticGate(key);
            exportDiagnosticGates.put(key, created);
            return created;
        }
    }

    /**
     * Model images whose current raw image is the replaced target in the pre-replacement read.
     * This is the bounded observation subset for the requested target; native matching may
     * affect other model images, which this method deliberately does not infer.
     */
    static List<ModelImageId> modelImagesUsing(final TextureRelationsSnapshot snapshot, final RawImageId target) {
        final List<ModelImageId> affected = new ArrayList<>();
        for (final ModelImageRelation relation : snapshot.modelImages()) {
            if (relation.currentRawImageId().filter(target::equals).isPresent()) {
                affected.add(relation.id());
            }
        }
        return List.copyOf(affected);
    }

    /**
     * Proves an incoming binding on a previously targeted model image. Native structural matching
     * may retain or remove unmatched images; agreement of every old image is not its contract.
     */
    static Optional<RawImageId> observedRawImage(
            final TextureRelationsSnapshot after,
            final List<ModelImageId> affected,
            final RawImageId expectedIncoming) {
        if (after == null || !after.isAvailable() || affected.isEmpty() || expectedIncoming == null) {
            return Optional.empty();
        }
        if (after.rawImages().stream()
                        .filter(raw -> expectedIncoming.equals(raw.id()))
                        .count()
                != 1L) {
            return Optional.empty();
        }
        boolean observed = false;
        for (final ModelImageId id : affected) {
            final Optional<ModelImageRelation> relation = after.modelImage(id);
            if (relation.isEmpty()
                    || relation.orElseThrow()
                            .currentRawImageId()
                            .filter(expectedIncoming::equals)
                            .isEmpty()) continue;
            final ModelImageRelation image = relation.orElseThrow();
            if (!image.linkedRawImageIds().contains(expectedIncoming)
                    || image.inputsByRawImage()
                            .getOrDefault(expectedIncoming, List.of())
                            .isEmpty()) {
                // A dangling current pointer or an empty selector is not application evidence.
                return Optional.empty();
            }
            observed = true;
        }
        return observed ? Optional.of(expectedIncoming) : Optional.empty();
    }

    /** Explains a failed observation using existing snapshots only; never changes admission or matching. */
    static PsdReplaceHost.Failure replacementObservationFailure(
            final TextureRelationsSnapshot before,
            final TextureRelationsSnapshot after,
            final RawImageId target,
            final RawImageId incoming) {
        final long incomingCount = after.rawImages().stream()
                .filter(raw -> incoming.equals(raw.id()))
                .count();
        final List<ModelImageId> affected = modelImagesUsing(before, target);
        String category = "INCOMING_NOT_CURRENT";
        if (incomingCount == 0L) {
            category = "INCOMING_RAW_ABSENT";
        } else if (incomingCount != 1L) {
            category = "INCOMING_RAW_NOT_UNIQUE";
        } else if (affected.isEmpty()) {
            category = "TARGET_MODEL_IMAGES_ABSENT";
        } else {
            boolean hasTargetLayerInputs = false;
            for (final ModelImageId id : affected) {
                final ModelImageRelation original = before.modelImage(id).orElseThrow();
                hasTargetLayerInputs |= !original.inputsByRawImage()
                        .getOrDefault(target, List.of())
                        .isEmpty();
                final Optional<ModelImageRelation> current = after.modelImage(id);
                if (current.isEmpty()
                        || current.orElseThrow()
                                .currentRawImageId()
                                .filter(incoming::equals)
                                .isEmpty()) continue;
                final ModelImageRelation image = current.orElseThrow();
                if (!image.linkedRawImageIds().contains(incoming)) {
                    return new PsdReplaceHost.Failure("POST_BINDING_OBSERVATION", "INCOMING_LINK_MISSING");
                }
                if (image.inputsByRawImage().getOrDefault(incoming, List.of()).isEmpty()) {
                    return new PsdReplaceHost.Failure("POST_BINDING_OBSERVATION", "INCOMING_LAYER_INPUTS_EMPTY");
                }
            }
            if (!hasTargetLayerInputs) category = "TARGET_LAYER_INPUTS_EMPTY_BEFORE";
        }
        return new PsdReplaceHost.Failure("POST_BINDING_OBSERVATION", category);
    }

    private String guidValue(final Object guid, final String label) {
        final Object raw = resolver.invoke(GUID_VALUE, guid);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalStateException("Editor " + label + " GUID is invalid.");
        }
        return value;
    }

    private static String message(final Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage();
    }

    private static final class ExportDiagnosticGate {
        private final ExportSessionKey key;
        private final AtomicBoolean consumed = new AtomicBoolean();

        private ExportDiagnosticGate(final ExportSessionKey key) {
            this.key = key;
        }

        private boolean consume() {
            return consumed.compareAndSet(false, true);
        }
    }

    private static final class ExportSessionKey {
        private final String identity;
        private final long generation;
        private final Object source;
        private final Object model;

        private ExportSessionKey(
                final String identity, final long generation, final Object source, final Object model) {
            this.identity = identity;
            this.generation = generation;
            this.source = source;
            this.model = model;
        }

        @Override
        public boolean equals(final Object other) {
            if (this == other) return true;
            if (!(other instanceof ExportSessionKey key)) return false;
            return generation == key.generation
                    && Objects.equals(identity, key.identity)
                    && source == key.source
                    && model == key.model;
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(identity);
            result = 31 * result + Long.hashCode(generation);
            result = 31 * result + System.identityHashCode(source);
            result = 31 * result + System.identityHashCode(model);
            return result;
        }
    }
}
