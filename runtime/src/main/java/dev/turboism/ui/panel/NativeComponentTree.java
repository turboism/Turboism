package dev.turboism.ui.panel;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

/**
 * Flattened view of a native host component tree.
 *
 * <p>A themed host operation walks one host subtree at a time. Every walker must agree on the same
 * traversal order and on which components this runtime owns. A runtime-owned component carries its
 * own appearance. A walker that repainted it would bring back the text background blocks of a
 * rendered panel.</p>
 */
final class NativeComponentTree {

    /** Package prefix of the components this runtime creates. */
    private static final String RUNTIME_PACKAGE_PREFIX = "dev.turboism.";

    private NativeComponentTree() {}

    /**
     * Lists one component and every descendant in depth-first order.
     *
     * @param root the tree root; not {@code null}
     * @return the root followed by its descendants
     */
    static List<Component> flatten(final Component root) {
        final List<Component> components = new ArrayList<>();
        collect(root, components);
        return components;
    }

    /**
     * Reports whether this runtime creates the component.
     *
     * <p>A runtime-owned component already carries its themed or transparent appearance, so a
     * native host walker must leave it untouched.</p>
     *
     * @param component the component to classify; not {@code null}
     * @return {@code true} for a component of this runtime
     */
    static boolean isRuntimeOwned(final Component component) {
        return component.getClass().getName().startsWith(RUNTIME_PACKAGE_PREFIX);
    }

    private static void collect(final Component component, final List<Component> components) {
        components.add(component);
        if (!(component instanceof Container container)) return;
        for (Component child : container.getComponents()) collect(child, components);
    }
}
