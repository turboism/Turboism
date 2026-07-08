package dev.turboism.sdk.cubism;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ProjectSnapshot(
    String projectId,
    String name,
    Optional<Path> projectDirectory,
    List<DocumentSnapshot> documents
) {
    public ProjectSnapshot {
        projectId = Objects.requireNonNull(projectId, "projectId");
        name = Objects.requireNonNull(name, "name");
        projectDirectory = Objects.requireNonNull(projectDirectory, "projectDirectory");
        documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
        if (projectDirectory.isPresent() && projectDirectory.get().isAbsolute()) {
            throw new IllegalArgumentException("projectDirectory must be relative or absent");
        }
    }
}
