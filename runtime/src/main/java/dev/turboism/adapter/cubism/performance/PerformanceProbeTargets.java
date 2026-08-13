package dev.turboism.adapter.cubism.performance;

import java.util.List;

public final class PerformanceProbeTargets {

    private PerformanceProbeTargets() { }

    /**
     * Cubism 5.2.03 RENDER_SCENE target. Verified against the exact reviewed
     * 5.2.03 JAR (size 40,805,584 / sha256 bcc6e34f...) with {@code javap -p -s}
     * on {@code com/live2d/cubism/view/context/CEViewContext}; see
     * {@code cubism-ref/verification/cubism-5.2.03-performance-render-scene.json}.
     * The FPS hook counts render calls only, so this slice carries no other metric.
     */
    public static List<PerformanceProbeMethodTransformer.Target> cubism5203() {
        return List.of(
            target(
                "com/live2d/cubism/view/context/CEViewContext",
                "renderScene_exe",
                "(Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;Lcom/live2d/type/CRect;)V",
                PerformanceProbeMetric.RENDER_SCENE
            )
        );
    }

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
