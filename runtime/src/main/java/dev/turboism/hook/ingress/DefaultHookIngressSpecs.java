package dev.turboism.hook.ingress;

import java.util.List;

public final class DefaultHookIngressSpecs {

    public static final List<HookIngressSpec> M12_DEFAULT_SPECS = List.of(
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
