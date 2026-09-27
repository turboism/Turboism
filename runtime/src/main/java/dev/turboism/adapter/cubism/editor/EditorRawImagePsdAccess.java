package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.VerifiedAccessException;
import dev.turboism.core.runtime.psd.PsdExportHost;
import dev.turboism.core.runtime.psd.PsdReplaceHost;
import dev.turboism.mapping.verification.selector.EditorRawImagePsdSelectorContract;
import dev.turboism.sdk.cubism.id.RawImageId;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;

/**
 * Package-private T003/T011 seam for PSD raw-image targeting and native export validation.
 *
 * <p>The ID-based overload resolves the selected raw image from the current model-source texture
 * manager before entering the native save/parse sequence. The low-level bound-source overload is
 * retained only as an internal synthetic/native seam; its type check is not an ownership proof and
 * production binding remains an explicit caller precondition for that primitive. The native save
 * and parse calls are kept inside one synchronous {@link EditorHostThread} boundary. This slice does
 * not replace a raw image, open an editor, create an Undo entry, or expose a host object or
 * {@link Path} through the SDK. Final-path
 * {@link LinkOption#NOFOLLOW_LINKS} checks happen immediately before and after save; they do not
 * eliminate parent-path or general TOCTOU races, and this class is not a security registry.</p>
 */
final class EditorRawImagePsdAccess {
    private final VerifiedMemberResolver resolver;
    private final EditorObjectReadAccess.CurrentGuard currentGuard;
    private final EditorRawImagePsdIntegrityAccess integrityAccess;
    private final EditorRawImagePsdSourceBinding sourceBinding;

    EditorRawImagePsdAccess(
        final VerifiedMemberResolver resolver,
        final EditorObjectReadAccess.CurrentGuard currentGuard
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.currentGuard = Objects.requireNonNull(currentGuard, "currentGuard");
        this.integrityAccess = new EditorRawImagePsdIntegrityAccess(resolver);
        this.sourceBinding = new EditorRawImagePsdSourceBinding(resolver, integrityAccess);
    }

    /**
     * Low-level internal native export primitive for synthetic invocation coverage.
     *
     * <p>This method accepts an explicitly bound native value only for the isolated native seam;
     * its type check is not an ownership proof. Production ID-based export uses the overload below
     * and never accepts a caller-supplied native object.</p>
     */
    ExportResult exportBoundPsd(
        final String identity,
        final Object model,
        final Object boundNativeSource,
        final Path target
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(boundNativeSource, "boundNativeSource");
        Objects.requireNonNull(target, "target");

        if (!resolver.isExactCubismVersion(EditorRawImagePsdSelectorContract.SUPPORTED_CUBISM_VERSION)) {
            return ExportResult.unavailable(
                target,
                "unsupported Cubism version: " + resolver.cubismVersion()
            );
        }
        if (!resolver.authorizesFeature(
            EditorRawImagePsdSelectorContract.ADAPTER_SLICE_ID,
            EditorRawImagePsdSelectorContract.CAPABILITY_ID,
            EditorRawImagePsdSelectorContract.REQUIRED_ALIASES
        )) {
            return ExportResult.unavailable(
                target,
                "PSD raw-image export lacks the complete exact selector authorization"
            );
        }

        return EditorHostThread.dispatch(
            "Cubism PSD raw-image export",
            () -> exportBoundOnHostThread(identity, model, boundNativeSource, target)
        );
    }

