package dev.turboism.sdk.ui;

import java.util.List;

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
