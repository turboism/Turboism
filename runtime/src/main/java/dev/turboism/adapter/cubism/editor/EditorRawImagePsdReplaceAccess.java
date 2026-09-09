package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorRawImagePsdReplaceSelectorContract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Package-private T015 seam for one explicit native PSD raw-image replace invocation.
 *
 * <p>The caller supplies the app controller, modeling document, old layered-image list, already
 * parsed incoming layered image, and a runtime-owned stable stage. Type checks only establish the
 * exact native shapes named by the verified resolver. They do not prove that those values belong to
 * one model, RawImageId, document generation, or registry entry; that binding is an unconnected
 * production precondition.</p>
 *
 * <p>The five-argument {@code com.live2d.cubism.process.psd.a.a} call owns the native
 * {@code beginEdit("Import PSD")} / {@code endEdit(false, ...)} GroupUndo boundary. This adapter
 * never calls begin/end, creates an Undo entry, retries, or rolls back. A normal void return is
 * therefore reported as {@link ReplaceStatus#NATIVE_RETURNED_UNVERIFIED}, never as APPLIED. Any
 * exception from the attempted native call is conservatively reported as partial/unknown because
 * the native mutation point and rollback state are not observable here. Real host outcome and
 * post-mutation reread remain an upper-layer responsibility.</p>
 *
 * <p>All native resolver calls and both current guards run inside one synchronous
 * {@link EditorHostThread} dispatch. The stage is checked as a non-empty regular file with
 * {@link LinkOption#NOFOLLOW_LINKS} immediately before the native call. That check does not remove
 * general path races and is not a security registry.</p>
 */
final class EditorRawImagePsdReplaceAccess {
    private final VerifiedMemberResolver resolver;
    private final EditorObjectReadAccess.CurrentGuard currentGuard;

    EditorRawImagePsdReplaceAccess(
        final VerifiedMemberResolver resolver,
        final EditorObjectReadAccess.CurrentGuard currentGuard
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.currentGuard = Objects.requireNonNull(currentGuard, "currentGuard");
    }

    /**
     * Attempts the exact 5.3.02 native replace call without exposing host values outside runtime.
     *
     * @param identity current runtime/session identity used by the guard
     * @param model model object captured by the caller's current-model guard
     * @param appController explicitly bound native CEAppCtrl-shaped value
     * @param document explicitly bound CModelingDocument-shaped value
     * @param oldLayeredImages explicit old CLayeredImage targets; at least one, identity-unique
     * @param incomingParsedLayeredImage parsed incoming CLayeredImage-shaped value
     * @param stableStage runtime-owned non-empty regular file containing the incoming PSD
     * @return an internal factual result; no result represents applied state
     */
    ReplaceResult replacePsd(
        final String identity,
        final Object model,
        final Object appController,
        final Object document,
        final List<?> oldLayeredImages,
        final Object incomingParsedLayeredImage,
        final Path stableStage
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(model, "model");

        if (!resolver.isExactCubismVersion(EditorRawImagePsdReplaceSelectorContract.SUPPORTED_CUBISM_VERSION)) {
            return ReplaceResult.unavailable(
                ReplaceFailurePhase.AVAILABILITY,
                "unsupported Cubism version: " + resolver.cubismVersion()
            );
        }
        if (!resolver.authorizesFeature(
            EditorRawImagePsdReplaceSelectorContract.ADAPTER_SLICE_ID,
            EditorRawImagePsdReplaceSelectorContract.CAPABILITY_ID,
            EditorRawImagePsdReplaceSelectorContract.REQUIRED_ALIASES
        )) {
            return ReplaceResult.unavailable(
                ReplaceFailurePhase.AVAILABILITY,
                "PSD raw-image replace lacks the complete exact selector authorization"
            );
        }

        return EditorHostThread.dispatch(
            "Cubism PSD raw-image replace",
            () -> replaceOnHostThread(
                identity,
                model,
                appController,
                document,
                oldLayeredImages,
                incomingParsedLayeredImage,
                stableStage
            )
        );
    }

    private ReplaceResult replaceOnHostThread(
        final String identity,
        final Object model,
        final Object appController,
        final Object document,
        final List<?> oldLayeredImages,
        final Object incomingParsedLayeredImage,
        final Path stableStage
    ) {
        try {
            currentGuard.requireCurrent(identity, model);
        } catch (RuntimeException failure) {
            return ReplaceResult.staleBeforeNative(failure);
        }

        final ReplaceResult inputFailure = validateInputs(
            appController,
            document,
            oldLayeredImages,
            incomingParsedLayeredImage,
            stableStage
        );
        if (inputFailure != null) {
            return inputFailure;
        }

        final Object editMode;
        final Object editingValue;
        try {
            editMode = resolver.invoke(
                EditorRawImagePsdReplaceSelectorContract.CURRENT_EDIT_MODE_ALIAS,
                document
            );
            if (editMode == null) {
                return ReplaceResult.unavailable(
                    ReplaceFailurePhase.EDITING_STATE,
                    "verified current edit mode is null"
                );
            }
            editingValue = resolver.invoke(
                EditorRawImagePsdReplaceSelectorContract.EDITING_STATE_ALIAS,
                editMode
            );
        } catch (RuntimeException failure) {
            return ReplaceResult.unavailable(ReplaceFailurePhase.EDITING_STATE, failure);
        }
        if (!(editingValue instanceof Boolean editing)) {
            return ReplaceResult.unavailable(
                ReplaceFailurePhase.EDITING_STATE,
                "verified isEditing selector returned a non-Boolean value"
            );
        }
        if (editing) {
            return ReplaceResult.editingRejected();
        }

        final StageState stageState;
        try {
            stageState = inspectStage(stableStage);
        } catch (IOException failure) {
            return ReplaceResult.invalidInput(ReplaceFailurePhase.STAGE, failure);
        }
        if (stageState != StageState.READY) {
            return ReplaceResult.invalidInput(
                ReplaceFailurePhase.STAGE,
                "stable stage must be a non-empty regular file; observed " + stageState
            );
        }

        final Object nativeProcess;
        try {
            nativeProcess = resolver.readStaticField(
                EditorRawImagePsdReplaceSelectorContract.PSD_IMPORT_PROCESS_INSTANCE_ALIAS
            );
            if (!resolver.isInstance(
                EditorRawImagePsdReplaceSelectorContract.PSD_IMPORT_PROCESS_CLASS_ALIAS,
                nativeProcess
            )) {
                return ReplaceResult.unavailable(
                    ReplaceFailurePhase.NATIVE_RECEIVER,
                    "verified PSD import process singleton has the wrong type"
                );
            }
        } catch (RuntimeException failure) {
            return ReplaceResult.unavailable(ReplaceFailurePhase.NATIVE_RECEIVER, failure);
        }

        final List<Object> targets = copyTargets(oldLayeredImages);
        boolean nativeReturned = false;
        RuntimeException nativeFailure = null;
        try {
            resolver.invoke(
                EditorRawImagePsdReplaceSelectorContract.PSD_IMPORT_REPLACE_ALIAS,
                nativeProcess,
                appController,
                incomingParsedLayeredImage,
                stableStage.toFile(),
                document,
                targets
            );
            nativeReturned = true;
        } catch (RuntimeException failure) {
            nativeFailure = failure;
        }

        RuntimeException postGuardFailure = null;
        try {
            currentGuard.requireCurrent(identity, model);
        } catch (RuntimeException failure) {
            postGuardFailure = failure;
        }

        if (nativeFailure != null) {
            final String detail = postGuardFailure == null
                ? "native replace invocation failed; mutation and rollback state are unknown"
                : "native replace invocation failed and post current guard failed; mutation and rollback state are unknown"
                    + "; post-guard: " + message(postGuardFailure);
            return ReplaceResult.partialFailure(
                ReplaceFailurePhase.NATIVE_INVOCATION,
                nativeFailure,
                false,
                postGuardFailure == null,
                detail
            );
        }
        if (postGuardFailure != null) {
            return ReplaceResult.partialFailure(
                ReplaceFailurePhase.CURRENT_GUARD_AFTER_NATIVE,
                postGuardFailure,
                true,
                false,
                "native returned but the post current guard failed; mutation and rollback state are unknown"
            );
        }
        if (!nativeReturned) {
            throw new IllegalStateException("native replace result lost its return state");
        }
        return ReplaceResult.nativeReturnedUnverified();
    }

    private ReplaceResult validateInputs(
        final Object appController,
        final Object document,
        final List<?> oldLayeredImages,
        final Object incomingParsedLayeredImage,
        final Path stableStage
    ) {
        if (appController == null || document == null || oldLayeredImages == null
            || incomingParsedLayeredImage == null || stableStage == null) {
            return ReplaceResult.invalidInput(
                ReplaceFailurePhase.INPUT,
                "app controller, document, target list, incoming object, and stage are required"
            );
        }
        try {
            if (!resolver.isInstance(
                EditorRawImagePsdReplaceSelectorContract.APP_CONTROLLER_CLASS_ALIAS,
                appController
            )) {
                return ReplaceResult.invalidInput(
                    ReplaceFailurePhase.INPUT,
                    "app controller is not the exact verified CEAppCtrl type"
                );
            }
            if (!resolver.isInstance(
                EditorRawImagePsdReplaceSelectorContract.MODELING_DOCUMENT_CLASS_ALIAS,
                document
            )) {
                return ReplaceResult.invalidInput(
                    ReplaceFailurePhase.INPUT,
                    "document is not the exact verified CModelingDocument type"
                );
            }
            if (!resolver.isInstance(
                EditorRawImagePsdReplaceSelectorContract.LAYERED_IMAGE_CLASS_ALIAS,
                incomingParsedLayeredImage
            )) {
                return ReplaceResult.invalidInput(
                    ReplaceFailurePhase.INPUT,
                    "incoming object is not the exact verified CLayeredImage type"
                );
            }

            if (oldLayeredImages.isEmpty()) {
                return ReplaceResult.invalidInput(
                    ReplaceFailurePhase.TARGETS,
                    "old CLayeredImage target list must not be empty"
                );
            }
            final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
            for (final Object target : oldLayeredImages) {
                if (target == null) {
                    return ReplaceResult.invalidInput(
                        ReplaceFailurePhase.TARGETS,
                        "old CLayeredImage target list must not contain null"
                    );
                }
                if (target == incomingParsedLayeredImage) {
                    return ReplaceResult.invalidInput(
                        ReplaceFailurePhase.TARGETS,
                        "incoming parsed CLayeredImage must not also be an old target"
                    );
                }
                if (seen.put(target, Boolean.TRUE) != null) {
                    return ReplaceResult.invalidInput(
                        ReplaceFailurePhase.TARGETS,
                        "old CLayeredImage target list contains an identity duplicate"
                    );
                }
                if (!resolver.isInstance(
                    EditorRawImagePsdReplaceSelectorContract.LAYERED_IMAGE_CLASS_ALIAS,
                    target
                )) {
                    return ReplaceResult.invalidInput(
                        ReplaceFailurePhase.TARGETS,
                        "old target is not the exact verified CLayeredImage type"
                    );
                }
            }
        } catch (RuntimeException failure) {
            return ReplaceResult.unavailable(ReplaceFailurePhase.INPUT, failure);
        }
        return null;
    }

    private static List<Object> copyTargets(final List<?> oldLayeredImages) {
        final List<Object> targets = new ArrayList<>(oldLayeredImages.size());
        targets.addAll(oldLayeredImages);
        return List.copyOf(targets);
    }

    private static StageState inspectStage(final Path stage) throws IOException {
        try {
            final BasicFileAttributes attributes = Files.readAttributes(
                stage,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
            );
            if (attributes.isSymbolicLink()) {
                return StageState.SYMBOLIC_LINK;
            }
            if (!attributes.isRegularFile()) {
                return StageState.OTHER;
            }
            return attributes.size() == 0 ? StageState.EMPTY : StageState.READY;
        } catch (NoSuchFileException exception) {
            return StageState.ABSENT;
        }
    }

    private static String message(final Throwable failure) {
        return failure.getMessage() == null ? "" : failure.getMessage();
    }

    private enum StageState {
        ABSENT,
        EMPTY,
        READY,
        SYMBOLIC_LINK,
        OTHER
    }

    enum ReplaceStatus {
        UNAVAILABLE,
        INVALID_INPUT,
        STALE_BEFORE_NATIVE,
        EDITING_REJECTED,
        NATIVE_RETURNED_UNVERIFIED,
        PARTIAL_FAILURE
    }

    enum ReplaceFailurePhase {
        NONE,
        AVAILABILITY,
        INPUT,
        TARGETS,
        STAGE,
        CURRENT_GUARD_BEFORE_NATIVE,
        EDITING_STATE,
        NATIVE_RECEIVER,
        NATIVE_INVOCATION,
        CURRENT_GUARD_AFTER_NATIVE
    }

    enum MutationState {
        NOT_ATTEMPTED,
        UNKNOWN
    }

    /** Internal result deliberately containing no native object, Path, or SDK value. */
    record ReplaceResult(
        ReplaceStatus status,
        boolean preCurrentGuardPassed,
        boolean postCurrentGuardPassed,
        boolean nativeInvocationAttempted,
        boolean nativeReturned,
        MutationState mutationState,
        boolean requiresPause,
        boolean requiresReobservation,
        ReplaceFailurePhase failurePhase,
        String failureType,
        String failureMessage
    ) {
        ReplaceResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(mutationState, "mutationState");
            Objects.requireNonNull(failurePhase, "failurePhase");
            if (requiresPause && mutationState != MutationState.UNKNOWN) {
                throw new IllegalArgumentException("a paused result must have unknown mutation state");
            }
            if (nativeReturned && !nativeInvocationAttempted) {
                throw new IllegalArgumentException("nativeReturned requires an attempted invocation");
            }
        }

        private static ReplaceResult unavailable(
            final ReplaceFailurePhase phase,
            final String detail
        ) {
            return new ReplaceResult(
                ReplaceStatus.UNAVAILABLE,
                false,
                false,
                false,
                false,
                MutationState.NOT_ATTEMPTED,
                false,
                false,
                phase,
                "UNAVAILABLE",
                detail
            );
        }

        private static ReplaceResult unavailable(
            final ReplaceFailurePhase phase,
            final RuntimeException failure
        ) {
            return unavailable(phase, message(failure));
        }

        private static ReplaceResult invalidInput(
            final ReplaceFailurePhase phase,
            final String detail
        ) {
            return new ReplaceResult(
                ReplaceStatus.INVALID_INPUT,
                true,
                false,
                false,
                false,
                MutationState.NOT_ATTEMPTED,
                false,
                false,
                phase,
                "INVALID_INPUT",
                detail
            );
        }

        private static ReplaceResult invalidInput(
            final ReplaceFailurePhase phase,
            final IOException failure
        ) {
            return invalidInput(phase, message(failure));
        }

        private static ReplaceResult staleBeforeNative(final RuntimeException failure) {
            return new ReplaceResult(
                ReplaceStatus.STALE_BEFORE_NATIVE,
                false,
                false,
                false,
                false,
                MutationState.NOT_ATTEMPTED,
                false,
                false,
                ReplaceFailurePhase.CURRENT_GUARD_BEFORE_NATIVE,
                failure.getClass().getName(),
                message(failure)
            );
        }

        private static ReplaceResult editingRejected() {
            return new ReplaceResult(
                ReplaceStatus.EDITING_REJECTED,
                true,
                false,
                false,
                false,
                MutationState.NOT_ATTEMPTED,
                false,
                false,
                ReplaceFailurePhase.EDITING_STATE,
                "EDITING",
                "native replace refused while the current edit mode is already editing"
            );
        }

        private static ReplaceResult nativeReturnedUnverified() {
            return new ReplaceResult(
                ReplaceStatus.NATIVE_RETURNED_UNVERIFIED,
                true,
                true,
                true,
                true,
                MutationState.UNKNOWN,
                false,
                true,
                ReplaceFailurePhase.NONE,
                null,
                "native five-argument replace returned void; applied state requires upper-layer reread"
            );
        }

        private static ReplaceResult partialFailure(
            final ReplaceFailurePhase phase,
            final RuntimeException failure,
            final boolean nativeReturned,
            final boolean postGuardPassed,
            final String detail
        ) {
            return new ReplaceResult(
                ReplaceStatus.PARTIAL_FAILURE,
                true,
                postGuardPassed,
                true,
                nativeReturned,
                MutationState.UNKNOWN,
                true,
                true,
                phase,
                failure.getClass().getName(),
                detail + ": " + message(failure)
            );
        }
    }
}