    /**
     * Resolves the current model-source raw image by exact {@link RawImageId}, saves it, and
     * reparses the output inside one synchronous host-thread/current-model boundary.
     */
    ExportResult exportPsd(
        final String identity,
        final Object modelSource,
        final Object model,
        final RawImageId sourceId,
        final Path target
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(modelSource, "modelSource");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(target, "target");

        if (!resolver.isExactCubismVersion(EditorRawImagePsdSelectorContract.SUPPORTED_CUBISM_VERSION)) {
            return ExportResult.unavailable(
                target,
                "unsupported Cubism version: " + resolver.cubismVersion()
            );
        }
        if (!resolver.authorizesFeature(
            EditorRawImagePsdSelectorContract.ADAPTER_SLICE_ID,
            EditorRawImagePsdSelectorContract.CAPABILITY_ID,
            EditorRawImagePsdSelectorContract.REQUIRED_ALIASES
        )) {
            return ExportResult.unavailable(
                target,
                "PSD raw-image export lacks the complete exact selector authorization"
            );
        }

        return EditorHostThread.dispatch(
            "Cubism PSD raw-image export",
            () -> exportOnHostThread(identity, modelSource, model, sourceId, target)
        );
    }

    /**
     * Parses one runtime-owned staged PSD into a verified native layered image for replacement.
     *
     * <p>Host thread only. The reconstructed value is type-checked against the same verified
     * constructors the export re-read uses; a parse or construct failure is reported as an
     * {@link IOException} so the caller can classify it without inspecting native text.</p>
     *
     * @param stage runtime-owned non-empty staged PSD
     * @param name runtime-issued edit file basename, matching official file-open naming semantics;
     *             explicit document/raw identity selects the target independently of this name
     */
    Object parseStageOnHostThread(final Path stage, final String name) throws IOException {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(name, "name");
        FailurePhase phase = FailurePhase.PARSE;
        try {
            final Object companion = requireNativeValue(
                "cubism.editor-model.psd-document.companion",
                resolver.readStaticField("cubism.editor-model.psd-document.companion")
            );
            final Object parsed = resolver.invoke(
                "cubism.editor-model.psd-document.parse-file",
                companion,
                stage.toFile(),
                false,
                false
            );
            if (!resolver.isInstance(
                EditorRawImagePsdSelectorContract.PSD_DOCUMENT_CLASS_ALIAS, parsed)) {
                throw new IOException("staged PSD parser returned a value outside the verified type");
            }
            phase = FailurePhase.CONSTRUCT;
            final Object reconstructed = resolver.construct(
                "cubism.editor-model.layered-image.from-psd",
                parsed,
                stage.toFile(),
                name
            );
            if (!resolver.isInstance(
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_CLASS_ALIAS, reconstructed)) {
                throw new IOException("staged PSD did not reconstruct as a verified layered image");
            }
            return reconstructed;
        } catch (IOException | RuntimeException failure) {
            throw new StageReadFailure(phase, failure);
        }
    }

    static PsdReplaceHost.Replacement unreadableStage(final Throwable failure) {
        final Optional<PsdReplaceHost.Failure> detail = failure instanceof StageReadFailure stage
            ? Optional.of(stage.detail) : Optional.empty();
        return new PsdReplaceHost.Replacement("STAGE_UNREADABLE", true, false, false, false, false,
            Optional.empty(), "The staged PSD could not be parsed into a verified native layered image.", detail);
    }

    private static final class StageReadFailure extends IOException {
        private final PsdReplaceHost.Failure detail;

        private StageReadFailure(final FailurePhase phase, final Throwable failure) {
            super("staged PSD could not be parsed into a verified layered image");
            final String category = failure instanceof VerifiedAccessException verified
                ? verified.hostFailureCategory().name() : "UNKNOWN";
            detail = new PsdReplaceHost.Failure(phase.name(), category);
        }
    }

    private ExportResult exportOnHostThread(
        final String identity,
        final Object modelSource,
        final Object model,
        final RawImageId sourceId,
        final Path target
    ) {
        currentGuard.requireCurrent(identity, model);
        final EditorRawImagePsdSourceBinding.BindingResult binding =
            sourceBinding.bindOnHostThread(modelSource, sourceId);
        if (binding.status() != EditorRawImagePsdSourceBinding.BindingStatus.MATCHED) {
            return ExportResult.bindingRejected(target, binding);
        }
        final ExportResult result = exportNativeOnHostThread(
            binding.candidate().nativeSource(),
            target,
            binding.snapshot()
        );
        if (result.saveReturned()) {
            currentGuard.requireCurrent(identity, model);
        }
        return result;
    }

