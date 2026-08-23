package dev.turboism.sdk.ui.workspace;

import dev.turboism.sdk.PreviewApi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A snapshot of the host's workspace state at the moment it was read.
 *
 * <p>The {@code available} list is defensively copied into an immutable list at construction, so
 * the snapshot cannot change under a caller that holds it. When {@code availability} is
 * {@link Availability#UNAVAILABLE} the host could not be queried and {@code current} and
 * {@code available} carry no information. A present {@code diagnosticCode} is never blank.
 *
 * @param availability   whether the host answered the workspace query at all
 * @param current        the workspace currently applied, empty when unknown or unavailable
 * @param available      every workspace the host offers, immutable and possibly empty
 * @param diagnosticCode stable machine-readable reason code, empty when there is nothing to report
 */
@PreviewApi
public record WorkspaceStatus(
    Availability availability,
    Optional<WorkspaceInfo> current,
    List<WorkspaceInfo> available,
    Optional<String> diagnosticCode
) {
    public WorkspaceStatus {
        availability = Objects.requireNonNull(availability, "availability");
        current = Objects.requireNonNull(current, "current");
        available = List.copyOf(Objects.requireNonNull(available, "available"));
        diagnosticCode = text(diagnosticCode, "diagnosticCode");
    }

    private static Optional<String> text(final Optional<String> value, final String name) {
        Objects.requireNonNull(value, name);
        return value.map(text -> {
            if (text.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return text;
        });
    }

    /**
     * Whether the host answered the workspace query.
     */
    @PreviewApi
    public enum Availability {
        AVAILABLE,
        UNAVAILABLE
    }
}
