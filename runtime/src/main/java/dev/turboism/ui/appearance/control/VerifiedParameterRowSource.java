package dev.turboism.ui.appearance.control;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Reads parameter rows and nested folder widgets through exact verified Editor selectors. */
public final class VerifiedParameterRowSource implements Supplier<Iterable<?>> {
    private static final String GROUP_LAYOUT = "cubism.editor-model.parameter-group-row.layout";
    private final VerifiedMemberResolver resolver;

    public VerifiedParameterRowSource(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public Iterable<?> get() {
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) return List.of();
        final List<Object> rows = new ArrayList<>();
        try {
            final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
            final Object frame = next("cubism.editor-model.app-controller.main-frame", app);
            final Object palette = next("cubism.editor-model.main-frame.parameter-palette", frame);
            final Object view = next("cubism.editor-model.parameter-palette.view", palette);
            final Object operation = next("cubism.editor-model.parameter-palette-view.operation", view);
            final Object parameters = next("cubism.editor-model.parameter-operation.rows", operation);
            if (parameters instanceof Iterable<?> iterable) iterable.forEach(rows::add);
            final Object layout = next("cubism.editor-model.parameter-operation.row-layout", operation);
            if (layout != null) collectLayoutRows(layout, rows);
        } catch (RuntimeException ignored) {
            // A document or palette may not be ready; a later appearance change retries.
        }
        return List.copyOf(rows);
    }

    private void collectLayoutRows(final Object root, final List<Object> rows) {
        final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        visited.addAll(rows);
        final ArrayDeque<Object> layouts = new ArrayDeque<>();
        final Set<Object> visitedLayouts = Collections.newSetFromMap(new IdentityHashMap<>());
        final String groupOwner =
                resolver.verifiedSelector(GROUP_LAYOUT).ownerInternalName().replace('/', '.');
        layouts.add(root);
        while (!layouts.isEmpty()) {
            final Object layout = layouts.removeFirst();
            if (!visitedLayouts.add(layout)) continue;
            final Object children = next("cubism.editor-model.parameter-row-container.children", layout);
            if (!(children instanceof Iterable<?> iterable)) continue;
            for (Object child : iterable) {
                if (child == null) continue;
                if (visited.add(child)) rows.add(child);
                if (child.getClass().getName().equals(groupOwner)) {
                    final Object nested = next(GROUP_LAYOUT, child);
                    if (nested != null) layouts.add(nested);
                }
            }
        }
    }

    private Object next(final String alias, final Object target) {
        return target == null ? null : resolver.invoke(alias, target);
    }
}