    private ExportResult exportBoundOnHostThread(
        final String identity,
        final Object model,
        final Object boundNativeSource,
        final Path target
    ) {
        currentGuard.requireCurrent(identity, model);
        final ExportResult result = exportNativeOnHostThread(boundNativeSource, target);
        if (result.status() == ExportStatus.READABLE_UNVERIFIED) {
            currentGuard.requireCurrent(identity, model);
        }
        return result;
    }

    private ExportResult exportNativeOnHostThread(
        final Object boundNativeSource,
        final Path target
    ) {
        return exportNativeOnHostThread(boundNativeSource, target, null);
    }

    private ExportResult exportNativeOnHostThread(
        final Object boundNativeSource,
        final Path target,
        final EditorRawImagePsdIntegrityAccess.Snapshot beforeSnapshot
    ) {

        final File targetFile = target.toFile();
        boolean saveReturned = false;
        TargetPathSafety pathSafety = TargetPathSafety.NOT_CHECKED;
        FailurePhase phase = FailurePhase.TARGET_PRECHECK;
        try {
            final TargetState before = inspectTarget(target);
            pathSafety = TargetPathSafety.FINAL_PATH_NOFOLLOW_PRE_ONLY;
            if (before != TargetState.ABSENT && before != TargetState.EMPTY_REGULAR) {
                return ExportResult.targetRejected(target, false, pathSafety, phase, before);
            }

            phase = FailurePhase.SOURCE_IDENTITY;
            if (!resolver.isInstance(
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_CLASS_ALIAS,
                boundNativeSource
            )) {
                return ExportResult.invalidSource(target, pathSafety);
            }
            phase = FailurePhase.PROGRESS;
            final Object progress = requireNativeValue(
                "cubism.editor-model.psd-progress.default",
                resolver.invokeStatic("cubism.editor-model.psd-progress.default")
            );

            phase = FailurePhase.SOURCE_NAME;
            final Object sourceNameValue = resolver.invoke(
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_NAME_ALIAS,
                boundNativeSource
            );
            if (!(sourceNameValue instanceof String sourceName)) {
                throw new IllegalStateException("verified layered-image name is not a String");
            }

            phase = FailurePhase.SAVE;
            resolver.invoke(
                "cubism.editor-model.layered-image.save-psd",
                boundNativeSource,
                targetFile,
                progress
            );
            saveReturned = true;

            phase = FailurePhase.TARGET_POSTCHECK;
            final TargetState after = inspectTarget(target);
            pathSafety = TargetPathSafety.FINAL_PATH_NOFOLLOW_PRE_AND_POST;
            if (after == TargetState.SYMBOLIC_LINK || after == TargetState.OTHER) {
                return ExportResult.targetRejected(target, saveReturned, pathSafety, phase, after);
            }
            if (after != TargetState.NON_EMPTY_REGULAR) {
                return ExportResult.outputMissing(target, saveReturned, pathSafety);
            }

            phase = FailurePhase.PARSE;
            final Object companion = requireNativeValue(
                "cubism.editor-model.psd-document.companion",
                resolver.readStaticField("cubism.editor-model.psd-document.companion")
            );
            final Object parsed = resolver.invoke(
                "cubism.editor-model.psd-document.parse-file",
                companion,
                targetFile,
                false,
                false
            );
            if (!resolver.isInstance(EditorRawImagePsdSelectorContract.PSD_DOCUMENT_CLASS_ALIAS, parsed)) {
                return ExportResult.parseFailed(
                    target,
                    saveReturned,
                    pathSafety,
                    FailurePhase.PARSE,
                    "PSD parser returned a value outside the verified CPsdDocument type"
                );
            }

            Object reconstructed = null;
            try {
                phase = FailurePhase.CONSTRUCT;
                reconstructed = resolver.construct(
                    "cubism.editor-model.layered-image.from-psd",
                    parsed,
                    targetFile,
                    sourceName
                );
                if (!resolver.isInstance(
                    EditorRawImagePsdSelectorContract.LAYERED_IMAGE_CLASS_ALIAS,
                    reconstructed
                )) {
                    return ExportResult.parseFailed(
                        target,
                        saveReturned,
                        pathSafety,
                        FailurePhase.CONSTRUCT,
                        "parsed PSD did not reconstruct as the verified CLayeredImage type"
                    );
                }
                EditorRawImagePsdIntegrityAccess.Verification integrityVerification;
                if (beforeSnapshot == null) {
                    integrityVerification = EditorRawImagePsdIntegrityAccess.Verification.unavailable(
                        "source binding snapshot was not supplied; export fidelity was not compared"
                    );
                } else {
                    try {
                        final EditorRawImagePsdIntegrityAccess.Snapshot afterSnapshot =
                            integrityAccess.captureOnHostThread(reconstructed);
                        integrityVerification = integrityAccess.verify(beforeSnapshot, afterSnapshot);
                    } catch (RuntimeException failure) {
                        integrityVerification = EditorRawImagePsdIntegrityAccess.Verification.unavailable(
                            "reparsed PSD was readable but export fidelity could not be observed: "
                                + message(failure)
                        );
                    }
                }
                return ExportResult.readableUnverified(target, pathSafety, integrityVerification)
                    .withSourceName(sourceName);
            } finally {
                // Both objects belong only to this export verification, never the model or Undo.
                try {
                    try {
                        if (resolver.isInstance(EditorRawImagePsdSelectorContract.LAYERED_IMAGE_CLASS_ALIAS, reconstructed)) {
                            resolver.invoke(EditorRawImagePsdSelectorContract.LAYERED_IMAGE_DISPOSE_OWNED_ALIAS, reconstructed);
                        }
                    } finally {
                        disposeOwnedParsedImages(parsed);
                    }
                } catch (RuntimeException cleanupFailure) {
                    phase = FailurePhase.DISPOSE;
                    throw cleanupFailure;
                }
            }
        } catch (IOException exception) {
            return ExportResult.targetCheckFailed(target, saveReturned, pathSafety, phase, exception);
        } catch (RuntimeException exception) {
            return ExportResult.failure(target, saveReturned, pathSafety, phase, exception);
        }
    }

