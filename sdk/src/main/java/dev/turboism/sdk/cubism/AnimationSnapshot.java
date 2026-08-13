package dev.turboism.sdk.cubism;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Snapshot of one animation file and its scene documents. */
public record AnimationSnapshot(
    String animationId,
    String name,
    Optional<Path> filePath,
    List<String> sceneDocumentIds,
    Optional<String> activeSceneDocumentId
) {
    public AnimationSnapshot {
        animationId = requireText(animationId, "animationId");
        name = requireText(name, "name");
        filePath = Objects.requireNonNull(filePath, "filePath");
        sceneDocumentIds = List.copyOf(Objects.requireNonNull(sceneDocumentIds, "sceneDocumentIds"));
        activeSceneDocumentId = Objects.requireNonNull(activeSceneDocumentId, "activeSceneDocumentId");
        if (filePath.isPresent() && filePath.get().isAbsolute()) {
            throw new IllegalArgumentException("filePath must be relative or absent");
        }
        if (activeSceneDocumentId.isPresent()
            && !sceneDocumentIds.contains(activeSceneDocumentId.orElseThrow())) {
            throw new IllegalArgumentException(
                "activeSceneDocumentId must identify one of sceneDocumentIds"
            );
        }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
