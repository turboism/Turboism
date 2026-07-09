package dev.turboism.sdk.ui;

public record StatusNotification(String id, String severity, String message) {
    public StatusNotification {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be null or blank");
        }
        if (!"INFO".equals(severity) && !"WARNING".equals(severity) && !"ERROR".equals(severity)) {
            throw new IllegalArgumentException("severity must be INFO, WARNING, or ERROR");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be null or blank");
        }
    }
}
