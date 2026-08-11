package dev.turboism.sdk.cubism;

import java.util.Optional;

/**
 * Shared active-document projection helpers.
 *
 * <p>Internal contract helpers used by {@link CubismFacade} and
 * {@link dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService}
 * default methods so the derived-read logic has a single source of truth.
 * Not part of the stable plugin API surface.</p>
 */
public final class ActiveReadProjections {

    private ActiveReadProjections() {
    }

    /** Animation file owning the active ANIMATION_SCENE document, when present. */
    public static Optional<AnimationSnapshot> animationOf(final Optional<DocumentSnapshot> document) {
        return document.flatMap(DocumentSnapshot::animation);
    }

    /** The active document only when it is a layered image/PSD document. */
    public static Optional<DocumentSnapshot> imageDocumentOf(final Optional<DocumentSnapshot> document) {
        return document.filter(DocumentSnapshot::isImageDocument);
    }

    /** Project entry owning the active document, when both are present and linked. */
    public static Optional<ProjectContentSnapshot> projectContentOf(
        final Optional<ProjectSnapshot> project,
        final Optional<DocumentSnapshot> document
    ) {
        return document
            .flatMap(doc -> doc.contentId())
            .flatMap(contentId -> project.flatMap(item -> item.content(contentId)));
    }
}
