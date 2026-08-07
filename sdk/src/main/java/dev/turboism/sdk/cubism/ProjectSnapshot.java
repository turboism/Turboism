package dev.turboism.sdk.cubism;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Snapshot of Cubism's current {@code CEProject} session container.
 *
 * <p>The project is not itself a model or animation. Its contents include model files, animation
 * files, layered image/PSD resources, game-data files, and other reviewed project entries;
 * documents are their currently available editor views.</p>
 */
public record ProjectSnapshot(
    String projectId,
    String name,
    Optional<Path> projectDirectory,
    List<ProjectContentSnapshot> contents,
    List<DocumentSnapshot> documents
) {
    public ProjectSnapshot {
        projectId = requireText(projectId, "projectId");
        name = requireText(name, "name");
        projectDirectory = Objects.requireNonNull(projectDirectory, "projectDirectory");
        contents = List.copyOf(Objects.requireNonNull(contents, "contents"));
        documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
        if (projectDirectory.isPresent() && projectDirectory.get().isAbsolute()) {
            throw new IllegalArgumentException("projectDirectory must be relative or absent");
        }
    }

    /** Legacy constructor for callers that have not yet separated file content from documents. */
    public ProjectSnapshot(
        final String projectId,
        final String name,
        final Optional<Path> projectDirectory,
        final List<DocumentSnapshot> documents
    ) {
        this(projectId, name, projectDirectory, List.of(), documents);
    }

    public Optional<ProjectContentSnapshot> content(final String contentId) {
        Objects.requireNonNull(contentId, "contentId");
        return contents.stream().filter(content -> content.contentId().equals(contentId)).findFirst();
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
