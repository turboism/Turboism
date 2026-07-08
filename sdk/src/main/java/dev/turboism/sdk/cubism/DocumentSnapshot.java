package dev.turboism.sdk.cubism;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record DocumentSnapshot(
    String documentId,
    String name,
    String relativePath,
    Optional<Path> filePath,
    Optional<ModelSnapshot> model
) {
    public DocumentSnapshot {
        documentId = Objects.requireNonNull(documentId, "documentId");
        name = Objects.requireNonNull(name, "name");
        relativePath = Objects.requireNonNull(relativePath, "relativePath");
        filePath = Objects.requireNonNull(filePath, "filePath");
        model = Objects.requireNonNull(model, "model");
        if (relativePath.isBlank() || relativePath.startsWith("/")) {
            throw new IllegalArgumentException("relativePath must be a relative path string");
        }
        if (filePath.isPresent() && filePath.get().isAbsolute()) {
            throw new IllegalArgumentException("filePath must be relative or absent");
        }
    }
}
