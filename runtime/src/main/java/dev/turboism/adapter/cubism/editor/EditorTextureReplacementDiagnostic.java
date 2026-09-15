package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorTextureRelationsSelectorContract;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in, bounded validation evidence around one PSD raw-image replacement.
 *
 * <p>The native relation fields in this class are read directly from the
 * reviewed resolver aliases, using the same model source and model as the
 * replacement call. The public {@link TextureRelationsSnapshot} remains only
 * a structural binding check; its projected selector map is never used as the
 * native {@code hasLayerInputData} value.</p>
 *
 * <p>This is evidence only. A synchronous native return is recorded as a
 * return observation, never as a commit or asynchronous completion. All
 * diagnostic failures are converted to unavailable evidence and are isolated
 * from the native return, exception, and replacement classification. Fatal VM
 * errors are deliberately not caught.</p>
 */
final class EditorTextureReplacementDiagnostic {

    static final String ENABLE_PROPERTY =
        "turboism.validation.editorTextureReplaceDiagnostic";
    static final String ARTIFACT = "editor-texture-psd-replace-diagnostic.txt";

    private static final String SUPPORTED_VERSION = "5.3.02";
    private static final String TEXTURE_MANAGER =
        "cubism.editor-model.model-source.texture-manager";
    private static final String RAW_IMAGES =
        "cubism.editor-model.texture-manager.raw-images";
    private static final String ALL_MODEL_IMAGES =
        "cubism.editor-model.texture-manager.all-model-images";
    private static final String MODEL_IMAGE_GROUPS =
        "cubism.editor-model.texture-manager.model-image-groups";
    private static final String WRAPPER_IMAGE =
        "cubism.editor-model.layered-image-wrapper.image";
    private static final String WRAPPER_REPLACED =
        "cubism.editor-model.layered-image-wrapper.replaced";
    private static final String LAYERED_IMAGE_CLASS =
        "cubism.editor-model.layered-image.class";
    private static final String LAYERED_IMAGE_GUID =
        "cubism.editor-model.layered-image.guid";
    private static final String MODEL_IMAGE_CLASS =
        "cubism.editor-model.model-image.class";
    private static final String MODEL_IMAGE_GUID =
        "cubism.editor-model.model-image.guid";
    private static final String MODEL_IMAGE_LINKED_RAW =
        "cubism.editor-model.model-image.linked-raw-image-guids";
    private static final String MODEL_IMAGE_INPUT_FILTER_ENV =
        "cubism.editor-model.model-image.input-filter-env";
    private static final String FILTER_ENV_CLASS =
        "cubism.editor-model.model-image-filter-env.class";
    private static final String FILTER_ENV_HAS_LAYER_INPUT =
        "cubism.editor-model.model-image-filter-env.has-layer-input-data";
    private static final String FILTER_ENV_LAYER_INPUT =
        "cubism.editor-model.model-image-filter-env.layer-input-data";
    private static final String FILTER_ENV_HAS_CURRENT =
        "cubism.editor-model.model-image-filter-env.has-current-image-guid";
    private static final String FILTER_ENV_CURRENT =
        "cubism.editor-model.model-image-filter-env.current-image-guid";
    private static final String SELECTOR_MAP_CLASS =
        "cubism.editor-model.layer-selector-map.class";
    private static final String SELECTOR_MAP_IMAGE_INPUTS =
        "cubism.editor-model.layer-selector-map.image-to-layer-input";
    private static final String GROUP_CLASS =
        "cubism.editor-model.model-image-group.class";
    private static final String GROUP_NAME =
        "cubism.editor-model.model-image-group.group-name";
    private static final String GROUP_IMAGES =
        "cubism.editor-model.model-image-group.model-images";
    private static final String GROUP_LINKED_RAW =
        "cubism.editor-model.model-image-group.linked-raw-image-guids";
    private static final String GUID_VALUE = "cubism.editor-model.guid.value";

    private static final int MAX_ITEMS = 32;
    private static final int MAX_VALUE_LENGTH = 128;
    static final int MAX_LINE_LENGTH = 32_000;
    private static final int LINE_RESERVE = 160;
    private static final AtomicLong CORRELATION_SEQUENCE = new AtomicLong();

    private EditorTextureReplacementDiagnostic() {
    }

