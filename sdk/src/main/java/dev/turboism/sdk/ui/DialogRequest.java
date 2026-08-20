package dev.turboism.sdk.ui;

/**
 * Descriptor for a simple runtime-rendered message or confirmation dialog.
 *
 * <p>Carries no host widgets: the runtime owns rendering and threading.</p>
 *
 * @param id    caller-chosen dialog identity, non-blank
 * @param title dialog window title, non-blank
 * @param body  dialog message text; may be empty but never {@code null}
 * @throws IllegalArgumentException when {@code id} or {@code title} is null or
 *     blank, or {@code body} is {@code null}
 */
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
