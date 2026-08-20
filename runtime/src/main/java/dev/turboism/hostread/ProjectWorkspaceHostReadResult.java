package dev.turboism.hostread;

import dev.turboism.sdk.hostread.AsyncHostReadErrorCode;
import dev.turboism.sdk.hostread.ProjectWorkspaceSnapshot;

import java.util.Objects;
import java.util.Optional;

/** Closed runtime result; no adapter or throwable crosses into the async service. */
public record ProjectWorkspaceHostReadResult(
    Optional<ProjectWorkspaceSnapshot> value,
    Optional<AsyncHostReadErrorCode> errorCode
) {
    public ProjectWorkspaceHostReadResult {
        value = Objects.requireNonNull(value, "value");
        errorCode = Objects.requireNonNull(errorCode, "errorCode");
        if (value.isPresent() == errorCode.isPresent()) {
            throw new IllegalArgumentException("exactly one of value/errorCode must be present");
        }
    }

    /**
     * @param value snapshot read from the host
     * @return a successful result carrying the snapshot and no error code
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static ProjectWorkspaceHostReadResult available(
        final ProjectWorkspaceSnapshot value
    ) {
        return new ProjectWorkspaceHostReadResult(
            Optional.of(Objects.requireNonNull(value, "value")),
            Optional.empty()
        );
    }

    /**
     * @param errorCode why the read could not produce a snapshot
     * @return a failed result carrying only the code; the underlying throwable,
     *     if any, deliberately does not cross this boundary
     * @throws NullPointerException if {@code errorCode} is {@code null}
     */
    public static ProjectWorkspaceHostReadResult failed(
        final AsyncHostReadErrorCode errorCode
    ) {
        return new ProjectWorkspaceHostReadResult(
            Optional.empty(),
            Optional.of(Objects.requireNonNull(errorCode, "errorCode"))
        );
    }
}
