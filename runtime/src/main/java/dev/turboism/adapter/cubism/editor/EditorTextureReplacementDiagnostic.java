package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.model.ModelImageGroupRelation;
import dev.turboism.sdk.cubism.model.ModelImageRelation;
import dev.turboism.sdk.cubism.model.RawImageDetails;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in, bounded relation evidence around one PSD raw-image replacement call.
 *
 * <p>This is validation evidence only. It does not invoke native code, infer a
 * committed edit from the native void return, or change replacement
 * classification. The existing relation snapshots are reused; the only
 * additional host read in the enabled path is the incoming layered-image GUID
 * lookup. The default path returns before inspecting any diagnostic input.</p>
 *
 * <p>Capture requires {@link #ENABLE_PROPERTY}; persistence uses the existing
 * {@link EditorObjectValidationTrace} opt-in sink and its separate trace
 * property. No diagnostic output is emitted unless that sink is enabled too.</p>
 *
 * <p>{@code nativeReturned=true} means only that the verified five-argument
 * native method returned synchronously. It is not an asynchronous completion or
 * commit signal. Relation observations are labelled as unavailable when their
 * binding or required evidence cannot be established. The
 * {@code hasLayerInputData} value is the relation projection's non-empty
 * selector-input map; it is not a new host read or a fabricated mapping.</p>
 */
final class EditorTextureReplacementDiagnostic {

    static final String ENABLE_PROPERTY =
        "turboism.validation.editorTextureReplaceDiagnostic";
    static final String ARTIFACT = "editor-texture-psd-replace-diagnostic.txt";

    private static final int MAX_ITEMS = 32;
    private static final int MAX_VALUE_LENGTH = 128;
    static final int MAX_LINE_LENGTH = 32_000;
    private static final AtomicLong CORRELATION_SEQUENCE = new AtomicLong();

    private EditorTextureReplacementDiagnostic() {
    }

    /**
     * Executes the already-admitted native operation exactly once. The helper
     * deliberately does not catch or retry an exception from the caller.
     */
    static EditorRawImagePsdReplaceAccess.ReplaceResult invokeNativeOnce(
        final NativeReplace operation
    ) {
        return Objects.requireNonNull(operation, "operation").invoke();
    }

    @FunctionalInterface
    interface NativeReplace {
        EditorRawImagePsdReplaceAccess.ReplaceResult invoke();
    }

    /** Returns whether this replacement-specific diagnostic was explicitly enabled. */
    static boolean enabled() {
        try {
            return Boolean.getBoolean(ENABLE_PROPERTY);
        } catch (SecurityException ignored) {
            return false;
        }
    }

    /**
     * Resolves the parsed incoming raw identity through already reviewed aliases.
     * Callers must invoke this only after {@link #enabled()} is true.
     */
    static RawIdentity resolveIncomingRaw(
        final VerifiedMemberResolver resolver,
        final Object incoming
    ) {
        if (!enabled()) return RawIdentity.unavailable("diagnostic-disabled");
        Objects.requireNonNull(resolver, "resolver");
        if (incoming == null) return RawIdentity.unavailable("incoming-layered-image-null");
        try {
            final Object guid = resolver.invoke(
                "cubism.editor-model.layered-image.guid", incoming
            );
            if (guid == null) return RawIdentity.unavailable("incoming-guid-null");
            final Object value = resolver.invoke("cubism.editor-model.guid.value", guid);
            if (!(value instanceof String text) || text.isBlank()) {
                return RawIdentity.unavailable("incoming-guid-invalid");
            }
            return RawIdentity.available(text);
        } catch (RuntimeException | LinkageError failure) {
            return RawIdentity.unavailable(
                "incoming-guid-unavailable:" + message(failure)
            );
        }
    }

    /**
     * Starts one correlated observation. When disabled this returns before
     * validating or inspecting any supplied host value.
     */
    static Optional<Session> begin(
        final String sessionIdentity,
        final Object document,
        final Object model,
        final RawImageId oldRaw,
        final RawIdentity incomingRaw,
        final TextureRelationsSnapshot before
    ) {
        if (!enabled()) return Optional.empty();
        final String correlation = "texture-replace-"
            + CORRELATION_SEQUENCE.incrementAndGet();
        final Session session = new Session(
            correlation,
            sessionIdentity,
            document,
            model,
            oldRaw,
            incomingRaw,
            before
        );
        session.writePre();
        return Optional.of(session);
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
        private final String sessionIdentity;
        private final String documentIdentity;
        private final String modelIdentity;
        private final String currentGuardBinding;
        private final RawImageId oldRaw;
        private final RawIdentity incomingRaw;
        private final TextureRelationsSnapshot before;
        private final List<ModelImageId> affectedModelImages;
        private final AtomicBoolean finished = new AtomicBoolean();

        private Session(
            final String correlation,
            final String sessionIdentity,
            final Object document,
            final Object model,
            final RawImageId oldRaw,
            final RawIdentity incomingRaw,
            final TextureRelationsSnapshot before
        ) {
            this.correlation = Objects.requireNonNull(correlation, "correlation");
            this.sessionIdentity = sessionIdentity;
            this.documentIdentity = identity(document);
            this.modelIdentity = identity(model);
            this.currentGuardBinding = safe(sessionIdentity) + "/" + modelIdentity;
            this.oldRaw = oldRaw;
            this.incomingRaw = Objects.requireNonNull(incomingRaw, "incomingRaw");
            this.before = before;
            this.affectedModelImages = affectedModelImages(before, oldRaw);
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
            } catch (RuntimeException | LinkageError ignored) {
                // Evidence must not change the native result or host classification.
            }
        }

        private void writePre() {
            try {
                writeObservation("pre", before, null, null);
            } catch (RuntimeException | LinkageError ignored) {
                // Evidence must not change the native call path.
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
            final StringBuilder line = new StringBuilder(1_024);
            append(line, "correlation", correlation);
            append(line, "phase", phase);
            append(line, "sessionIdentity", sessionIdentity);
            append(line, "documentIdentity", documentIdentity);
            append(line, "modelIdentity", modelIdentity);
            append(line, "currentGuardBinding", currentGuardBinding);
            if (nativeResult == null) {
                append(line, "currentGuardPrePassed", "PASSED_BY_CALLER");
            } else {
                append(line, "currentGuardPrePassed", nativeResult.preCurrentGuardPassed());
                append(line, "currentGuardPostPassed", nativeResult.postCurrentGuardPassed());
            }
            append(line, "oldRaw", oldRaw == null ? null : oldRaw.value());
            append(line, "incomingRaw", incomingRaw.value());
            append(line, "incomingRawStatus", incomingRaw.isAvailable() ? "AVAILABLE" : "UNAVAILABLE");
            if (!incomingRaw.isAvailable()) append(line, "incomingRawCause", incomingRaw.cause());
            append(line, "observation", view.status());
            append(line, "observationCause", view.cause());
            append(line, "relationBinding", view.binding());
            append(line, "generation", view.generation());
            append(line, "revision", view.revision());
            append(line, "affectedModelImageIds", view.affectedModelImageIds());
            append(line, "modelImages", view.modelImages());
            append(line, "groups", view.groups());
            append(line, "rawWrappers", view.rawWrappers());
            append(line, "artPathExclusion", "UNAVAILABLE:reviewed-alias-not-admitted");
            append(line, "nativeCompletionCallback", "UNAVAILABLE:reviewed-alias-not-admitted");
            if (nativeResult == null) {
                append(line, "nativeStatus", "THREW_OR_UNOBSERVED");
                append(line, "nativeInvocationAttempted", "UNKNOWN");
                append(line, "nativeReturned", "UNKNOWN");
            } else {
                append(line, "nativeStatus", nativeResult.status().name());
                append(line, "nativeInvocationAttempted", nativeResult.nativeInvocationAttempted());
                append(line, "nativeReturned", nativeResult.nativeReturned());
                append(line, "nativeFailurePhase", nativeResult.failurePhase().name());
                append(line, "nativeFailureType", nativeResult.failureType());
                if (nativeResult.failureMessage() != null) {
                    append(line, "nativeFailureMessage", nativeResult.failureMessage());
                }
            }
            line.append(System.lineSeparator());
            final String bounded = line.length() <= MAX_LINE_LENGTH
                ? line.toString()
                : line.substring(0, MAX_LINE_LENGTH - System.lineSeparator().length())
                    + System.lineSeparator();
            EditorObjectValidationTrace.writeArtifact(ARTIFACT, bounded, true);
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
                return ObservationView.unavailable(unavailableCause, affectedModelImages);
            }
            if (snapshot == null) {
                return ObservationView.unavailable(
                    phase + "-relation-snapshot-missing",
                    affectedModelImages
                );
            }
            if (!snapshot.isAvailable()) {
                return ObservationView.unavailable(
                    phase + "-relation-snapshot-unavailable",
                    affectedModelImages
                );
            }
            if (sessionIdentity == null || !sessionIdentity.equals(snapshot.binding())) {
                return ObservationView.unavailable(
                    "relation-binding-mismatch", affectedModelImages
                );
            }
            if (phase.equals("post") && before != null && before.isAvailable()
                && snapshot.generation() != before.generation()) {
                return ObservationView.unavailable(
                    "relation-generation-mismatch", affectedModelImages
                );
            }
            if (phase.equals("post") && before != null && before.isAvailable()
                && snapshot.revision() <= before.revision()) {
                return ObservationView.unavailable(
                    "relation-revision-not-newer", affectedModelImages
                );
            }
            if (oldRaw == null) {
                return ObservationView.unavailable("old-raw-missing", affectedModelImages);
            }
            if (!containsRaw(snapshot, oldRaw.value())) {
                return ObservationView.unavailable(
                    phase + "-old-raw-not-present", affectedModelImages
                );
            }
            if (!incomingRaw.isAvailable()) {
                return ObservationView.unavailable(
                    incomingRaw.cause(), affectedModelImages
                );
            }
            if (documentIdentity.equals("null")) {
                return ObservationView.unavailable("document-missing", affectedModelImages);
            }
            if (modelIdentity.equals("null")) {
                return ObservationView.unavailable("model-missing", affectedModelImages);
            }
            if (phase.equals("post") && !containsRaw(snapshot, incomingRaw.value())) {
                return ObservationView.unavailable(
                    "post-incoming-raw-not-present", affectedModelImages
                );
            }
            if (phase.equals("post")) {
                for (final ModelImageId id : affectedModelImages) {
                    if (snapshot.modelImage(id).isEmpty()) {
                        return ObservationView.unavailable(
                            "post-affected-model-image-missing:" + id.value(),
                            affectedModelImages
                        );
                    }
                }
            }
            return ObservationView.available(
                snapshot.binding(),
                Long.toString(snapshot.generation()),
                Long.toString(snapshot.revision()),
                affectedModelImages,
                modelImageSummary(snapshot),
                groupSummary(snapshot),
                rawWrapperSummary(snapshot)
            );
        }

        private boolean containsRaw(
            final TextureRelationsSnapshot snapshot,
            final String rawId
        ) {
            return snapshot.rawImages().stream().anyMatch(raw -> raw.id().value().equals(rawId));
        }

        private String modelImageSummary(final TextureRelationsSnapshot snapshot) {
            final StringBuilder result = new StringBuilder();
            int count = 0;
            for (final ModelImageRelation relation : snapshot.modelImages()) {
                if (!affectedModelImages.contains(relation.id())) continue;
                if (count++ >= MAX_ITEMS) break;
                if (result.length() > 0) result.append(';');
                result.append("id:").append(safe(relation.id().value()));
                result.append(",hasLayerInputData:")
                    .append(!relation.inputsByRawImage().isEmpty());
                result.append(",currentImageGuid:")
                    .append(relation.currentRawImageId().map(value -> safe(value.value())).orElse("NONE"));
                result.append(",selectorKeys:")
                    .append(rawIds(relation.inputsByRawImage().keySet().stream().map(RawImageId::value).toList()));
                result.append(",containsOldRaw:")
                    .append(relation.inputsByRawImage().containsKey(oldRaw));
                result.append(",linkedRaw:")
                    .append(rawIds(relation.linkedRawImageIds().stream().map(RawImageId::value).toList()));
            }
            return result.toString();
        }

        private String groupSummary(final TextureRelationsSnapshot snapshot) {
            final Set<String> affectedIds = new HashSet<>();
            for (final ModelImageId id : affectedModelImages) affectedIds.add(id.value());
            final String incoming = incomingRaw.value();
            final StringBuilder result = new StringBuilder();
            int count = 0;
            for (final ModelImageGroupRelation relation : snapshot.groups()) {
                final boolean relevant = relation.modelImageIds().stream()
                    .map(ModelImageId::value)
                    .anyMatch(affectedIds::contains)
                    || relation.linkedRawImageIds().stream()
                        .map(RawImageId::value)
                        .anyMatch(value -> value.equals(oldRaw.value())
                            || (incoming != null && value.equals(incoming)));
                if (!relevant) continue;
                if (count++ >= MAX_ITEMS) break;
                if (result.length() > 0) result.append(';');
                result.append("name:").append(safe(relation.groupName()));
                result.append(",modelImageIds:")
                    .append(rawIds(relation.modelImageIds().stream().map(ModelImageId::value).toList()));
                result.append(",linkedRaw:")
                    .append(rawIds(relation.linkedRawImageIds().stream().map(RawImageId::value).toList()));
            }
            return result.toString();
        }

        private String rawWrapperSummary(final TextureRelationsSnapshot snapshot) {
            final StringBuilder result = new StringBuilder();
            appendRawWrapper(result, snapshot, oldRaw == null ? null : oldRaw.value());
            if (incomingRaw.value() != null && !incomingRaw.value().equals(oldRaw == null ? null : oldRaw.value())) {
                if (result.length() > 0) result.append(';');
                appendRawWrapper(result, snapshot, incomingRaw.value());
            }
            return result.toString();
        }

        private void appendRawWrapper(
            final StringBuilder result,
            final TextureRelationsSnapshot snapshot,
            final String id
        ) {
            if (id == null) {
                result.append("id:null,present:false,replaced:UNAVAILABLE");
                return;
            }
            result.append("id:").append(safe(id));
            final Optional<RawImageDetails> raw = snapshot.rawImage(new RawImageId(id));
            result.append(",present:").append(raw.isPresent());
            result.append(",replaced:")
                .append(raw.map(value -> Boolean.toString(value.isReplaced())).orElse("UNAVAILABLE"));
        }
    }

    private record ObservationView(
        String status,
        String cause,
        String binding,
        String generation,
        String revision,
        String affectedModelImageIds,
        String modelImages,
        String groups,
        String rawWrappers
    ) {
        static ObservationView available(
            final String binding,
            final String generation,
            final String revision,
            final List<ModelImageId> affected,
            final String modelImages,
            final String groups,
            final String rawWrappers
        ) {
            return new ObservationView(
                "AVAILABLE",
                "",
                binding,
                generation,
                revision,
                modelImageIds(affected),
                modelImages,
                groups,
                rawWrappers
            );
        }

        static ObservationView unavailable(
            final String cause,
            final List<ModelImageId> affected
        ) {
            return new ObservationView(
                "UNAVAILABLE",
                cause,
                "",
                "",
                "",
                modelImageIds(affected),
                "",
                "",
                ""
            );
        }
    }

    private static List<ModelImageId> affectedModelImages(
        final TextureRelationsSnapshot snapshot,
        final RawImageId oldRaw
    ) {
        if (snapshot == null || !snapshot.isAvailable() || oldRaw == null) return List.of();
        final List<ModelImageId> result = new ArrayList<>();
        for (final ModelImageRelation relation : snapshot.modelImages()) {
            if (relation.currentRawImageId().filter(oldRaw::equals).isPresent()) {
                if (result.size() == MAX_ITEMS) break;
                result.add(relation.id());
            }
        }
        return List.copyOf(result);
    }

    private static String modelImageIds(final List<ModelImageId> values) {
        final List<String> ids = new ArrayList<>();
        for (final ModelImageId value : values) ids.add(value.value());
        return rawIds(ids);
    }

    private static String rawIds(final List<String> values) {
        final StringBuilder result = new StringBuilder();
        int count = 0;
        for (final String value : values) {
            if (count++ >= MAX_ITEMS) break;
            if (result.length() > 0) result.append(',');
            result.append(safe(value));
        }
        return result.toString();
    }

    private static void append(
        final StringBuilder line,
        final String key,
        final Object value
    ) {
        if (line.length() > 0) line.append(' ');
        line.append(key).append('=').append(safe(value == null ? null : value.toString()));
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
}
