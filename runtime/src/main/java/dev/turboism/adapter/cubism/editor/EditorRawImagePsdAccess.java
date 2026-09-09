package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
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

/**
 * Package-private T003/T011 seam for PSD raw-image targeting and native export validation.
 *
 * <p>The caller supplies a raw-image native object already bound by the ID-based selector and a
 * runtime-owned target. The binding is an internal precondition, not a proof performed here:
 * the class check below only verifies the native runtime type and does not establish that the
 * object belongs to the supplied current model or RawImageId. Production binding remains
 * unconnected. The native save and parse calls are kept inside one synchronous
 * {@link EditorHostThread} boundary. This slice does not replace a raw image, open an editor,
 * create an Undo entry, or expose a host object or {@link Path} through the SDK. Final-path
 * {@link LinkOption#NOFOLLOW_LINKS} checks happen immediately before and after save; they do not
 * eliminate parent-path or general TOCTOU races, and this class is not a security registry.</p>
 */
final class EditorRawImagePsdAccess {
    private final VerifiedMemberResolver resolver;
    private final EditorObjectReadAccess.CurrentGuard currentGuard;

    EditorRawImagePsdAccess(
        final VerifiedMemberResolver resolver,
        final EditorObjectReadAccess.CurrentGuard currentGuard
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.currentGuard = Objects.requireNonNull(currentGuard, "currentGuard");
    }

    /**
     * Exports one explicitly bound native {@code CLayeredImage} and reparses its output.
     * The caller must establish that binding as an internal precondition; this adapter's exact
     * native-type check is not ownership verification, and production binding is not connected.
     *
     * <p>The verified {@code com.live2d.util.a.a.e()} factory supplies the concrete synchronous
     * progress object required by {@code CLayeredImage.save(File, Progress)}. Its bytecode returns
     * the native shared default instance, whose cancellation/status state is delegated to its
     * internal state object and therefore reused; this adapter never treats it as a callback,
     * resets it, or exposes it. A normal {@code void} return is not enough for success: the target
     * must be a non-empty regular file, {@code CPsdDocument.Companion.a(File, false, false)} must
     * return the exact parsed type, and the exact {@code CLayeredImage(parsed, file, name)}
     * constructor must accept it. Parsed layer completeness remains explicitly unverified. The
     * runtime-owned target may be absent or an existing zero-length ordinary file; existing
     * non-empty files, symbolic links, and other file types are rejected.</p>
     */
    ExportResult exportPsd(
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
            () -> exportOnHostThread(identity, model, boundNativeSource, target)
        );
    }

    private ExportResult exportOnHostThread(
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
                EditorRawImagePsdSelectorContract.PSD_PROGRESS_DEFAULT_ALIAS,
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
                EditorRawImagePsdSelectorContract.PSD_DOCUMENT_COMPANION_ALIAS,
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

            phase = FailurePhase.CONSTRUCT;
            final Object reconstructed = resolver.construct(
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
            return ExportResult.readableUnverified(target, pathSafety);
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
        String failureMessage
    ) {
        ExportResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(targetPathSafety, "targetPathSafety");
            Objects.requireNonNull(layerCompleteness, "layerCompleteness");
            Objects.requireNonNull(failurePhase, "failurePhase");
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
            return new ExportResult(
                ExportStatus.READABLE_UNVERIFIED,
                target,
                pathSafety,
                true,
                true,
                LayerCompleteness.UNVERIFIED,
                FailurePhase.NONE,
                null,
                null
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
                message(failure)
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
        PROGRESS,
        SOURCE_NAME,
        SAVE,
        TARGET_POSTCHECK,
        OUTPUT_CHECK,
        PARSE,
        CONSTRUCT
    }

    enum SelectionStatus {
        MATCHED,
        NOT_FOUND,
        DUPLICATE
    }
}
