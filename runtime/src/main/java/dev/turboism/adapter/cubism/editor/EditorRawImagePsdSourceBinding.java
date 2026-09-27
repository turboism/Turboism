package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.RawImageId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Package-private ID-to-native binding for one current model-source texture manager.
 *
 * <p>The only native object returned by this helper is selected from the current source's raw
 * image collection. Names, paths, and caller-supplied objects are not identity substitutes. The
 * caller must have already established the current model/source pairing with its current-model
 * guard; no verified model-to-source ownership selector exists in this slice. An exact
 * {@code CLayeredImage} type check therefore proves only native shape, not model ownership. A
 * source PSD document is not required: a raw image with a valid native layer resource may be
 * exported, while the absence of that document does not classify the resource as PSD.</p>
 */
final class EditorRawImagePsdSourceBinding {
    private final VerifiedMemberResolver resolver;
    private final EditorRawImagePsdIntegrityAccess integrityAccess;

    EditorRawImagePsdSourceBinding(
        final VerifiedMemberResolver resolver,
        final EditorRawImagePsdIntegrityAccess integrityAccess
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.integrityAccess = Objects.requireNonNull(integrityAccess, "integrityAccess");
    }

    /**
     * Resolves exactly one raw image from the supplied current model source.
     * Must be called inside the caller's one host-thread/current-model boundary.
     */
    BindingResult bindOnHostThread(final Object modelSource, final RawImageId targetId) {
        return bindOnHostThread(modelSource, targetId, true);
    }

    /** Production export/replace identity lookup; does not inspect any layer tree. */
    BindingResult bindIdentityOnHostThread(final Object modelSource, final RawImageId targetId) {
        return bindOnHostThread(modelSource, targetId, false);
    }

    private BindingResult bindOnHostThread(final Object modelSource, final RawImageId targetId,
        final boolean captureIntegrity) {
        if (!EditorHostThread.isCurrent()) {
            throw new IllegalStateException("PSD source binding must run on the Editor host thread");
        }
        Objects.requireNonNull(modelSource, "modelSource");
        Objects.requireNonNull(targetId, "targetId");
        try {
            final Object textureManager = requireObject(
                resolver.invoke("cubism.editor-model.model-source.texture-manager", modelSource),
                "Editor texture manager"
            );
            final List<?> wrappers = snapshotList(
                resolver.invoke("cubism.editor-model.texture-manager.raw-images", textureManager),
                "Editor raw image collection"
            );
            final ArrayList<EditorRawImagePsdAccess.RawImageCandidate<Object>> candidates =
                new ArrayList<>(wrappers.size());
            final Set<RawImageId> ids = new HashSet<>();
            for (final Object wrapper : wrappers) {
                if (wrapper == null) throw unavailable("Editor raw image collection contains null");
                final Object layeredImage = requireObject(
                    resolver.invoke("cubism.editor-model.layered-image-wrapper.image", wrapper),
                    "Editor raw layered image"
                );
                if (!resolver.isInstance("cubism.editor-model.layered-image.class", layeredImage)) {
                    throw unavailable("Editor raw image collection contains an invalid CLayeredImage");
                }
                final RawImageId id = new RawImageId(guidValue(
                    resolver.invoke("cubism.editor-model.layered-image.guid", layeredImage),
                    "Editor raw image"
                ));
                if (!ids.add(id)) {
                    return new BindingResult(
                        BindingStatus.DUPLICATE_ID,
                        null,
                        null,
                        "Editor raw image identifiers are not unique"
                    );
                }
                if (!targetId.equals(id)) continue;
                final Object nameValue = resolver.invoke(
                    "cubism.editor-model.layered-image.name",
                    layeredImage
                );
                if (!(nameValue instanceof String name)) {
                    throw unavailable("Editor raw image name is invalid");
                }
                candidates.add(new EditorRawImagePsdAccess.RawImageCandidate<>(id, name, layeredImage));
            }

            final EditorRawImagePsdAccess.TargetSelection<Object> selection =
                EditorRawImagePsdAccess.selectByRawImageId(candidates, targetId);
            if (selection.status() == EditorRawImagePsdAccess.SelectionStatus.NOT_FOUND) {
                return new BindingResult(
                    BindingStatus.NOT_FOUND,
                    null,
                    null,
                    "requested RawImageId is absent from the current model-source texture manager"
                );
            }
            if (selection.status() == EditorRawImagePsdAccess.SelectionStatus.DUPLICATE) {
                return new BindingResult(
                    BindingStatus.DUPLICATE_ID,
                    null,
                    null,
                    "requested RawImageId resolves to more than one current raw image"
                );
            }
            final EditorRawImagePsdAccess.RawImageCandidate<Object> candidate = selection.candidate();
            final EditorRawImagePsdIntegrityAccess.Snapshot snapshot =
                captureIntegrity ? integrityAccess.captureOnHostThread(candidate.nativeSource()) : null;
            return new BindingResult(
                BindingStatus.MATCHED,
                candidate,
                snapshot,
                "selected current model-source raw image by exact RawImageId"
            );
        } catch (RuntimeException failure) {
            return new BindingResult(
                BindingStatus.UNAVAILABLE,
                null,
                null,
                "PSD source binding could not be verified: " + message(failure)
            );
        }
    }

    private static Object requireObject(final Object value, final String label) {
        if (value == null) throw unavailable(label + " is unavailable");
        return value;
    }

    private static List<?> snapshotList(final Object value, final String label) {
        if (!(value instanceof List<?> values)) throw unavailable(label + " is not a List");
        try {
            return List.copyOf(values);
        } catch (NullPointerException failure) {
            throw unavailable(label + " contains null");
        }
    }

    private String guidValue(final Object guid, final String label) {
        final Object value = requireObject(guid, label + " GUID");
        final Object raw = resolver.invoke("cubism.editor-model.guid.value", value);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw unavailable(label + " GUID is invalid");
        }
        return text;
    }

    private static IllegalStateException unavailable(final String detail) {
        return new IllegalStateException(detail);
    }

    private static String message(final Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage();
    }

    enum BindingStatus {
        MATCHED,
        NOT_FOUND,
        DUPLICATE_ID,
        INVALID,
        UNAVAILABLE
    }

    record BindingResult(
        BindingStatus status,
        EditorRawImagePsdAccess.RawImageCandidate<Object> candidate,
        EditorRawImagePsdIntegrityAccess.Snapshot snapshot,
        String detail
    ) {
        BindingResult {
            status = Objects.requireNonNull(status, "status");
            detail = Objects.requireNonNull(detail, "detail");
            if (status == BindingStatus.MATCHED) {
                Objects.requireNonNull(candidate, "candidate");
                // Identity-only production binding deliberately has no integrity snapshot.
            } else if (candidate != null || snapshot != null) {
                throw new IllegalArgumentException("non-matched binding must not carry native state");
            }
        }
    }
}
