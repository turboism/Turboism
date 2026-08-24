package dev.turboism.sdk.script;


import java.util.List;
import java.util.Objects;

/** Immutable public metadata for one installed script. */
public record ScriptDescriptor(
    ScriptId id,
    String name,
    String version,
    ScriptLanguage language,
    String entry,
    List<String> permissions
) {

    public ScriptDescriptor {
        id = Objects.requireNonNull(id, "id");
        name = requireText(name, "name", 256);
        version = requireText(version, "version", 128);
        language = Objects.requireNonNull(language, "language");
        entry = requireText(entry, "entry", 512);
        permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
    }

    private static String requireText(final String value, final String field, final int max) {
        final String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty() || normalized.length() > max) {
            throw new IllegalArgumentException(field + " must contain 1-" + max + " characters");
        }
        return normalized;
    }
}
