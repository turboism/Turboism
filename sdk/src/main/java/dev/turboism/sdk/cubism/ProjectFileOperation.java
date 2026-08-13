package dev.turboism.sdk.cubism;

import java.util.Objects;
import java.util.Optional;

/** Immutable request observed before a model or animation file-content operation. */
public record ProjectFileOperation(
    ProjectContentKind kind,
    ProjectFileOperationType operation,
    Optional<String> contentId,
    String displayName,
    Optional<String> fileName
) {
    public ProjectFileOperation {
        kind = Objects.requireNonNull(kind, "kind");
        if (kind != ProjectContentKind.MODEL && kind != ProjectContentKind.ANIMATION) {
            throw new IllegalArgumentException(
                "Project file lifecycle supports MODEL and ANIMATION content only"
            );
        }
        operation = Objects.requireNonNull(operation, "operation");
        contentId = Objects.requireNonNull(contentId, "contentId");
        displayName = Objects.requireNonNull(displayName, "displayName");
        fileName = Objects.requireNonNull(fileName, "fileName");
        if (displayName.isBlank()) displayName = "Untitled";
        contentId.ifPresent(value -> requireText(value, "contentId"));
        fileName.ifPresent(value -> requireText(value, "fileName"));
    }

    private static void requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
