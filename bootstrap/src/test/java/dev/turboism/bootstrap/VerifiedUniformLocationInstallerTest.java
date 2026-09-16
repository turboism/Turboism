package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationCallSiteTransformer;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationLifecycleTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VerifiedUniformLocationInstallerTest {
    @Test void exactArtifactsAndEnabledPreferenceAreRequired() {
        RuntimeStartupConfig normal = new RuntimeStartupConfig(false, false, false, false);
        assertTrue(VerifiedUniformLocationInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_03, normal, true, 17));
        assertTrue(VerifiedUniformLocationInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_02, normal, true, 17));
        assertTrue(VerifiedUniformLocationInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_2_03, normal, true, 17));
        assertFalse(VerifiedUniformLocationInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_03_JOGL, normal, true, 17));
        assertFalse(VerifiedUniformLocationInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_03, normal, false, 17));
        assertFalse(VerifiedUniformLocationInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_03, normal, true, 16));
        assertFalse(VerifiedUniformLocationInstaller.admitted(new HostArtifactDigest(1L, "9".repeat(64)), normal, true, 17));
        assertFalse(VerifiedUniformLocationInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_03,
            new RuntimeStartupConfig(true, false, false, false), true, 17));
        assertFalse(VerifiedUniformLocationInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_03,
            new RuntimeStartupConfig(false, false, false, false, false, false, false,
                Set.of(VerifiedUniformLocationInstaller.HOOK_ID)), true, 17));
    }
    @Test void startupAndFailureCleanupRemainBeforeUserRuntime() throws Exception {
        NativeOptimizationStartupOrder.assertBeforeRuntime("installUniformLocationCache", "closeUniformLocationCache");
    }
    @Test void exactReferencesExerciseRegistrationAndRestorationWithoutRunningEditor() throws Exception {
        try (Fixture fixture = fixture()) {
            VerifiedUniformLocationInstaller installer = new VerifiedUniformLocationInstaller(fixture.instrumentation, fixture.artifact, fixture.loader);
            installer.install();
            assertTrue(System.getProperties().containsKey(UniformLocationCallSiteTransformer.LOOKUP_PROPERTY));
            assertEquals(5, fixture.active.size());
            installer.close();
            assertTrue(installer.restored());
            assertEquals(0, fixture.active.size());
            assertFalse(System.getProperties().containsKey(UniformLocationCallSiteTransformer.LOOKUP_PROPERTY));
        }
    }
    @Test void failedRetransformRollsBackAllOwnedSlotsAndTransforms() throws Exception {
        try (Fixture fixture = fixture()) {
            VerifiedUniformLocationInstaller installer = new VerifiedUniformLocationInstaller(fixture.instrumentation, fixture.artifact, fixture.loader);
            fixture.failHookRetransform = true;
            assertThrows(Exception.class, installer::install);
            assertEquals(0, fixture.active.size());
            assertFalse(System.getProperties().containsKey(UniformLocationCallSiteTransformer.LOOKUP_PROPERTY));
            installer.close();
        }
    }
    @Test void incompleteMutationInventoryIsNotAdmitted(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        Path path = directory.resolve("missing-writers.jar");
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new java.util.jar.JarEntry("empty.txt")); output.closeEntry();
        }
        try (var jar = new java.util.jar.JarFile(path.toFile())) {
            assertThrows(IllegalArgumentException.class, () -> VerifiedUniformLocationInstaller.verifyMutationInventory(jar));
        }
    }
    private static Fixture fixture() throws Exception {
        String supplied = System.getenv("TURBOISM_UNIFORM_HOST_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(), "exact reference artifact not supplied");
        return new Fixture(Path.of(supplied));
    }
    /** JVM instrumentation protocol double; bytecode and class identities are real references. */
    private static final class Fixture implements AutoCloseable {
        final Path artifact;
        final URLClassLoader loader;
        final List<ClassFileTransformer> active = new ArrayList<>();
        final Instrumentation instrumentation;
        boolean failHookRetransform;
        Fixture(Path artifact) throws Exception {
            this.artifact = artifact;
            List<URL> urls = new ArrayList<>();
            try (var files = Files.walk(artifact.getParent(), 3)) {
                for (Path path : files.filter(path -> path.toString().endsWith(".jar")).toList()) urls.add(path.toUri().toURL());
            }
            loader = new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader());
            instrumentation = (Instrumentation) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Instrumentation.class}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "isRetransformClassesSupported", "isModifiableClass" -> true;
                    case "addTransformer" -> { active.add((ClassFileTransformer) args[0]); yield null; }
                    case "removeTransformer" -> active.remove(args[0]);
                    case "retransformClasses" -> {
                        boolean containsHook = active.stream().anyMatch(value -> value instanceof UniformLocationCallSiteTransformer || value instanceof UniformLocationLifecycleTransformer);
                        if (containsHook && failHookRetransform) { failHookRetransform = false; throw new UnmodifiableClassException("injected installation failure"); }
                        for (Class<?> type : (Class<?>[]) args[0]) {
                            byte[] bytes;
                            try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) { bytes = input.readAllBytes(); }
                            for (ClassFileTransformer transformer : List.copyOf(active)) {
                                byte[] next = transformer.transform(type.getModule(), type.getClassLoader(), type.getName().replace('.', '/'), type, type.getProtectionDomain(), bytes);
                                if (next != null) bytes = next;
                            }
                        }
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
        }
        @Override public void close() throws Exception { loader.close(); }
    }
}
