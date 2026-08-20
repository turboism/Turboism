package dev.turboism.sdk.ui;

/**
 * Declares a runtime-rendered panel to embed in the host UI.
 *
 * @param id                 contribution identity used to activate the panel later, non-blank
 * @param title              panel tab or window title, non-blank
 * @param placement          host-surface placement hint, non-blank
 * @param priority           ordering weight among panels competing for the same placement
 * @param content            toolkit-neutral view tree the runtime renders
 * @param floatingByDefault  {@code true} to open the panel as a floating window rather than docked
 * @throws IllegalArgumentException when {@code id}, {@code title}, or
 *     {@code placement} is null or blank, or {@code content} is {@code null}
 */
public record EmbeddedPanelContribution(
    String id,
    String title,
    String placement,
    int priority,
    PanelView content,
    boolean floatingByDefault
) {
    public EmbeddedPanelContribution(
        final String id,
        final String title,
        final String placement,
        final int priority
    ) {
        this(id, title, placement, priority,
            PanelView.column(PanelView.text("Content is not available yet.")),
            false
        );
    }

    public EmbeddedPanelContribution(
        final String id,
        final String title,
        final String placement,
        final int priority,
        final PanelView content
    ) {
        this(id, title, placement, priority, content, false);
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
