package dev.turboism.sdk.cubism;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Snapshot of the current Cubism editor document ({@code IDocument}).
 *
 * <p>A document is an editor view, not necessarily a file. In particular, an animation file owns
 * scene documents; a model file is also its model document; layered image/PSD resources may open
 * as IMAGE documents even though their generic {@code getFileContent()} path is not implemented.</p>
 */
public record DocumentSnapshot(
    String documentId,
    String name,
    String relativePath,
    Optional<Path> filePath,
    Optional<ModelSnapshot> model,
    DocumentKind kind,
    Optional<String> contentId,
    Optional<AnimationSnapshot> animation
) {
    public DocumentSnapshot {
        documentId = requireText(documentId, "documentId");
        name = requireText(name, "name");
        relativePath = requireText(relativePath, "relativePath");
        filePath = Objects.requireNonNull(filePath, "filePath");
        model = Objects.requireNonNull(model, "model");
        kind = Objects.requireNonNull(kind, "kind");
        contentId = Objects.requireNonNull(contentId, "contentId");
        animation = Objects.requireNonNull(animation, "animation");
        if (relativePath.startsWith("/") || relativePath.contains("..")) {
            throw new IllegalArgumentException("relativePath must be a relative path string");
        }
        if (filePath.isPresent() && filePath.get().isAbsolute()) {
            throw new IllegalArgumentException("filePath must be relative or absent");
        }
        if (kind != DocumentKind.MODEL && model.isPresent()) {
            throw new IllegalArgumentException("Only MODEL documents may expose a model snapshot");
        }
        if (kind != DocumentKind.ANIMATION_SCENE && animation.isPresent()) {
            throw new IllegalArgumentException(
                "Only ANIMATION_SCENE documents may expose an animation snapshot"
            );
        }
    }

    /** Legacy constructor; model presence determines MODEL versus OTHER. */
    public DocumentSnapshot(
        final String documentId,
        final String name,
        final String relativePath,
        final Optional<Path> filePath,
        final Optional<ModelSnapshot> model
    ) {
        this(
            documentId,
            name,
            relativePath,
            filePath,
            model,
            model.isPresent() ? DocumentKind.MODEL : DocumentKind.OTHER,
            Optional.empty(),
            Optional.empty()
        );
    }

    /** @return true when this document is a model document and may therefore expose {@link #model()} */
    public boolean isModelDocument() {
        return kind == DocumentKind.MODEL;
    }

    /**
     * @return true when this document is an animation scene and may therefore expose
     *     {@link #animation()}; false for the animation file's other views
     */
    public boolean isAnimationDocument() {
        return kind == DocumentKind.ANIMATION_SCENE;
    }

    /**
     * @return true when this document is a layered image/PSD view, for which the host's generic
     *     file-content path is not implemented
     */
    public boolean isImageDocument() {
        return kind == DocumentKind.IMAGE;
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
