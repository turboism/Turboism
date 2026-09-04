package dev.turboism.sdk.cubism;

import java.util.Optional;

/**
 * Projection helpers for deriving typed active-document views from SDK snapshots.
 *
 * <p>These methods preserve empty-state semantics while avoiding repeated
 * snapshot navigation in plugins and SDK default methods.</p>
 */
public final class ActiveReadProjections {

    private ActiveReadProjections() {
    }

    /**
     * Resolves the animation owned by an active animation-scene document.
     *
     * @param document active document snapshot, when available
     * @return the owning animation snapshot, or empty when the document is absent or not an animation scene
     */
    public static Optional<AnimationSnapshot> animationOf(final Optional<DocumentSnapshot> document) {
        return document.flatMap(DocumentSnapshot::animation);
    }

    /**
     * Restricts the active document to layered image and PSD document kinds.
     *
     * @param document active document snapshot, when available
     * @return the image document, or empty when the document is absent or has another kind
     */
    public static Optional<DocumentSnapshot> imageDocumentOf(final Optional<DocumentSnapshot> document) {
        return document.filter(DocumentSnapshot::isImageDocument);
    }

    /**
     * Resolves the project entry that owns the active document.
     *
     * @param project active project snapshot, when available
     * @param document active document snapshot, when available
     * @return the owning project entry, or empty when either snapshot or their link is absent
     */
    public static Optional<ProjectContentSnapshot> projectContentOf(
        final Optional<ProjectSnapshot> project,
        final Optional<DocumentSnapshot> document
    ) {
        return document
            .flatMap(doc -> doc.contentId())
            .flatMap(contentId -> project.flatMap(item -> item.content(contentId)));
    }
}