    /** Only the successful parser result created locally by export verification may enter here. */
    private void disposeOwnedParsedImages(final Object parsed) {
        final Object value = resolver.invoke(EditorRawImagePsdSelectorContract.PSD_DOCUMENT_LAYERS_OWNED_ALIAS, parsed);
        if (!(value instanceof Object[] layers)) {
            throw new IllegalStateException("verified PSD layer records are not an array");
        }
        final IdentityHashMap<Object, Boolean> images = new IdentityHashMap<>();
        for (final Object layer : layers) {
            final Object image = resolver.invoke(EditorRawImagePsdSelectorContract.PSD_LAYER_IMAGE_OWNED_ALIAS, layer);
            // Group records have no image; a shared image wrapper must be disposed once.
            if (image != null) images.put(image, Boolean.TRUE);
        }
        RuntimeException failure = null;
        for (final Object image : images.keySet()) {
            try {
                resolver.invoke(EditorRawImagePsdSelectorContract.PSD_IMAGE_DISPOSE_OWNED_ALIAS, image);
            } catch (RuntimeException cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else failure.addSuppressed(cleanupFailure);
            }
        }
        if (failure != null) throw failure;
    }

    private static TargetState inspectTarget(final Path target) throws IOException {
        try {
            final BasicFileAttributes attributes = Files.readAttributes(
                target,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
            );
            if (attributes.isSymbolicLink()) {
                return TargetState.SYMBOLIC_LINK;
            }
            if (!attributes.isRegularFile()) {
                return TargetState.OTHER;
            }
            return attributes.size() == 0
                ? TargetState.EMPTY_REGULAR
                : TargetState.NON_EMPTY_REGULAR;
        } catch (NoSuchFileException exception) {
            return TargetState.ABSENT;
        }
    }

