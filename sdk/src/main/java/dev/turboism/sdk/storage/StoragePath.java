package dev.turboism.sdk.storage;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A location inside one of the plugin's granted storage roots.
 *
 * <p>The relative path is validated at construction to be portable and
 * already normalized: it must not be blank, absolute, home-relative,
 * contain a backslash, look like a URI or drive specifier, contain empty,
 * {@code .} or {@code ..} segments, or contain control characters.
 * Escaping the root is therefore impossible by construction rather than by
 * a later check.</p>
 *
 * @param root sandbox root the path is resolved against
 * @param relativePath forward-slash separated path below that root
 */
public record StoragePath(StorageRoot root, String relativePath) {

    private static final Pattern URI_OR_DRIVE = Pattern.compile(
        "^[A-Za-z][A-Za-z0-9+.-]*:.*"
    );

    public StoragePath {
        root = Objects.requireNonNull(root, "root");
        relativePath = Objects.requireNonNull(relativePath, "relativePath");
        if (relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
        if (relativePath.startsWith("/")
            || relativePath.startsWith("~")
            || relativePath.indexOf('\\') >= 0
            || URI_OR_DRIVE.matcher(relativePath).matches()) {
            throw invalid();
        }
        final String[] segments = relativePath.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw invalid();
            }
            for (int index = 0; index < segment.length(); index++) {
                final char value = segment.charAt(index);
                if (Character.isISOControl(value)) {
                    throw invalid();
                }
            }
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
            "relativePath must be normalized portable relative text"
        );
    }
}
