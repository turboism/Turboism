package dev.turboism.adapter.cubism.mesh;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.util.Objects;

/** Reads native component-space button bounds without invoking native actions. */
public final class NativeMeshControls {
    private NativeMeshControls() {}

    /** Tests verified, enabled native controls at the supplied canvas-component position. */
    public static boolean contains(final VerifiedMemberResolver resolver, final Object view, final int x, final int y) {
        final Object scene = Objects.requireNonNull(
                resolver.invoke(MeshToolSessionSelectorContract.VIEW_SCENE_GRAPH, view), "native scene graph");
        final Object root = Objects.requireNonNull(
                resolver.invoke(MeshToolSessionSelectorContract.SCENE_COMPONENT_OBJECTS, scene),
                "native component objects");
        final Object entities = resolver.invoke(MeshToolSessionSelectorContract.ENTITY_TRAVERSE_ALL, root);
        if (!(entities instanceof Iterable<?> iterable)) {
            throw new IllegalStateException("native component traversal is unavailable");
        }
        final Object point = resolver.construct(MeshToolSessionSelectorContract.VECTOR_CREATE, (float) x, (float) y);
        for (Object entity : iterable) {
            if (!isComponentButton(resolver, entity)
                    || !Boolean.TRUE.equals(
                            resolver.invoke(MeshToolSessionSelectorContract.ENTITY_ENABLED_IN_HIERARCHY, entity))) {
                continue;
            }
            final Object bounds = Objects.requireNonNull(
                    resolver.invoke(MeshToolSessionSelectorContract.GUI_BUTTON_COMPONENT_BOUNDS, entity),
                    "native button bounds");
            // This is the same component-space hit test, including the native default tolerance,
            // used by both native button families. Bounds are re-read after every layout change.
            if (Boolean.TRUE.equals(resolver.invokeStatic(
                    MeshToolSessionSelectorContract.GUI_BOUNDS_CONTAINS, bounds, point, 0.0f, 2, null))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isComponentButton(final VerifiedMemberResolver resolver, final Object entity) {
        // Mesh-editor confirmation/cancel icons use a separate component-only hierarchy.
        return resolver.isInstance(MeshToolSessionSelectorContract.GUI_ICON_BUTTON_CLASS, entity)
                || (resolver.isInstance(MeshToolSessionSelectorContract.GUI_BUTTON_CLASS, entity)
                        && Boolean.TRUE.equals(
                                resolver.invoke(MeshToolSessionSelectorContract.GUI_BUTTON_ON_COMPONENT, entity)));
    }
}
