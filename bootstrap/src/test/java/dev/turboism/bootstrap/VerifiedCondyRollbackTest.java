package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.optimization.camera.CameraMatrixCacheBridge;
import dev.turboism.adapter.cubism.optimization.deferred.DeferredGlErrorCheckTransformer;
import dev.turboism.adapter.cubism.optimization.meshsetup.SkippedFrameMeshSetupElisionBridge;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge;
import dev.turboism.config.RuntimeStartupConfig;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies multi-cycle installer publication before retransformation, close rollback, and
 * reinstallation across reviewed host installers in {@code :bootstrap:test}.
 */
final class VerifiedCondyRollbackTest {

    @AfterEach
    void clearProperties() {
        final Properties properties = System.getProperties();
        properties.remove(UniformLocationHookBridge.DEFER_ACTIVE_PROPERTY);
        properties.remove(UniformLocationHookBridge.DEFER_QUERY_PROPERTY);
        properties.remove(UniformLocationHookBridge.DEFER_REPORT_PROPERTY);
        properties.remove(UniformLocationHookBridge.ENABLE_PROPERTY);
        properties.remove(DeferredGlErrorCheckTransformer.ENABLE_PROPERTY);
        properties.remove(CameraMatrixCacheBridge.GET_PROPERTY);
        properties.remove(SkippedFrameMeshSetupElisionBridge.PREDICATE_PROPERTY);
    }

    @Test
    void deferredCameraAndMeshSetupInstallersPublishBeforeRetransformAndRestoreAcrossCycles() throws Exception {
        final String configuredJar = System.getProperty("turboism.test.cubism5305Jar", "");
        final Path artifact = !configuredJar.isBlank()
                ? Path.of(configuredJar)
                : Path.of(
                        System.getProperty("user.home"),
                        ".local",
                        "share",
                        "cubism-reference",
                        "5.3.05",
                        "proton",
                        "pfx",
                        "drive_c",
                        "Program Files",
                        "Live2D Cubism 5.3",
                        "app",
                        "lib",
                        "Live2D_Cubism.jar");
        final Path libDir = artifact.getParent();
        assumeTrue(Files.isRegularFile(artifact) && libDir != null, "Cubism 5.3.05 reference lib directory absent");

        final List<URL> urls = new ArrayList<>();
        try (var jars = Files.walk(libDir, 2)) {
            for (final Path jar :
                    jars.filter(p -> p.toString().endsWith(".jar")).toList()) {
                urls.add(jar.toUri().toURL());
            }
        }
        final RuntimeStartupConfig config = new RuntimeStartupConfig(false, false, false, false);
        try (URLClassLoader loader =
                new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            final RetransformRecorder recorder = new RetransformRecorder();
            final InstallContext context = new InstallContext(
                    recorder.instrumentation,
                    artifact,
                    loader,
                    config,
                    Runtime.version().feature());

            for (int cycle = 0; cycle < 2; cycle++) {
                recorder.resetSnapshots();
                try (var uniform = new VerifiedUniformLocationInstaller(context);
                        var deferred = new VerifiedDeferredGlErrorCheckInstaller(context, false);
                        var camera = new VerifiedCameraMatrixCacheInstaller(context);
                        var meshSetup = new VerifiedSkippedFrameMeshSetupElisionInstaller(context);
                        var sorting = new VerifiedSortingInfoCacheInstaller(context)) {
                    uniform.install();
                    assertTrue(
                            System.getProperties().get(UniformLocationHookBridge.DEFER_ACTIVE_PROPERTY)
                                    instanceof AtomicBoolean);
                    deferred.install();
                    camera.install();
                    meshSetup.install();
                    sorting.install();

                    assertFalse(recorder.rewritten.isEmpty(), "cycle " + cycle + " must retransform target classes");
                    assertNotNull(
                            recorder.observedAtFirstRetransform.get(UniformLocationHookBridge.DEFER_ACTIVE_PROPERTY));
                    assertNotNull(recorder.observedAtFirstRetransform.get(CameraMatrixCacheBridge.GET_PROPERTY));
                    assertNotNull(recorder.observedAtFirstRetransform.get(
                            SkippedFrameMeshSetupElisionBridge.PREDICATE_PROPERTY));

                    sorting.close();
                    meshSetup.close();
                    camera.close();
                    deferred.close();
                    uniform.close();
                    assertTrue(deferred.restored());
                    assertTrue(camera.restored());
                    assertTrue(meshSetup.restored());
                    assertTrue(sorting.restored());
                    assertTrue(uniform.restored());
                }
                recorder.assertAllRestored();
                assertNull(System.getProperties().get(UniformLocationHookBridge.DEFER_ACTIVE_PROPERTY));
                assertNull(System.getProperties().get(CameraMatrixCacheBridge.GET_PROPERTY));
                assertNull(System.getProperties().get(SkippedFrameMeshSetupElisionBridge.PREDICATE_PROPERTY));
            }
        }
    }

