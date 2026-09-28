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
import java.util.Objects;
import java.util.Optional;

/**
 * Package-private T003/T011 seam for PSD raw-image targeting and native export validation.
 *
 * <p>The ID-based overload resolves the selected raw image from the current model-source texture
 * manager before entering the native save sequence. The low-level bound-source overload is
 * retained only as an internal synthetic/native seam; its type check is not an ownership proof and
 * production binding remains an explicit caller precondition for that primitive. Native save runs
 * inside one synchronous {@link EditorHostThread} boundary, without parsing or reconstructing the
 * exported output. Staged replacement parsing is a separate import-only operation. This slice does
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

        EditorHostThread.dispatch(
            "Cubism PSD raw-image export pre-guard",
            () -> {
                currentGuard.requireCurrent(identity, model);
                return null;
            }
        );
        final ExportResult result = exportNative(boundNativeSource, target);
        if (result.saveReturned()) {
            EditorHostThread.dispatch(
                "Cubism PSD raw-image export post-guard",
                () -> {
                    currentGuard.requireCurrent(identity, model);
                    return null;
                }
            );
        }
        return result;
    }

    /**
     * Resolves the current model-source raw image by exact {@link RawImageId} and saves it.
     *
     * <p>Identity binding and the current-model guards run on the host thread; the native PSD
     * serialization runs on the calling thread, matching Cubism's own background export task.
     * The caller must keep the document quiescent for the whole call so the save cannot read
     * layers mutated mid-export.</p>
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

        final BoundExport bound = EditorHostThread.dispatch(
            "Cubism PSD raw-image export binding",
            () -> bindForExport(identity, modelSource, model, sourceId, target)
        );
        if (bound.rejection() != null) {
            return bound.rejection();
        }
        final ExportResult result = exportNative(bound.nativeSource(), target);
        if (result.saveReturned()) {
            EditorHostThread.dispatch(
                "Cubism PSD raw-image export post-guard",
                () -> {
                    currentGuard.requireCurrent(identity, model);
                    return null;
                }
            );
        }
        return result;
    }

    private BoundExport bindForExport(
        final String identity,
        final Object modelSource,
        final Object model,
        final RawImageId sourceId,
        final Path target
    ) {
        currentGuard.requireCurrent(identity, model);
        final EditorRawImagePsdSourceBinding.BindingResult binding =
            sourceBinding.bindIdentityOnHostThread(modelSource, sourceId);
        if (binding.status() != EditorRawImagePsdSourceBinding.BindingStatus.MATCHED) {
            return new BoundExport(ExportResult.bindingRejected(target, binding), null);
        }
        return new BoundExport(null, binding.candidate().nativeSource());
    }

    private record BoundExport(ExportResult rejection, Object nativeSource) {
    }

    /**
     * Parses one runtime-owned staged PSD into a verified native layered image for replacement.
     *
     * <p>Host thread only. The reconstructed value is type-checked against the same verified
     * constructors admitted for staged replacement; a parse or construct failure is reported as an
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

    /**
     * Serializes one bound native layered image to {@code target} on the calling thread.
     *
     * <p>Cubism's own export runs {@code CLayeredImage.save} on a background progress thread
     * rather than the Swing host thread; running it on the host thread freezes the editor UI
     * for the whole encoding of large layered images. The current-model guards in the callers
     * bracket this window on the host thread, and the caller is required to hold the document
     * quiescent so the read cannot observe a half-applied edit.</p>
     */
    private ExportResult exportNative(
        final Object boundNativeSource,
        final Path target
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

            // The user workflow requires native export, not a second PSD import for validation.
            // No parse, reconstruction, layer-tree comparison or pixel observation occurs here.
            return ExportResult.savedUnverified(target, pathSafety).withSourceName(sourceName);
        } catch (IOException exception) {
            return ExportResult.targetCheckFailed(target, saveReturned, pathSafety, phase, exception);
        } catch (RuntimeException exception) {
            return ExportResult.failure(target, saveReturned, pathSafety, phase, exception);
        }
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

        private static ExportResult savedUnverified(
            final Path target,
            final TargetPathSafety pathSafety
        ) {
            return new ExportResult(
                ExportStatus.SAVED_UNVERIFIED,
                target,
                pathSafety,
                true,
                true,
                LayerCompleteness.UNVERIFIED,
                FailurePhase.NONE,
                null,
                null,
                EditorRawImagePsdIntegrityAccess.Verification.unavailable(
                    "native save completed; export contents are not re-parsed or compared")
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
        SAVED_UNVERIFIED,
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
