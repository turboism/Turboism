package dev.turboism.adapter.cubism.performance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exact-version FPS counting target pins. The 5.2.03 RENDER_SCENE entry was
 * established from the exact reviewed 5.2.03 JAR bytecode (see
 * {@code cubism-ref/verification/cubism-5.2.03-performance-render-scene.json});
 * the 5.3.02 set stays untouched (regression pin).
 */
class PerformanceFpsTargetsTest {

    @Test
    void cubism5203CarriesOnlyTheVerifiedRenderSceneTarget() {
        final List<PerformanceProbeMethodTransformer.Target> targets =
            PerformanceProbeTargets.cubism5203();

        assertEquals(1, targets.size());
        final PerformanceProbeMethodTransformer.Target target = targets.get(0);
        assertEquals("com/live2d/cubism/view/context/CEViewContext", target.ownerInternalName());
        assertEquals("renderScene_exe", target.methodName());
        assertEquals(
            "(Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;Lcom/live2d/type/CRect;)V",
            target.descriptor()
        );
        assertEquals(PerformanceProbeMetric.RENDER_SCENE, target.metric());
    }

    @Test
    void cubism5302SetIsUnchanged() {
        final List<PerformanceProbeMethodTransformer.Target> targets =
            PerformanceProbeTargets.cubism5302();

        assertEquals(7, targets.size());
        final PerformanceProbeMethodTransformer.Target renderScene = targets.get(0);
        assertEquals("com/live2d/cubism/view/context/CEViewContext", renderScene.ownerInternalName());
        assertEquals("renderScene_exe", renderScene.methodName());
        assertEquals(
            "(Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;Lcom/live2d/type/CRect;)V",
            renderScene.descriptor()
        );
        assertEquals(PerformanceProbeMetric.RENDER_SCENE, renderScene.metric());
    }
}
