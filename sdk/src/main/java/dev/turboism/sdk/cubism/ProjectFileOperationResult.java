package dev.turboism.sdk.cubism;

import java.util.Objects;
import java.util.Optional;

/** Completion result delivered by the after phase of a model or animation file operation. */
public record ProjectFileOperationResult(
    ProjectFileOperation request,
    Optional<ProjectContentSnapshot> content,
    boolean succeeded,
    Optional<String> failureType
) {
    public ProjectFileOperationResult {
        request = Objects.requireNonNull(request, "request");
        content = Objects.requireNonNull(content, "content");
        failureType = Objects.requireNonNull(failureType, "failureType");
        if (succeeded && failureType.isPresent()) {
            throw new IllegalArgumentException("successful operations cannot have failureType");
        }
    }

    public static ProjectFileOperationResult succeeded(
        final ProjectFileOperation request,
        final ProjectContentSnapshot content
    ) {
        return new ProjectFileOperationResult(
            request,
            Optional.of(Objects.requireNonNull(content, "content")),
            true,
            Optional.empty()
        );
    }

    public static ProjectFileOperationResult failed(
        final ProjectFileOperation request,
        final ProjectContentSnapshot content,
        final Throwable failure
    ) {
        return new ProjectFileOperationResult(
            request,
            Optional.ofNullable(content),
            false,
            Optional.of(Objects.requireNonNull(failure, "failure").getClass().getName())
        );
    }

    public static ProjectFileOperationResult rejected(
        final ProjectFileOperation request,
        final ProjectContentSnapshot content
    ) {
        return new ProjectFileOperationResult(
            request,
            Optional.ofNullable(content),
            false,
            Optional.empty()
        );
    }
}
