package dev.turboism.sdk.ui;

public record EmbeddedPanelContribution(
    String id,
    String title,
    String placement,
    int priority,
    PanelView content
) {
    public EmbeddedPanelContribution(
        final String id,
        final String title,
        final String placement,
        final int priority
    ) {
        this(
            id,
            title,
            placement,
            priority,
            PanelView.column(PanelView.text("Content is not available yet."))
        );
    }

    public EmbeddedPanelContribution {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be null or blank");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be null or blank");
        }
        if (placement == null || placement.isBlank()) {
            throw new IllegalArgumentException("placement must not be null or blank");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
    }
}
