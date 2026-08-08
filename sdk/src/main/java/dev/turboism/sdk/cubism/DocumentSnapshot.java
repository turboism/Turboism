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
    DocumentKind kind,
    String relativePath,
    Optional<Path> filePath,
    Optional<String> contentId,
    Optional<ModelSnapshot> model,
    Optional<AnimationSnapshot> animation
) {
    public DocumentSnapshot {
        documentId = requireText(documentId, "documentId");
        name = requireText(name, "name");
        kind = Objects.requireNonNull(kind, "kind");
        relativePath = requireText(relativePath, "relativePath");
        filePath = Objects.requireNonNull(filePath, "filePath");
        contentId = Objects.requireNonNull(contentId, "contentId");
        model = Objects.requireNonNull(model, "model");
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
            model.isPresent() ? DocumentKind.MODEL : DocumentKind.OTHER,
            relativePath,
            filePath,
            Optional.empty(),
            model,
            Optional.empty()
        );
    }

    public boolean isModelDocument() {
        return kind == DocumentKind.MODEL;
    }

    public boolean isAnimationDocument() {
        return kind == DocumentKind.ANIMATION_SCENE;
    }

    public boolean isImageDocument() {
        return kind == DocumentKind.IMAGE;
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
