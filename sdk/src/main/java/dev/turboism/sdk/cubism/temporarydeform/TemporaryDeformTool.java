package dev.turboism.sdk.cubism.temporarydeform;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.ui.BoundingBoxOverlayButton;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Plugin-defined custom Temporary Deform Tool integrated into Cubism's Modeling menu,
 * Tool Details deform-method dropdown, and optional bounding-box overlay button.
 */
@CubismEditor(from = "5.0.00")
public interface TemporaryDeformTool {

    /** Returns the plugin-local unique identifier for this tool. */
    String id();

    /** Returns the user-visible localized label shown in menus and Tool Details. */
    String label();

    /** Returns the ascending display order across custom temporary deform tools. */
    default int order() {
        return 0;
    }

    /** Returns an optional bounding-box overlay button that enters this temporary deform tool. */
    default Optional<OverlayButtonSpec> overlayButton() {
        return Optional.empty();
    }

    /** Returns the current Tool Details option descriptors displayed while this tool is active. */
    default List<TemporaryDeformOption> options() {
        return List.of();
    }

    /** Notifies the tool when the operator updates a Tool Details option on the EDT. */
    default void onOptionChanged(final String optionId, final String value) {}

    /** Creates an active session bound to the current temporary deform target selection. */
    TemporaryDeformSession createSession(TemporaryDeformContext context);

    /**
     * Bounding-box overlay button metadata paired with a custom temporary deform tool.
     *
     * @param tooltip hover tooltip text
     * @param icons icon resource paths for normal/hover/pressed/disabled states
     * @param order ascending display order on the bounding-box overlay
     * @param targetKinds bounding-box target kinds that display this button
     */
    @CubismEditor(from = "5.0.00")
    record OverlayButtonSpec(
            String tooltip,
            BoundingBoxOverlayButton.IconVariants icons,
            int order,
            Set<BoundingBoxOverlayButton.TargetKind> targetKinds) {
        public OverlayButtonSpec {
            Objects.requireNonNull(tooltip, "tooltip");
            if (tooltip.isBlank()) {
                throw new IllegalArgumentException("tooltip must not be blank");
            }
            icons = Objects.requireNonNull(icons, "icons");
            targetKinds = Set.copyOf(Objects.requireNonNull(targetKinds, "targetKinds"));
        }
    }
}