    private static final class RetransformRecorder {
        private final List<ClassFileTransformer> transformers = new ArrayList<>();
        private final Map<Class<?>, byte[]> original = new LinkedHashMap<>();
        private final Map<Class<?>, byte[]> current = new LinkedHashMap<>();
        private final List<Class<?>> rewritten = new ArrayList<>();
        private final Map<String, Object> observedAtFirstRetransform = new LinkedHashMap<>();
        private final Instrumentation instrumentation;

        RetransformRecorder() {
            this.instrumentation = (Instrumentation) Proxy.newProxyInstance(
                    RetransformRecorder.class.getClassLoader(),
                    new Class<?>[] {Instrumentation.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "isRetransformClassesSupported", "isModifiableClass" -> true;
                        case "getAllLoadedClasses" -> new Class<?>[0];
                        case "addTransformer" -> {
                            transformers.add((ClassFileTransformer) args[0]);
                            yield null;
                        }
                        case "removeTransformer" -> transformers.remove(args[0]);
                        case "retransformClasses" -> {
                            for (final Class<?> type : (Class<?>[]) args[0]) {
                                retransform(type);
                            }
                            yield null;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        void resetSnapshots() {
            rewritten.clear();
            observedAtFirstRetransform.clear();
        }

        private void retransform(final Class<?> type) throws Exception {
            captureSlotSnapshot(UniformLocationHookBridge.DEFER_ACTIVE_PROPERTY);
            captureSlotSnapshot(CameraMatrixCacheBridge.GET_PROPERTY);
            captureSlotSnapshot(SkippedFrameMeshSetupElisionBridge.PREDICATE_PROPERTY);

            byte[] bytes;
            try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertNotNull(input, type.getName());
                bytes = input.readAllBytes();
            }
            original.putIfAbsent(type, bytes.clone());
            for (final ClassFileTransformer transformer : List.copyOf(transformers)) {
                final byte[] next = transformer.transform(
                        type.getModule(),
                        type.getClassLoader(),
                        type.getName().replace('.', '/'),
                        type,
                        type.getProtectionDomain(),
                        bytes);
                if (next != null) {
                    bytes = next;
                    if (!rewritten.contains(type)) {
                        rewritten.add(type);
                    }
                }
            }
            current.put(type, bytes);
        }

        private void captureSlotSnapshot(final String key) {
            if (!observedAtFirstRetransform.containsKey(key)) {
                final Object val = System.getProperties().get(key);
                if (val != null) {
                    observedAtFirstRetransform.put(key, val);
                }
            }
        }

        void assertAllRestored() {
            assertTrue(transformers.isEmpty(), "every transformer must be removed after close");
            for (final Class<?> type : rewritten) {
                assertArrayEquals(original.get(type), current.get(type), type.getName() + " must be restored");
                assertFalse(Arrays.equals(original.get(type), new byte[0]));
            }
        }
    }
}
