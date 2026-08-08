package dev.turboism.sdk.ui;

import java.util.Objects;

/**
 * Injects a collapsible section into an existing embedded panel, including the
 * Turboism tab {@code turboism.panel.main}.
 *
 * <p>Injected sections ("B") are appended after the target panel's declared
 * content ("A") during render synthesis; B sections are ordered by
 * {@code order} ascending, then {@code sectionId} lexicographically, regardless
 * of plugin load order.</p>
 */
public record CollapsibleSectionContribution(
    EmbeddedPanelId targetPanelId,
    String sectionId,
    String title,
    int order,
    boolean expandedByDefault,
    PanelView content
) {

    public CollapsibleSectionContribution {
        Objects.requireNonNull(targetPanelId, "targetPanelId");
        sectionId = requireText(sectionId, "sectionId");
        title = requireText(title, "title");
        if (order < 0) {
            throw new IllegalArgumentException("order must not be negative");
        }
        content = Objects.requireNonNull(content, "content");
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
