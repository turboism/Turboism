package dev.turboism.sdk.ui.workspace.layout;


import java.util.List;
import java.util.Objects;

/**
 * A split container branch of the dock layout tree. Child order follows the host
 * {@code CPMSplitContainer} contents order.
 */
public record SplitDock(List<DockComponent> children) implements DockComponent {

    public SplitDock {
        children = List.copyOf(Objects.requireNonNull(children, "children"));
    }
}
