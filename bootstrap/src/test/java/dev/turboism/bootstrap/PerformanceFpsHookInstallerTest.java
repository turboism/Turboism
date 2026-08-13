package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.performance.PerformanceProbeMetric;
import dev.turboism.adapter.cubism.performance.PerformanceProbeMethodTransformer;
import dev.turboism.adapter.cubism.performance.PerformanceProbeTargets;
import dev.turboism.mapping.verification.HostArtifactDigest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FPS counting digest admission and exact-version target routing. Digest
 * routing is the reviewed-constants seam the constructor delegates to (the
 * same {@code forArtifact} idiom as {@code MeshMirrorHostProfile} and
 * {@code EditorModelVerificationManifest}); a file-based accept test cannot
 * fabricate a SHA-256 preimage, so the constructor's reject path is exercised
 * with a real fake artifact file.
 */
final class PerformanceFpsHookInstallerTest {

    private static final long CUBISM_5203_SIZE = 40_805_584L;
    private static final String CUBISM_5203_SHA256 =
        "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd";
    private static final long CUBISM_5302_SIZE = 41_922_739L;
    private static final String CUBISM_5302_SHA256 =
        "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21";

    @TempDir
    Path temporary;

    @Test
    void acceptsReviewed5203DigestAndSelectsExactRenderSceneTarget() {
        final List<PerformanceProbeMethodTransformer.Target> targets =
            PerformanceFpsHookInstaller.fpsTargetsFor(
                new HostArtifactDigest(CUBISM_5203_SIZE, CUBISM_5203_SHA256)
            );

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
    void acceptsReviewed5302DigestRegression() {
        final List<PerformanceProbeMethodTransformer.Target> expected = PerformanceProbeTargets
            .cubism5302().stream()
            .filter(target -> target.metric() == PerformanceProbeMetric.RENDER_SCENE)
            .toList();
        final List<PerformanceProbeMethodTransformer.Target> targets =
            PerformanceFpsHookInstaller.fpsTargetsFor(
                new HostArtifactDigest(CUBISM_5302_SIZE, CUBISM_5302_SHA256)
            );

        assertEquals(expected, targets);
        assertEquals(1, targets.size());
        assertEquals("com/live2d/cubism/view/context/CEViewContext", targets.get(0).ownerInternalName());
        assertEquals("renderScene_exe", targets.get(0).methodName());
        assertEquals(
            "(Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;Lcom/live2d/type/CRect;)V",
            targets.get(0).descriptor()
        );
    }

    @Test
    void rejectsUnknownDigest() {
        assertThrows(
            IllegalArgumentException.class,
            () -> PerformanceFpsHookInstaller.fpsTargetsFor(
                new HostArtifactDigest(1_234L, "a".repeat(64))
            )
        );
    }

    @Test
    void constructorRejectsFakeArtifactFile() throws Exception {
        final Path fakeArtifact = temporary.resolve("fake-artifact.jar");
        Files.write(fakeArtifact, new byte[] {1, 2, 3, 4, 5});

        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, arguments) -> defaultValue(method.getReturnType())
        );

        final IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> new PerformanceFpsHookInstaller(
                instrumentation, fakeArtifact, getClass().getClassLoader())
        );
        final HostArtifactDigest fakeDigest = HostArtifactDigest.from(fakeArtifact);
        assertEquals(
            "unsupported Cubism artifact for FPS counting"
                + " (expected Cubism 5.2.03 or 5.3.02; got size=" + fakeDigest.size()
                + " sha256=" + fakeDigest.sha256() + ")",
            failure.getMessage()
        );
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        return null;
    }
}
