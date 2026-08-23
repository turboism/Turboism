package dev.turboism.adapter.cubism.performance;

import java.util.List;

/**
 * The verified instrumentation targets for each admitted Cubism build.
 *
 * <p>Cubism ships obfuscated, so every owner, name, and descriptor here was read off
 * the exact reviewed JAR and is valid only for that version - this project admits
 * 5.2.03 and 5.3.02 and nothing else. 5.2.03 carries only the renderScene target used
 * for the FPS counter; 5.3.02 carries the full metric set. Not instantiable.</p>
 */
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

    /**
     * @return the full 5.3.02 target set - render scene, modeling pre-render update,
     *     render system, scene traversal, renderer dispatch, model-instance update, and
     *     model-instance reinit. Valid only against the reviewed 5.3.02 artifact; on any
     *     other build the obfuscated names will simply not match and nothing is instrumented.
     */
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
