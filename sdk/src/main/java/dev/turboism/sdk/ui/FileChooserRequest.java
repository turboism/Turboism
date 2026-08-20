package dev.turboism.sdk.ui;

import java.util.List;

/**
 * Descriptor for a host file-selection dialog.
 *
 * @param id                caller-chosen request identity, non-blank
 * @param title             chooser window title, non-blank
 * @param allowedExtensions defensively copied, immutable list of acceptable
 *                          file extensions; empty means no extension filter
 * @throws IllegalArgumentException when {@code id} or {@code title} is null or blank
 * @throws NullPointerException when {@code allowedExtensions} is {@code null}
 */
public record FileChooserRequest(String id, String title, List<String> allowedExtensions) {
    public FileChooserRequest {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be null or blank");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be null or blank");
        }
        allowedExtensions = List.copyOf(allowedExtensions);
    }
}
