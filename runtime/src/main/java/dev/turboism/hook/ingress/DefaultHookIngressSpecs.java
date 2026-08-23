package dev.turboism.hook.ingress;

import java.util.List;

/**
 * The built-in set of semantic hook ingress definitions the runtime ships with.
 *
 * <p>Every entry is production-disabled and declared {@code enqueue-only}: the
 * ingress may hand a normalized SDK event to the mailbox and nothing more. This
 * holder is not instantiable.</p>
 */
public final class DefaultHookIngressSpecs {

    public static final List<HookIngressSpec> DEFAULT_SPECS = List.of(
        new HookIngressSpec("hook-ingress.project.lifecycle", "event.project.lifecycle", false, "enqueue-only"),
        new HookIngressSpec("hook-ingress.selection.changed", "event.selection.changed", false, "enqueue-only"),
        new HookIngressSpec("hook-ingress.context-menu.opening", "ui.context-source.read", false, "enqueue-only"),
        new HookIngressSpec("hook-ingress.texture-atlas.reinit", "event.texture-atlas.reinit", false, "enqueue-only"),
        new HookIngressSpec("hook-ingress.viewport.overlay.lifecycle", "ui.overlay.contribute", false, "enqueue-only"),
        new HookIngressSpec("hook-ingress.render.status", "event.render.status.changed", false, "enqueue-only"),
        new HookIngressSpec("hook-ingress.model.tree.changed", "cubism.model-tree.read", false, "enqueue-only"),
        new HookIngressSpec("hook-ingress.parameter.changed", "cubism.parameter.read", false, "enqueue-only")
    );

    private DefaultHookIngressSpecs() {
    }
}