    /** Returns whether this validation-only observation was explicitly enabled. */
    static boolean enabled() {
        try {
            return Boolean.getBoolean(ENABLE_PROPERTY);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** Resolves the parsed incoming raw identity through the reviewed GUID aliases. */
    static RawIdentity resolveIncomingRaw(
        final VerifiedMemberResolver resolver,
        final Object incoming
    ) {
        try {
            if (!enabled()) return RawIdentity.unavailable("diagnostic-disabled");
            if (resolver == null) return RawIdentity.unavailable("resolver-unavailable");
            if (incoming == null) return RawIdentity.unavailable("incoming-layered-image-null");
            final Object guid = resolver.invoke(LAYERED_IMAGE_GUID, incoming);
            if (guid == null) return RawIdentity.unavailable("incoming-guid-null");
            final Object value = resolver.invoke(GUID_VALUE, guid);
            if (!(value instanceof String text) || text.isBlank()) {
                return RawIdentity.unavailable("incoming-guid-invalid");
            }
            return RawIdentity.available(text);
        } catch (RuntimeException | LinkageError failure) {
            return RawIdentity.unavailable("incoming-guid-unavailable:" + message(failure));
        }
    }

    /**
     * Compatibility overload for structural-only unit fixtures. Production
     * calls use the resolver/source overload below, so no native field can be
     * inferred from a public snapshot.
     */
    static Optional<Session> begin(
        final String sessionIdentity,
        final Object document,
        final Object model,
        final RawImageId oldRaw,
        final RawIdentity incomingRaw,
        final TextureRelationsSnapshot before
    ) {
        return begin(
            null,
            sessionIdentity,
            null,
            document,
            model,
            oldRaw,
            incomingRaw,
            before
        );
    }

    /** Starts one correlated observation, returning empty only when disabled or setup failed. */
    static Optional<Session> begin(
        final VerifiedMemberResolver resolver,
        final String sessionIdentity,
        final Object source,
        final Object document,
        final Object model,
        final RawImageId oldRaw,
        final RawIdentity incomingRaw,
        final TextureRelationsSnapshot before
    ) {
        try {
            if (!enabled()) return Optional.empty();
        } catch (RuntimeException | LinkageError failure) {
            return Optional.empty();
        }
        final String correlation = "texture-replace-"
            + CORRELATION_SEQUENCE.incrementAndGet();
        try {
            final Session session = new Session(
                correlation,
                resolver,
                sessionIdentity,
                source,
                document,
                model,
                oldRaw,
                incomingRaw,
                before
            );
            session.writePre();
            return Optional.of(session);
        } catch (RuntimeException | LinkageError failure) {
            writeSetupFailure(correlation, sessionIdentity, document, model, oldRaw, incomingRaw, failure);
            return Optional.empty();
        }
    }

    /** Starts the separate, first-export-only observation used to compare export pre/post state. */
    static Optional<ExportSession> beginExport(
        final VerifiedMemberResolver resolver,
        final String sessionIdentity,
        final Object source,
        final Object document,
        final Object model,
        final RawImageId oldRaw
    ) {
        try {
            if (!enabled()) return Optional.empty();
        } catch (RuntimeException | LinkageError failure) {
            return Optional.empty();
        }
        final String correlation = "texture-export-"
            + CORRELATION_SEQUENCE.incrementAndGet();
        try {
            final ExportSession session = new ExportSession(
                correlation,
                resolver,
                sessionIdentity,
                source,
                document,
                model,
                oldRaw
            );
            session.writePre();
            return Optional.of(session);
        } catch (RuntimeException | LinkageError failure) {
            writeExportSetupFailure(
                correlation,
                sessionIdentity,
                source,
                document,
                model,
                oldRaw,
                failure
            );
            return Optional.empty();
        }
    }

    /** A raw identity plus a bounded reason when the reviewed GUID read is unavailable. */
    record RawIdentity(String value, String cause) {
        RawIdentity {
            if ((value == null) == (cause == null)) {
                throw new IllegalArgumentException("raw identity must be available or unavailable");
            }
            if (value != null && value.isBlank()) {
                throw new IllegalArgumentException("available raw identity must not be blank");
            }
            if (cause != null && cause.isBlank()) {
                throw new IllegalArgumentException("unavailable raw identity needs a cause");
            }
        }

        static RawIdentity available(final String value) {
            return new RawIdentity(Objects.requireNonNull(value, "value"), null);
        }

        static RawIdentity unavailable(final String cause) {
            return new RawIdentity(null, Objects.requireNonNull(cause, "cause"));
        }

        boolean isAvailable() {
            return value != null;
        }
    }

    /** One pre/native/post record; finishing more than once is ignored. */
    static final class Session {
        private final String correlation;
        private final VerifiedMemberResolver resolver;
        private final String sessionIdentity;
        private final Object source;
        private final Object model;
        private final String sourceIdentity;
        private final String documentIdentity;
        private final String modelIdentity;
        private final String currentGuardBinding;
        private final RawImageId oldRaw;
        private final RawIdentity incomingRaw;
        private final TextureRelationsSnapshot before;
        private final AtomicBoolean finished = new AtomicBoolean();

        private Session(
            final String correlation,
            final VerifiedMemberResolver resolver,
            final String sessionIdentity,
            final Object source,
            final Object document,
            final Object model,
            final RawImageId oldRaw,
            final RawIdentity incomingRaw,
            final TextureRelationsSnapshot before
        ) {
            this.correlation = Objects.requireNonNull(correlation, "correlation");
            this.resolver = resolver;
            this.sessionIdentity = sessionIdentity;
            this.source = source;
            this.model = model;
            this.sourceIdentity = identity(source);
            this.documentIdentity = identity(document);
            this.modelIdentity = identity(model);
            this.currentGuardBinding = safe(sessionIdentity) + "/" + modelIdentity;
            this.oldRaw = oldRaw;
            this.incomingRaw = Objects.requireNonNull(incomingRaw, "incomingRaw");
            this.before = before;
        }

        /** Writes the post side once; diagnostic failures never escape this method. */
        void finish(
            final TextureRelationsSnapshot after,
            final EditorRawImagePsdReplaceAccess.ReplaceResult nativeResult,
            final String postCause
        ) {
            if (!finished.compareAndSet(false, true)) return;
            try {
                writeObservation("post", after, nativeResult, postCause);
            } catch (RuntimeException | LinkageError failure) {
                writeFailure("post", "diagnostic-finish-failed:" + message(failure), nativeResult);
            }
        }

        private void writePre() {
            try {
                writeObservation("pre", before, null, null);
            } catch (RuntimeException | LinkageError failure) {
                writeFailure("pre", "diagnostic-pre-failed:" + message(failure), null);
            }
        }

        private void writeObservation(
            final String phase,
            final TextureRelationsSnapshot snapshot,
            final EditorRawImagePsdReplaceAccess.ReplaceResult nativeResult,
            final String cause
        ) {
            final String effectiveCause = "post".equals(phase)
                && nativeResult != null
                && !nativeResult.nativeInvocationAttempted()
                ? "native-not-invoked"
                : "post".equals(phase)
                    && nativeResult != null
                    && !nativeResult.postCurrentGuardPassed()
                    ? "post-current-guard-failed"
                    : cause;
            final ObservationView view = observe(snapshot, phase, effectiveCause);
            final NativeRelationObservation nativeEvidence = shouldReadNativeEvidence(
                phase, nativeResult
            )
                ? NativeRelationObservation.capture(
                    resolver, source, model, oldRaw, incomingRaw
                )
                : NativeRelationObservation.unavailable(
                    effectiveCause == null || effectiveCause.isBlank()
                        ? "native-observation-not-attempted"
                        : effectiveCause
                );

            final BoundedLine line = new BoundedLine(MAX_LINE_LENGTH);
            line.add("correlation", correlation);
            line.add("phase", phase);
            line.add("sessionIdentity", sessionIdentity);
            line.add("sourceIdentity", sourceIdentity);
            line.add("documentIdentity", documentIdentity);
            line.add("modelIdentity", modelIdentity);
            line.add("currentGuardBinding", currentGuardBinding);
            if (nativeResult == null) {
                line.add("currentGuardPrePassed", "PASSED_BY_CALLER");
            } else {
                line.add("currentGuardPrePassed", nativeResult.preCurrentGuardPassed());
                line.add("currentGuardPostPassed", nativeResult.postCurrentGuardPassed());
            }
            line.add("oldRaw", oldRaw == null ? null : oldRaw.value());
            line.add("incomingRaw", incomingRaw.value());
            line.add("incomingRawStatus", incomingRaw.isAvailable() ? "AVAILABLE" : "UNAVAILABLE");
            if (!incomingRaw.isAvailable()) line.add("incomingRawCause", incomingRaw.cause());
            line.add("observation", view.status());
            line.add("observationCause", view.cause());
            line.add("relationBinding", view.binding());
            line.add("generation", view.generation());
            line.add("revision", view.revision());
            appendNativeEvidence(line, nativeEvidence);
            line.add("artPathExclusion", "UNAVAILABLE:reviewed-alias-not-admitted");
            line.add("nativeCompletionCallback", "UNAVAILABLE:reviewed-alias-not-admitted");
            line.add("nativeReturnObservation", "SYNCHRONOUS_RETURN_ONLY");
            appendNativeResult(line, nativeResult);
            EditorObjectValidationTrace.writeArtifact(ARTIFACT, line.finish(), true);
        }

        private boolean shouldReadNativeEvidence(
            final String phase,
            final EditorRawImagePsdReplaceAccess.ReplaceResult nativeResult
        ) {
            if ("pre".equals(phase)) return true;
            return nativeResult != null
                && nativeResult.nativeInvocationAttempted()
                && nativeResult.postCurrentGuardPassed();
        }

        private ObservationView observe(
            final TextureRelationsSnapshot snapshot,
            final String phase,
            final String explicitCause
        ) {
            final String unavailableCause = explicitCause == null || explicitCause.isBlank()
                ? null
                : explicitCause;
            if (unavailableCause != null) {
                return ObservationView.unavailable(unavailableCause);
            }
            if (snapshot == null) {
                return ObservationView.unavailable(phase + "-relation-snapshot-missing");
            }
            if (!snapshot.isAvailable()) {
                return ObservationView.unavailable(phase + "-relation-snapshot-unavailable");
            }
            if (sessionIdentity == null || !sessionIdentity.equals(snapshot.binding())) {
                return ObservationView.unavailable("relation-binding-mismatch");
            }
            if (phase.equals("post") && before != null && before.isAvailable()
                && snapshot.generation() != before.generation()) {
                return ObservationView.unavailable("relation-generation-mismatch");
            }
            if (phase.equals("post") && before != null && before.isAvailable()
                && snapshot.revision() <= before.revision()) {
                return ObservationView.unavailable("relation-revision-not-newer");
            }
            if (oldRaw == null) {
                return ObservationView.unavailable("old-raw-missing");
            }
            if (!containsRaw(snapshot, oldRaw.value())) {
                return ObservationView.unavailable(phase + "-old-raw-not-present");
            }
            if (!incomingRaw.isAvailable()) {
                return ObservationView.unavailable(incomingRaw.cause());
            }
            if (documentIdentity.equals("null")) {
                return ObservationView.unavailable("document-missing");
            }
            if (modelIdentity.equals("null")) {
                return ObservationView.unavailable("model-missing");
            }
            if (phase.equals("post") && !containsRaw(snapshot, incomingRaw.value())) {
                return ObservationView.unavailable("post-incoming-raw-not-present");
            }
            return ObservationView.available(
                snapshot.binding(),
                Long.toString(snapshot.generation()),
                Long.toString(snapshot.revision())
            );
        }

        private boolean containsRaw(final TextureRelationsSnapshot snapshot, final String rawId) {
            return snapshot.rawImages().stream().anyMatch(raw -> raw.id().value().equals(rawId));
        }
    }

    /**
     * One first-export pre/post pair. Export has no native completion callback, so this class only
     * records direct relation observations and never records a replacement-style native return.
     */
    static final class ExportSession {
        private final String correlation;
        private final VerifiedMemberResolver resolver;
        private final String sessionIdentity;
        private final Object source;
        private final Object model;
        private final String sourceIdentity;
        private final String documentIdentity;
        private final String modelIdentity;
        private final String currentGuardBinding;
        private final RawImageId oldRaw;
        private final AtomicBoolean finished = new AtomicBoolean();

        private ExportSession(
            final String correlation,
            final VerifiedMemberResolver resolver,
            final String sessionIdentity,
            final Object source,
            final Object document,
            final Object model,
            final RawImageId oldRaw
        ) {
            this.correlation = Objects.requireNonNull(correlation, "correlation");
            this.resolver = resolver;
            this.sessionIdentity = sessionIdentity;
            this.source = source;
            this.model = model;
            this.sourceIdentity = identity(source);
            this.documentIdentity = identity(document);
            this.modelIdentity = identity(model);
            this.currentGuardBinding = safe(sessionIdentity) + "/" + modelIdentity;
            this.oldRaw = Objects.requireNonNull(oldRaw, "oldRaw");
        }

        private void writePre() {
            try {
                writeObservation(
                    "export-pre",
                    true,
                    NativeRelationObservation.capture(
                        resolver,
                        source,
                        model,
                        oldRaw,
                        RawIdentity.unavailable("not-applicable-export")
                    ),
                    null
                );
            } catch (RuntimeException | LinkageError ignored) {
                // A failed diagnostic sink or capture must not affect the export call. The direct
                // capture helper already converts reviewed native read failures to UNAVAILABLE.
            }
        }

        /** Finishes at most once; a false guard means no post host read is permitted. */
        void finish(final boolean currentGuardPassed, final String cause) {
            if (!finished.compareAndSet(false, true)) return;
            try {
                final NativeRelationObservation observation = currentGuardPassed
                    ? NativeRelationObservation.capture(
                        resolver,
                        source,
                        model,
                        oldRaw,
                        RawIdentity.unavailable("not-applicable-export")
                    )
                    : NativeRelationObservation.unavailable(
                        cause == null || cause.isBlank()
                            ? "post-current-guard-unavailable"
                            : cause
                    );
                writeObservation("export-post", currentGuardPassed, observation, cause);
            } catch (RuntimeException | LinkageError ignored) {
                // Never change export success/failure or mask its original exception.
            }
        }

        private void writeObservation(
            final String phase,
            final boolean currentGuardPassed,
            final NativeRelationObservation observation,
            final String cause
        ) {
            final BoundedLine line = new BoundedLine(MAX_LINE_LENGTH);
            line.add("correlation", correlation);
            line.add("phase", phase);
            line.add("nativeOperation", "PSD_EXPORT");
            line.add("sessionIdentity", sessionIdentity);
            line.add("sourceIdentity", sourceIdentity);
            line.add("documentIdentity", documentIdentity);
            line.add("modelIdentity", modelIdentity);
            line.add("currentGuardBinding", currentGuardBinding);
            line.add("currentGuardPreStatus", "PASSED");
            line.add("currentGuardPostStatus", "export-pre".equals(phase)
                ? "NOT_APPLICABLE"
                : currentGuardPassed ? "PASSED" : "UNAVAILABLE");
            line.add("rawBindingStatus", "MATCHED_BY_CALLER");
            line.add("oldRaw", oldRaw.value());
            line.add("observation", observation.status());
            line.add("observationCause", cause == null || cause.isBlank()
                ? observation.cause() : cause);
            appendNativeEvidence(line, observation);
            line.add(
                "nativeCompletionCallback",
                "UNAVAILABLE:CLayeredImage.save-returns-void"
            );
            line.add(
                "nativeReturnObservation",
                "NOT_REPORTED_EXPORT_HAS_NO_VERIFIED_RETURN_SIGNAL"
            );
            EditorObjectValidationTrace.writeArtifact(ARTIFACT, line.finish(), true);
        }
    }

    private static void appendNativeResult(
        final BoundedLine line,
        final EditorRawImagePsdReplaceAccess.ReplaceResult nativeResult
    ) {
        if (nativeResult == null) {
            line.add("nativeStatus", "THREW_OR_UNOBSERVED");
            line.add("nativeInvocationAttempted", "UNKNOWN");
            line.add("nativeReturned", "UNKNOWN");
            return;
        }
        line.add("nativeStatus", nativeResult.status().name());
        line.add("nativeInvocationAttempted", nativeResult.nativeInvocationAttempted());
        line.add("nativeReturned", nativeResult.nativeReturned());
        line.add("nativeFailurePhase", nativeResult.failurePhase().name());
        line.add("nativeFailureType", nativeResult.failureType());
        if (nativeResult.failureMessage() != null) {
            line.add("nativeFailureMessage", nativeResult.failureMessage());
        }
    }

    private static void appendNativeEvidence(
        final BoundedLine line,
        final NativeRelationObservation observation
    ) {
        line.add("nativeRelationObservation", observation.status());
        if (!observation.isAvailable()) {
            line.add("nativeRelationObservationCause", observation.cause());
            return;
        }
        line.add("nativeModelImageTotalCount", observation.modelImageTotalCount());
        line.add("nativeModelImageTruncated", observation.modelImageTruncated());
        line.add("nativeGroupTotalCount", observation.groupTotalCount());
        line.add("nativeGroupTruncated", observation.groupTruncated());
        // Keep the requested old/incoming wrappers ahead of bulk graph detail. A large
        // relation graph may be truncated, but it must not hide either binding endpoint.
        line.add("nativeRawWrapperTotalCount", observation.rawWrapperTotalCount());
        line.add("nativeRawWrapperTruncated", observation.rawWrapperTruncated());
        for (int index = 0; index < observation.rawWrappers().size(); index++) {
            final NativeRawWrapper value = observation.rawWrappers().get(index);
            final String prefix = "nativeRawWrapper." + index + ".";
            line.add(prefix + "id", value.id());
            line.add(prefix + "present", value.present());
            line.add(prefix + "replaced", value.replaced());
        }
        for (int index = 0; index < observation.modelImages().size(); index++) {
            final NativeModelImage value = observation.modelImages().get(index);
            final String prefix = "nativeModelImage." + index + ".";
            line.add(prefix + "id", value.id());
            line.add(prefix + "target", value.target());
            appendValue(line, prefix + "hasLayerInputData", value.hasLayerInputData());
            appendValueList(line, prefix + "selectorKeys", value.selectorKeys());
            appendValue(line, prefix + "currentImageGuid", value.currentImageGuid());
            appendValueList(line, prefix + "linkedRaw", value.linkedRaw());
            line.add(prefix + "linkedRawContainsOld", value.linkedRawContainsOld());
            line.add(prefix + "linkedRawContainsIncoming", value.linkedRawContainsIncoming());
            line.add(prefix + "selectorKeysContainOld", value.selectorKeys().containsOld());
            line.add(prefix + "selectorKeysContainIncoming", value.selectorKeys().containsIncoming());
        }
        for (int index = 0; index < observation.groups().size(); index++) {
            final NativeGroup value = observation.groups().get(index);
            final String prefix = "nativeGroup." + index + ".";
            line.add(prefix + "name", value.name());
            line.add(prefix + "target", value.target());
            appendValueList(line, prefix + "modelImageIds", value.modelImageIds());
            appendValueList(line, prefix + "linkedRaw", value.linkedRaw());
            line.add(prefix + "linkedRawContainsOld", value.linkedRaw().containsOld());
            line.add(prefix + "linkedRawContainsIncoming", value.linkedRaw().containsIncoming());
        }
    }

    private static void appendValue(
        final BoundedLine line,
        final String key,
        final Value value
    ) {
        line.add(key, value.available() ? value.value() : "UNAVAILABLE");
        if (!value.available()) line.add(key + "Cause", value.cause());
    }

    private static void appendValueList(
        final BoundedLine line,
        final String key,
        final ValueList value
    ) {
        line.add(key + "Status", value.available() ? "AVAILABLE" : "UNAVAILABLE");
        line.add(key + "Count", value.total());
        line.add(key + "Truncated", value.truncated());
        if (!value.available()) line.add(key + "Cause", value.cause());
        for (int index = 0; index < value.values().size(); index++) {
            line.add(key + "." + index, value.values().get(index));
        }
    }

    private static void writeFailure(
        final String phase,
        final String cause,
        final EditorRawImagePsdReplaceAccess.ReplaceResult nativeResult
    ) {
        // Session-specific failures are best-effort only. The normal path has
        // already recorded the native facts before this fallback is reached.
        // This method is intentionally not used to retry or classify a replace.
        try {
            final BoundedLine line = new BoundedLine(MAX_LINE_LENGTH);
            line.add("phase", phase);
            line.add("observation", "UNAVAILABLE");
            line.add("observationCause", cause);
            if (nativeResult != null) line.add("nativeStatus", nativeResult.status().name());
            EditorObjectValidationTrace.writeArtifact(ARTIFACT, line.finish(), true);
        } catch (RuntimeException | LinkageError ignored) {
            // A failed diagnostic sink must not become a replacement failure.
        }
    }

    private static void writeSetupFailure(
        final String correlation,
        final String sessionIdentity,
        final Object document,
        final Object model,
        final RawImageId oldRaw,
        final RawIdentity incomingRaw,
        final Throwable failure
    ) {
        try {
            final BoundedLine line = new BoundedLine(MAX_LINE_LENGTH);
            line.add("correlation", correlation);
            line.add("phase", "pre");
            line.add("sessionIdentity", sessionIdentity);
            line.add("documentIdentity", identity(document));
            line.add("modelIdentity", identity(model));
            line.add("oldRaw", oldRaw == null ? null : oldRaw.value());
            line.add("incomingRaw", incomingRaw == null ? null : incomingRaw.value());
            line.add("observation", "UNAVAILABLE");
            line.add("observationCause", "diagnostic-setup-failed:" + message(failure));
            line.add("nativeStatus", "NOT_STARTED");
            EditorObjectValidationTrace.writeArtifact(ARTIFACT, line.finish(), true);
        } catch (RuntimeException | LinkageError ignored) {
            // A failed diagnostic sink must not become a replacement failure.
        }
    }

    private static void writeExportSetupFailure(
        final String correlation,
        final String sessionIdentity,
        final Object source,
        final Object document,
        final Object model,
        final RawImageId oldRaw,
        final Throwable failure
    ) {
        try {
            final BoundedLine line = new BoundedLine(MAX_LINE_LENGTH);
            line.add("correlation", correlation);
            line.add("phase", "export-pre");
            line.add("nativeOperation", "PSD_EXPORT");
            line.add("sessionIdentity", sessionIdentity);
            line.add("sourceIdentity", identity(source));
            line.add("documentIdentity", identity(document));
            line.add("modelIdentity", identity(model));
            line.add("currentGuardPostStatus", "UNAVAILABLE");
            line.add("rawBindingStatus", "UNAVAILABLE");
            line.add("oldRaw", oldRaw == null ? null : oldRaw.value());
            line.add("observation", "UNAVAILABLE");
            line.add("observationCause", "diagnostic-export-setup-failed:" + message(failure));
            line.add("nativeCompletionCallback", "UNAVAILABLE:CLayeredImage.save-returns-void");
            line.add(
                "nativeReturnObservation",
                "NOT_REPORTED_EXPORT_HAS_NO_VERIFIED_RETURN_SIGNAL"
            );
            EditorObjectValidationTrace.writeArtifact(ARTIFACT, line.finish(), true);
        } catch (RuntimeException | LinkageError ignored) {
            // A failed diagnostic sink must not become an export failure.
        }
    }

    private record ObservationView(
        String status,
        String cause,
        String binding,
        String generation,
        String revision
    ) {
        static ObservationView available(
            final String binding,
            final String generation,
            final String revision
        ) {
            return new ObservationView("AVAILABLE", "", binding, generation, revision);
        }

        static ObservationView unavailable(final String cause) {
            return new ObservationView("UNAVAILABLE", cause, "", "", "");
        }
    }

    private record Value(boolean available, String value, String cause) {
        static Value available(final String value) {
            return new Value(true, value, "");
        }

        static Value unavailable(final String cause) {
            return new Value(false, "", cause);
        }
    }

    private record ValueList(
        boolean available,
        List<String> values,
        int total,
        boolean truncated,
        String cause,
        boolean containsOld,
        boolean containsIncoming
    ) {
        ValueList {
            values = List.copyOf(values);
        }

        static ValueList available(
            final List<String> values,
            final int total,
            final boolean truncated,
            final boolean containsOld,
            final boolean containsIncoming
        ) {
            return new ValueList(
                true, values, total, truncated, "", containsOld, containsIncoming
            );
        }

        static ValueList unavailable(final String cause) {
            return new ValueList(false, List.of(), 0, false, cause, false, false);
        }
    }

    private record NativeModelImage(
        String id,
        boolean target,
        Value hasLayerInputData,
        ValueList selectorKeys,
        Value currentImageGuid,
        ValueList linkedRaw,
        boolean linkedRawContainsOld,
        boolean linkedRawContainsIncoming
    ) { }

    private record NativeGroup(
        String name,
        boolean target,
        ValueList modelImageIds,
        ValueList linkedRaw
    ) { }

    private record NativeRawWrapper(String id, boolean present, String replaced) { }

    private record NativeRelationObservation(
        String status,
        String cause,
        int modelImageTotalCount,
        boolean modelImageTruncated,
        List<NativeModelImage> modelImages,
        int groupTotalCount,
        boolean groupTruncated,
        List<NativeGroup> groups,
        int rawWrapperTotalCount,
        boolean rawWrapperTruncated,
        List<NativeRawWrapper> rawWrappers
    ) {
        NativeRelationObservation {
            modelImages = List.copyOf(modelImages);
            groups = List.copyOf(groups);
            rawWrappers = List.copyOf(rawWrappers);
        }

        static NativeRelationObservation unavailable(final String cause) {
            return new NativeRelationObservation(
                "UNAVAILABLE", cause, 0, false, List.of(), 0, false, List.of(), 0, false, List.of()
            );
        }

        boolean isAvailable() {
            return "AVAILABLE".equals(status);
        }

        static NativeRelationObservation capture(
            final VerifiedMemberResolver resolver,
            final Object source,
            final Object model,
            final RawImageId oldRaw,
            final RawIdentity incomingRaw
        ) {
            try {
                if (resolver == null) return unavailable("resolver-unavailable");
                if (source == null) return unavailable("source-unavailable");
                if (model == null) return unavailable("model-unavailable");
                if (!resolver.isExactCubismVersion(SUPPORTED_VERSION)) {
                    return unavailable("unsupported-cubism-version");
                }
                if (!resolver.authorizesFeature(
                    EditorTextureRelationsSelectorContract.ADAPTER_SLICE_ID,
                    EditorTextureRelationsSelectorContract.CAPABILITY_ID,
                    EditorTextureRelationsSelectorContract.REQUIRED_ALIASES
                )) {
                    return unavailable("native-relation-aliases-unavailable");
                }

                final Object textureManager = requireObject(
                    resolver.invoke(TEXTURE_MANAGER, source), "texture-manager"
                );
                final List<?> allImages = list(
                    resolver.invoke(ALL_MODEL_IMAGES, textureManager), "model-images"
                );
                final List<NativeModelImage> modelValues = new ArrayList<>();
                for (final Object image : allImages) {
                    requireInstance(resolver, MODEL_IMAGE_CLASS, image, "model-image");
                    final String id = guidValue(
                        resolver, resolver.invoke(MODEL_IMAGE_GUID, image), "model-image"
                    );
                    final ValueList linkedRaw = readGuidList(
                        resolver,
                        resolver.invoke(MODEL_IMAGE_LINKED_RAW, image),
                        oldRaw,
                        incomingRaw,
                        "model-image-linked-raw"
                    );
                    final NativeFilter filter = readFilter(resolver, image, oldRaw, incomingRaw);
                    modelValues.add(new NativeModelImage(
                        id,
                        referencesTarget(linkedRaw, filter.currentImageGuid(), oldRaw, incomingRaw),
                        filter.hasLayerInputData(),
                        filter.selectorKeys(),
                        filter.currentImageGuid(),
                        linkedRaw,
                        linkedRaw.containsOld(),
                        linkedRaw.containsIncoming()
                    ));
                }
                modelValues.sort((left, right) -> Boolean.compare(right.target(), left.target()));
                final boolean modelTruncated = modelValues.size() > MAX_ITEMS;
                final List<NativeModelImage> boundedModelValues = modelValues.size() > MAX_ITEMS
                    ? List.copyOf(modelValues.subList(0, MAX_ITEMS))
                    : List.copyOf(modelValues);
                final Set<String> nativeTargetIds = new HashSet<>();
                for (final NativeModelImage value : modelValues) {
                    if (value.target()) nativeTargetIds.add(value.id());
                }

                final List<?> rawGroups = list(
                    resolver.invoke(MODEL_IMAGE_GROUPS, textureManager), "model-image-groups"
                );
                final List<NativeGroup> groupValues = new ArrayList<>();
                for (final Object group : rawGroups) {
                    requireInstance(resolver, GROUP_CLASS, group, "model-image-group");
                    final String name = stringValue(
                        resolver.invoke(GROUP_NAME, group), "model-image-group-name"
                    );
                    final ValueList ids = readModelImageIds(resolver, resolver.invoke(GROUP_IMAGES, group));
                    final ValueList linkedRaw = readGuidList(
                        resolver,
                        resolver.invoke(GROUP_LINKED_RAW, group),
                        oldRaw,
                        incomingRaw,
                        "model-image-group-linked-raw"
                    );
                    final boolean target = intersects(nativeTargetIds, ids.values())
                        || linkedRaw.containsOld()
                        || linkedRaw.containsIncoming();
                    groupValues.add(new NativeGroup(name, target, ids, linkedRaw));
                }
                groupValues.sort((left, right) -> Boolean.compare(
                    right.target(), left.target()
                ));
                final boolean groupTruncated = groupValues.size() > MAX_ITEMS;
                final List<NativeGroup> boundedGroupValues = groupValues.size() > MAX_ITEMS
                    ? List.copyOf(groupValues.subList(0, MAX_ITEMS))
                    : List.copyOf(groupValues);

                final List<?> wrappers = list(
                    resolver.invoke(RAW_IMAGES, textureManager), "raw-image-wrappers"
                );
                final List<NativeRawWrapper> rawValues = new ArrayList<>();
                boolean rawTruncated = false;
                final Set<String> requested = new LinkedHashSet<>();
                if (oldRaw != null) requested.add(oldRaw.value());
                if (incomingRaw.isAvailable()) requested.add(incomingRaw.value());
                final Set<String> found = new HashSet<>();
                int rawTotal = 0;
                for (final Object wrapper : wrappers) {
                    rawTotal++;
                    final Object image = requireObject(
                        resolver.invoke(WRAPPER_IMAGE, wrapper), "raw-layered-image"
                    );
                    requireInstance(resolver, LAYERED_IMAGE_CLASS, image, "raw-layered-image");
                    final String id = guidValue(
                        resolver, resolver.invoke(LAYERED_IMAGE_GUID, image), "raw-layered-image"
                    );
                    if (!requested.contains(id)) continue;
                    if (!found.add(id)) {
                        throw EditorTextureReplacementDiagnostic.unavailable("duplicate-raw-image-id");
                    }
                    if (rawValues.size() >= MAX_ITEMS) {
                        rawTruncated = true;
                        continue;
                    }
                    final Object replaced = resolver.invoke(WRAPPER_REPLACED, wrapper);
                    if (!(replaced instanceof Boolean value)) {
                        throw EditorTextureReplacementDiagnostic.unavailable(
                            "raw-image-replaced-is-not-boolean"
                        );
                    }
                    rawValues.add(new NativeRawWrapper(id, true, Boolean.toString(value)));
                }
                for (final String requestedId : requested) {
                    if (found.contains(requestedId)) continue;
                    if (rawValues.size() >= MAX_ITEMS) {
                        rawTruncated = true;
                        continue;
                    }
                    rawValues.add(new NativeRawWrapper(requestedId, false, "UNAVAILABLE"));
                }
                return new NativeRelationObservation(
                    "AVAILABLE",
                    "",
                    allImages.size(),
                    modelTruncated,
                    boundedModelValues,
                    rawGroups.size(),
                    groupTruncated,
                    boundedGroupValues,
                    rawTotal,
                    rawTruncated,
                    rawValues
                );
            } catch (RuntimeException | LinkageError failure) {
                return unavailable("native-relation-observation-failed:" + message(failure));
            }
        }

        private static boolean referencesTarget(
            final ValueList linkedRaw,
            final Value currentImageGuid,
            final RawImageId oldRaw,
            final RawIdentity incomingRaw
        ) {
            if (linkedRaw.containsOld() || linkedRaw.containsIncoming()) return true;
            if (!currentImageGuid.available()) return false;
            return oldRaw != null && oldRaw.value().equals(currentImageGuid.value())
                || incomingRaw.isAvailable()
                    && incomingRaw.value().equals(currentImageGuid.value());
        }

        private static NativeFilter readFilter(
            final VerifiedMemberResolver resolver,
            final Object image,
            final RawImageId oldRaw,
            final RawIdentity incomingRaw
        ) {
            final Object filterEnv = resolver.invoke(MODEL_IMAGE_INPUT_FILTER_ENV, image);
            if (filterEnv == null) {
                return new NativeFilter(
                    Value.unavailable("filter-env-null"),
                    ValueList.unavailable("filter-env-null"),
                    Value.unavailable("filter-env-null")
                );
            }
            requireInstance(resolver, FILTER_ENV_CLASS, filterEnv, "model-image-filter-env");
            final Object hasValue = resolver.invoke(FILTER_ENV_HAS_LAYER_INPUT, filterEnv);
            final Value hasLayerInputData = hasValue instanceof Boolean value
                ? Value.available(Boolean.toString(value))
                : Value.unavailable("has-layer-input-data-is-not-boolean");

            final ValueList selectorKeys;
            final Object selectorMap = resolver.invoke(FILTER_ENV_LAYER_INPUT, filterEnv);
            if (selectorMap == null) {
                selectorKeys = ValueList.unavailable("selector-map-null");
            } else if (!resolver.isInstance(SELECTOR_MAP_CLASS, selectorMap)) {
                selectorKeys = ValueList.unavailable("selector-map-invalid");
            } else {
                final Object rawMap = resolver.invoke(SELECTOR_MAP_IMAGE_INPUTS, selectorMap);
                if (!(rawMap instanceof Map<?, ?> map)) {
                    selectorKeys = ValueList.unavailable("selector-map-values-not-map");
                } else {
                    selectorKeys = readGuidList(resolver, map.keySet(), oldRaw, incomingRaw, "selector-key");
                }
            }

            final Object hasCurrentValue = resolver.invoke(FILTER_ENV_HAS_CURRENT, filterEnv);
            final Value currentImageGuid;
            if (!(hasCurrentValue instanceof Boolean hasCurrent)) {
                currentImageGuid = Value.unavailable("has-current-image-guid-is-not-boolean");
            } else if (!hasCurrent) {
                currentImageGuid = Value.available("NONE");
            } else {
                final Object guid = resolver.invoke(FILTER_ENV_CURRENT, filterEnv);
                currentImageGuid = guid == null
                    ? Value.unavailable("current-image-guid-null")
                    : Value.available(guidValue(resolver, guid, "current-image-guid"));
            }
            return new NativeFilter(hasLayerInputData, selectorKeys, currentImageGuid);
        }

        private static ValueList readModelImageIds(
            final VerifiedMemberResolver resolver,
            final Object value
        ) {
            final List<?> values = list(value, "group-model-images");
            final List<String> ids = new ArrayList<>();
            for (final Object image : values) {
                requireInstance(resolver, MODEL_IMAGE_CLASS, image, "group-model-image");
                final String id = guidValue(
                    resolver, resolver.invoke(MODEL_IMAGE_GUID, image), "group-model-image"
                );
                if (ids.size() < MAX_ITEMS) ids.add(id);
            }
            return ValueList.available(ids, values.size(), values.size() > MAX_ITEMS, false, false);
        }

        private static ValueList readGuidList(
            final VerifiedMemberResolver resolver,
            final Object value,
            final RawImageId oldRaw,
            final RawIdentity incomingRaw,
            final String label
        ) {
            final List<?> values = list(value, label);
            final List<String> ids = new ArrayList<>();
            boolean containsOld = false;
            boolean containsIncoming = false;
            for (final Object guid : values) {
                final String id = guidValue(resolver, guid, label);
                containsOld |= oldRaw != null && id.equals(oldRaw.value());
                containsIncoming |= incomingRaw.isAvailable() && id.equals(incomingRaw.value());
                if (ids.size() < MAX_ITEMS) ids.add(id);
            }
            return ValueList.available(ids, values.size(), values.size() > MAX_ITEMS, containsOld, containsIncoming);
        }

        private static boolean intersects(final Set<String> values, final List<String> candidates) {
            for (final String value : candidates) if (values.contains(value)) return true;
            return false;
        }

    }

    private record NativeFilter(Value hasLayerInputData, ValueList selectorKeys, Value currentImageGuid) { }

    private static void requireInstance(
        final VerifiedMemberResolver resolver,
        final String alias,
        final Object value,
        final String label
    ) {
        if (!resolver.isInstance(alias, value)) throw unavailable(label + " has invalid type");
    }

    private static Object requireObject(final Object value, final String label) {
        if (value == null) throw unavailable(label + " is unavailable");
        return value;
    }

    private static String guidValue(
        final VerifiedMemberResolver resolver,
        final Object guid,
        final String label
    ) {
        final Object value = requireObject(guid, label + " GUID");
        final Object text = resolver.invoke(GUID_VALUE, value);
        if (!(text instanceof String result) || result.isBlank()) {
            throw unavailable(label + " GUID is invalid");
        }
        return result;
    }

    private static String stringValue(final Object value, final String label) {
        if (!(value instanceof String text)) throw unavailable(label + " is invalid");
        return text;
    }

    private static List<?> list(final Object value, final String label) {
        if (value == null || !(value instanceof Iterable<?> iterable)) {
            throw unavailable(label + " collection is unavailable");
        }
        final List<Object> result = new ArrayList<>();
        iterable.forEach(result::add);
        return result;
    }

    private static IllegalStateException unavailable(final String message) {
        return new IllegalStateException(message);
    }

    private static String identity(final Object value) {
        return value == null
            ? "null"
            : value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    private static String safe(final String value) {
        if (value == null) return "null";
        final StringBuilder result = new StringBuilder(Math.min(value.length(), MAX_VALUE_LENGTH));
        for (int index = 0; index < value.length() && result.length() < MAX_VALUE_LENGTH; index++) {
            final char character = value.charAt(index);
            result.append(Character.isLetterOrDigit(character)
                || character == '-' || character == '_' || character == '.'
                || character == ':' || character == '@' || character == '/'
                ? character : '_');
        }
        return result.toString();
    }

    private static String message(final Throwable failure) {
        final String value = failure.getMessage();
        return value == null || value.isBlank() ? failure.getClass().getName() : value;
    }

    private static final class BoundedLine {
        private final int maxLength;
        private final StringBuilder line = new StringBuilder(1_024);
        private int skippedFields;

        BoundedLine(final int maxLength) {
            this.maxLength = maxLength;
        }

        void add(final String key, final Object value) {
            final String field = key + "=" + safe(value == null ? null : value.toString());
            final int separator = line.length() == 0 ? 0 : 1;
            if (line.length() + separator + field.length() + LINE_RESERVE > maxLength) {
                skippedFields++;
                return;
            }
            if (separator != 0) line.append(' ');
            line.append(field);
        }

        String finish() {
            final String marker = " diagnosticTruncated=" + (skippedFields > 0)
                + " diagnosticSkippedFields=" + skippedFields;
            if (line.length() + marker.length() + System.lineSeparator().length() <= maxLength) {
                line.append(marker);
            }
            line.append(System.lineSeparator());
            return line.toString();
        }
    }
}
