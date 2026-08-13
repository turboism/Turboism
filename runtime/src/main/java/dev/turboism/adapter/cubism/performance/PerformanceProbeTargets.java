package dev.turboism.adapter.cubism.performance;

import java.util.List;

public final class PerformanceProbeTargets {

    private PerformanceProbeTargets() { }

    public static List<PerformanceProbeMethodTransformer.Target> cubism5302() {
        return List.of(
            target(
                "com/live2d/cubism/view/context/CEViewContext",
                "renderScene_exe",
                "(Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;Lcom/live2d/type/CRect;)V",
                PerformanceProbeMetric.RENDER_SCENE
            ),
            target(
                "com/live2d/cubism/view/context/K",
                "a",
                "(Lcom/live2d/graphics3d/a;)V",
                PerformanceProbeMetric.MODELING_PRE_RENDER_UPDATE
            ),
            target(
                "com/live2d/graphics3d/rendering/e",
                "b",
                "(Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;ZZ)V",
                PerformanceProbeMetric.RENDER_SYSTEM
            ),
            target(
                "com/live2d/graphics3d/rendering/e",
                "a",
                "(Lcom/live2d/graphics3d/entity/GEntity;ZZZLjava/util/ArrayList;)V",
                PerformanceProbeMetric.SCENE_TRAVERSAL
            ),
            target(
                "com/live2d/graphics3d/rendering/e",
                "a",
                "(Lcom/live2d/graphics3d/component/AGRenderer;Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;)V",
                PerformanceProbeMetric.RENDERER_DISPATCH
            ),
            target(
                "com/live2d/cubism/doc/model/CModelSource",
                "updateModelInstances",
                "()V",
                PerformanceProbeMetric.UPDATE_MODEL_INSTANCES
            ),
            target(
                "com/live2d/cubism/doc/model/CModel",
                "reinitModelInstance_exe",
                "()V",
                PerformanceProbeMetric.REINIT_MODEL_INSTANCE_EXE
            )
        );
    }

    private static PerformanceProbeMethodTransformer.Target target(
        final String owner,
        final String method,
        final String descriptor,
        final PerformanceProbeMetric metric
    ) {
        return new PerformanceProbeMethodTransformer.Target(owner, method, descriptor, metric);
    }
}
