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

    /**
     * Result for an operation the host carried through to completion.
     *
     * @param request the operation being reported on
     * @param content the resulting project content; required, since a successful operation always
     *     produced one
     * @return a result with no failure type
     * @throws NullPointerException if {@code request} or {@code content} is null
     */
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

    /**
     * Result for an operation that threw. Only the throwable's class name is retained — never its
     * message or stack trace — so host paths and user data cannot leak to plugins through a result.
     *
     * @param request the operation being reported on
     * @param content content the host had produced before failing; may be null, recorded as absent
     * @param failure the throwable that ended the operation
     * @return an unsuccessful result whose failure type is the throwable's fully qualified class name
     * @throws NullPointerException if {@code request} or {@code failure} is null
     */
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

    /**
     * Result for an operation the host declined to perform. Distinguished from
     * {@link #failed(ProjectFileOperation, ProjectContentSnapshot, Throwable)} by carrying no
     * failure type: nothing went wrong, the operation simply was not permitted or not applicable.
     *
     * @param request the operation being reported on
     * @param content content associated with the request; may be null, recorded as absent
     * @return an unsuccessful result with an empty failure type
     * @throws NullPointerException if {@code request} is null
     */
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
