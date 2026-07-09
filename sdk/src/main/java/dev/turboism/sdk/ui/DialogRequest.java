package dev.turboism.sdk.ui;

public record DialogRequest(String id, String title, String body) {
    public DialogRequest {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be null or blank");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be null or blank");
        }
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
    }
}
