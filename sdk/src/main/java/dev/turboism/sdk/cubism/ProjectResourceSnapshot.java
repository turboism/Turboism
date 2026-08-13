package dev.turboism.sdk.cubism;

import java.util.Objects;
import java.util.Optional;

/** A non-document asset referenced by one project content entry. */
public record ProjectResourceSnapshot(
    String resourceId,
    String name,
    ResourceKind kind,
    Optional<String> relativePath
) {
    public ProjectResourceSnapshot {
        resourceId = requireText(resourceId, "resourceId");
        name = requireText(name, "name");
        kind = Objects.requireNonNull(kind, "kind");
        relativePath = Objects.requireNonNull(relativePath, "relativePath");
        relativePath.ifPresent(value -> requireRelativePath(value, "relativePath"));
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static void requireRelativePath(final String value, final String name) {
        final String normalized = requireText(value, name).replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")) {
            throw new IllegalArgumentException(name + " must be relative without parent segments");
        }
    }
}