    private enum TargetState {
        ABSENT,
        EMPTY_REGULAR,
        NON_EMPTY_REGULAR,
        SYMBOLIC_LINK,
        OTHER
    }

    private static Object requireNativeValue(final String alias, final Object value) {
        return Objects.requireNonNull(value, "verified native value is null for " + alias);
    }

    private static String message(final Throwable failure) {
        return failure.getMessage() == null ? "" : failure.getMessage();
    }

    /**
     * Selects exactly one raw image by its host-issued RawImageId.
     *
     * <p>Name, path, and list order are never used as identity. A duplicate ID is an explicit
     * ambiguity so a caller cannot accidentally export or replace the wrong native object.</p>
     */
    static <T> TargetSelection<T> selectByRawImageId(
        final Iterable<RawImageCandidate<T>> candidates,
        final RawImageId targetId
    ) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(targetId, "targetId");

        RawImageCandidate<T> match = null;
        for (final RawImageCandidate<T> candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate");
            if (!targetId.equals(candidate.id())) {
                continue;
            }
            if (match != null) {
                return new TargetSelection<>(SelectionStatus.DUPLICATE, null);
            }
            match = candidate;
        }
        return match == null
            ? new TargetSelection<>(SelectionStatus.NOT_FOUND, null)
            : new TargetSelection<>(SelectionStatus.MATCHED, match);
    }

    /** Internal raw-image candidate; native host values never cross this package boundary. */
    record RawImageCandidate<T>(RawImageId id, String name, T nativeSource) {
        RawImageCandidate {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(nativeSource, "nativeSource");
        }
    }

    /** Internal selection result with an explicit ambiguity state. */
    record TargetSelection<T>(SelectionStatus status, RawImageCandidate<T> candidate) {
        TargetSelection {
            Objects.requireNonNull(status, "status");
            if (status == SelectionStatus.MATCHED) {
                Objects.requireNonNull(candidate, "candidate");
            } else if (candidate != null) {
                throw new IllegalArgumentException("non-matched selection must not carry a candidate");
            }
        }
    }

    /** Factual result of the first export slice; it carries no host object. */
    record ExportResult(
        ExportStatus status,
        Path target,
        TargetPathSafety targetPathSafety,
        boolean saveReturned,
        boolean outputReadable,
        LayerCompleteness layerCompleteness,
        FailurePhase failurePhase,
        String failureType,
        String failureMessage,
        EditorRawImagePsdIntegrityAccess.Verification integrityVerification,
        VerifiedAccessException.HostFailureCategory failureCategory,
        String sourceName
    ) {
        ExportResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(targetPathSafety, "targetPathSafety");
            Objects.requireNonNull(layerCompleteness, "layerCompleteness");
            Objects.requireNonNull(failurePhase, "failurePhase");
            Objects.requireNonNull(integrityVerification, "integrityVerification");
            Objects.requireNonNull(failureCategory, "failureCategory");
            Objects.requireNonNull(sourceName, "sourceName");
        }

        ExportResult(
            final ExportStatus status, final Path target, final TargetPathSafety targetPathSafety,
            final boolean saveReturned, final boolean outputReadable, final LayerCompleteness layerCompleteness,
            final FailurePhase failurePhase, final String failureType, final String failureMessage,
            final EditorRawImagePsdIntegrityAccess.Verification integrityVerification,
            final VerifiedAccessException.HostFailureCategory failureCategory
        ) {
            this(status, target, targetPathSafety, saveReturned, outputReadable, layerCompleteness,
                failurePhase, failureType, failureMessage, integrityVerification, failureCategory, "");
        }

        private ExportResult withSourceName(final String name) {
            return new ExportResult(status, target, targetPathSafety, saveReturned, outputReadable,
                layerCompleteness, failurePhase, failureType, failureMessage, integrityVerification,
                failureCategory, name);
        }

        ExportResult(
            final ExportStatus status, final Path target, final TargetPathSafety targetPathSafety,
            final boolean saveReturned, final boolean outputReadable, final LayerCompleteness layerCompleteness,
            final FailurePhase failurePhase, final String failureType, final String failureMessage,
            final EditorRawImagePsdIntegrityAccess.Verification integrityVerification
        ) {
            this(status, target, targetPathSafety, saveReturned, outputReadable, layerCompleteness,
                failurePhase, failureType, failureMessage, integrityVerification,
                VerifiedAccessException.HostFailureCategory.UNKNOWN);
        }

        PsdExportHost.Observation observation() {
            return new PsdExportHost.Observation(status.name(), integrityVerification.status().name(),
                outputReadable, integrityVerification.rootNameMatches() && integrityVerification.dimensionsMatch()
                    && integrityVerification.layerTreeMatches(),
                failurePhase == FailurePhase.NONE ? Optional.empty() : Optional.of(new PsdExportHost.Failure(
                    failurePhase.name(), failureCategory.name(), saveReturned)), sourceName);
        }

        ExportResult(
            final ExportStatus status,
            final Path target,
            final TargetPathSafety targetPathSafety,
            final boolean saveReturned,
            final boolean outputReadable,
            final LayerCompleteness layerCompleteness,
            final FailurePhase failurePhase,
            final String failureType,
            final String failureMessage
        ) {
            this(
                status,
                target,
                targetPathSafety,
                saveReturned,
                outputReadable,
                layerCompleteness,
                failurePhase,
                failureType,
                failureMessage,
                EditorRawImagePsdIntegrityAccess.Verification.unavailable(
                    "export integrity was not captured for this result"
                )
            );
        }

        private static ExportResult unavailable(final Path target, final String detail) {
            return new ExportResult(
                ExportStatus.UNAVAILABLE,
                target,
                TargetPathSafety.NOT_CHECKED,
                false,
                false,
                LayerCompleteness.UNVERIFIED,
                FailurePhase.AVAILABILITY,
                "UNAVAILABLE",
                detail
            );
        }

        private static ExportResult invalidSource(
            final Path target,
            final TargetPathSafety pathSafety
        ) {
            return new ExportResult(
                ExportStatus.BOUND_SOURCE_INVALID,
                target,
                pathSafety,
                false,
                false,
                LayerCompleteness.UNVERIFIED,
                FailurePhase.SOURCE_IDENTITY,
                "BOUND_SOURCE_TYPE",
                "bound native source is not the exact verified CLayeredImage type"
            );
        }

        private static ExportResult bindingRejected(
            final Path target,
            final EditorRawImagePsdSourceBinding.BindingResult binding
        ) {
            final ExportStatus status = binding.status() == EditorRawImagePsdSourceBinding.BindingStatus.UNAVAILABLE
                ? ExportStatus.UNAVAILABLE
                : ExportStatus.BOUND_SOURCE_INVALID;
            return new ExportResult(
                status,
                target,
                TargetPathSafety.NOT_CHECKED,
                false,
                false,
                LayerCompleteness.UNVERIFIED,
                FailurePhase.SOURCE_BINDING,
                "SOURCE_BINDING_" + binding.status().name(),
                binding.detail()
            );
        }

        private static ExportResult targetRejected(
            final Path target,
            final boolean saveReturned,
            final TargetPathSafety pathSafety,
            final FailurePhase phase,
            final TargetState state
        ) {
            return new ExportResult(
                ExportStatus.TARGET_REJECTED,
                target,
                pathSafety,
                saveReturned,
                false,
                LayerCompleteness.UNVERIFIED,
                phase,
                "TARGET_" + state.name(),
                "runtime-owned target must be absent or an existing zero-length ordinary file; observed "
                    + state.name()
            );
        }

        private static ExportResult targetCheckFailed(
            final Path target,
            final boolean saveReturned,
            final TargetPathSafety pathSafety,
            final FailurePhase phase,
            final IOException failure
        ) {
            return new ExportResult(
                ExportStatus.TARGET_REJECTED,
                target,
                pathSafety,
                saveReturned,
                false,
                LayerCompleteness.UNVERIFIED,
                phase,
                "TARGET_CHECK_FAILED",
                "unable to inspect the final target path with NOFOLLOW_LINKS: " + message(failure)
            );
        }

        private static ExportResult outputMissing(
            final Path target,
            final boolean saveReturned,
            final TargetPathSafety pathSafety
        ) {
            return new ExportResult(
                ExportStatus.OUTPUT_MISSING,
                target,
                pathSafety,
                saveReturned,
                false,
                LayerCompleteness.UNVERIFIED,
                FailurePhase.TARGET_POSTCHECK,
                "OUTPUT_NOT_WRITTEN",
                "native save returned normally but produced no non-empty regular target file"
            );
        }

        private static ExportResult parseFailed(
            final Path target,
            final boolean saveReturned,
            final TargetPathSafety pathSafety,
            final FailurePhase phase,
            final String detail
        ) {
            return new ExportResult(
                ExportStatus.PARSE_FAILED,
                target,
                pathSafety,
                saveReturned,
                false,
                LayerCompleteness.UNVERIFIED,
                phase,
                "PARSE_UNREADABLE",
                detail
            );
        }

        private static ExportResult readableUnverified(
            final Path target,
            final TargetPathSafety pathSafety
        ) {
            return readableUnverified(
                target,
                pathSafety,
                EditorRawImagePsdIntegrityAccess.Verification.unavailable(
                    "source binding snapshot was not supplied; export fidelity was not compared"
                )
            );
        }

        private static ExportResult readableUnverified(
            final Path target,
            final TargetPathSafety pathSafety,
            final EditorRawImagePsdIntegrityAccess.Verification integrityVerification
        ) {
            return new ExportResult(
                ExportStatus.READABLE_UNVERIFIED,
                target,
                pathSafety,
                true,
                true,
                LayerCompleteness.UNVERIFIED,
                FailurePhase.NONE,
                null,
                null,
                integrityVerification
            );
        }

        private static ExportResult failure(
            final Path target,
            final boolean saveReturned,
            final TargetPathSafety pathSafety,
            final FailurePhase phase,
            final RuntimeException failure
        ) {
            final ExportStatus status = phase == FailurePhase.PARSE || phase == FailurePhase.CONSTRUCT
                ? ExportStatus.PARSE_FAILED
                : ExportStatus.NATIVE_FAILURE;
            return new ExportResult(
                status,
                target,
                pathSafety,
                saveReturned,
                false,
                LayerCompleteness.UNVERIFIED,
                phase,
                failure.getClass().getName(),
                message(failure),
                EditorRawImagePsdIntegrityAccess.Verification.unavailable(
                    "export integrity was not captured for this result"),
                failure instanceof VerifiedAccessException verified
                    ? verified.hostFailureCategory() : VerifiedAccessException.HostFailureCategory.UNKNOWN
            );
        }
    }

    /** Evidence from final-path checks only; it is not a race-free ownership proof. */
    enum TargetPathSafety {
        NOT_CHECKED,
        FINAL_PATH_NOFOLLOW_PRE_ONLY,
        FINAL_PATH_NOFOLLOW_PRE_AND_POST
    }

    enum ExportStatus {
        UNAVAILABLE,
        BOUND_SOURCE_INVALID,
        TARGET_REJECTED,
        OUTPUT_MISSING,
        PARSE_FAILED,
        READABLE_UNVERIFIED,
        NATIVE_FAILURE
    }

    enum LayerCompleteness {
        UNVERIFIED
    }

    enum FailurePhase {
        NONE,
        AVAILABILITY,
        TARGET_PRECHECK,
        SOURCE_IDENTITY,
        SOURCE_BINDING,
        PROGRESS,
        SOURCE_NAME,
        SAVE,
        TARGET_POSTCHECK,
        OUTPUT_CHECK,
        PARSE,
        CONSTRUCT,
        DISPOSE
    }

    enum SelectionStatus {
        MATCHED,
        NOT_FOUND,
        DUPLICATE
    }
}
