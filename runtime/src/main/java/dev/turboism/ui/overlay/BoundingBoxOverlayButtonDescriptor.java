package dev.turboism.ui.overlay;

import dev.turboism.sdk.ui.BoundingBoxOverlayButton;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.host.EditorUiFamily;

import java.util.Objects;

/** Runtime-owned descriptor carrying the contribution owner into host adaptation. */
public record BoundingBoxOverlayButtonDescriptor(
    String pluginId,
    BoundingBoxOverlayButton button
) {
    public BoundingBoxOverlayButtonDescriptor {
        pluginId = requireText(pluginId, "pluginId");
        button = Objects.requireNonNull(button, "button");
    }

    public static BoundingBoxOverlayButtonDescriptor from(
        final EditorUiContribution<?> contribution
    ) {
        Objects.requireNonNull(contribution, "contribution");
        if (contribution.identity().family() != EditorUiFamily.BOUNDING_BOX_OVERLAY_BUTTON
            || !(contribution.descriptor() instanceof BoundingBoxOverlayButton button)) {
            throw new IllegalArgumentException(
                "bounding-box overlay provider received an incompatible contribution"
            );
        }
        return new BoundingBoxOverlayButtonDescriptor(
            contribution.identity().pluginId(),
            button
        );
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
