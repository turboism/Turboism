package dev.turboism.sdk.cubism;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One file-backed entry in the current Cubism project.
 *
 * <p>A model file is both file content and one model document. An animation file is file content
 * that owns one or more scene documents. Layered image/PSD project entries are not ordinary
 * {@code IFileContent}, but they may own an IMAGE editor document and source resources.</p>
 */
public record ProjectContentSnapshot(
    String contentId,
    String name,
    ProjectContentKind kind,
    Optional<Path> filePath,
    List<String> documentIds,
    List<ProjectResourceSnapshot> resources
) {
    public ProjectContentSnapshot {
        contentId = requireText(contentId, "contentId");
        name = requireText(name, "name");
        kind = Objects.requireNonNull(kind, "kind");
        filePath = Objects.requireNonNull(filePath, "filePath");
        documentIds = List.copyOf(Objects.requireNonNull(documentIds, "documentIds"));
        resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
        if (filePath.isPresent() && filePath.get().isAbsolute()) {
            throw new IllegalArgumentException("filePath must be relative or absent");
        }
    }

    public ProjectContentSnapshot(
        final String contentId,
        final String name,
        final ProjectContentKind kind,
        final Optional<Path> filePath,
        final List<String> documentIds
    ) {
        this(contentId, name, kind, filePath, documentIds, List.of());
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
