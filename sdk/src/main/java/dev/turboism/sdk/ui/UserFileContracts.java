package dev.turboism.sdk.ui;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

final class UserFileContracts {

    private static final int MAX_ID_LENGTH = 128;
    private static final int MAX_TITLE_LENGTH = 256;
    private static final int MAX_EXTENSION_LENGTH = 32;
    private static final Pattern EXTENSION = Pattern.compile(
        "[A-Za-z0-9][A-Za-z0-9+_-]{0," + (MAX_EXTENSION_LENGTH - 1) + "}"
    );

    private UserFileContracts() {
    }

    static String requireText(
        final String value,
        final String name,
        final int maximumLength
    ) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds " + maximumLength + " characters");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(name + " contains a control character");
            }
        }
        return value;
    }

    static String requireId(final String value) {
        return requireText(value, "id", MAX_ID_LENGTH);
    }

    static String requireTitle(final String value) {
        return requireText(value, "title", MAX_TITLE_LENGTH);
    }

    static List<String> extensions(final List<String> extensions) {
        final List<String> snapshot = List.copyOf(
            Objects.requireNonNull(extensions, "allowedExtensions")
        );
        for (String extension : snapshot) {
            if (extension == null || !EXTENSION.matcher(extension).matches()) {
                throw new IllegalArgumentException("allowed extension is invalid");
            }
        }
        return snapshot;
    }

    static <T> Optional<T> optional(final Optional<T> value, final String name) {
        return Objects.requireNonNull(value, name);
    }
}
