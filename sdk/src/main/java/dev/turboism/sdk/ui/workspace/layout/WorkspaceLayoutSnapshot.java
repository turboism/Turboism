package dev.turboism.sdk.ui.workspace.layout;


import java.util.Objects;
import java.util.Optional;

/**
 * Immutable snapshot of the current workspace dock layout.
 *
 * <p>{@code availability} is {@link Availability#AVAILABLE} only when the whole read path
 * resolved against the verified host chain; any broken link, missing host state, or failed
 * mapping yields {@link Availability#UNAVAILABLE} with a {@code diagnosticCode} instead of a
 * partial or guessed tree. An {@code AVAILABLE} snapshot may still carry an empty
 * {@code root} when the host tree contains no dock components (for example a canvas-only
 * workspace).</p>
 */
public record WorkspaceLayoutSnapshot(
    Availability availability,
    Optional<DockComponent> root,
    Optional<String> diagnosticCode
) {

    public WorkspaceLayoutSnapshot {
        availability = Objects.requireNonNull(availability, "availability");
        root = Objects.requireNonNull(root, "root");
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

    public enum Availability {
        AVAILABLE,
        UNAVAILABLE
    }
}
