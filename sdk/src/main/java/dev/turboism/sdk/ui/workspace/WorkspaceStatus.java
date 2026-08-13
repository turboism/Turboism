package dev.turboism.sdk.ui.workspace;

import dev.turboism.sdk.PreviewApi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

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

    @PreviewApi
    public enum Availability {
        AVAILABLE,
        UNAVAILABLE
    }
}
