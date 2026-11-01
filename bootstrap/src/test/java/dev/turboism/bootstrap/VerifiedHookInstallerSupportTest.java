package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifiedHookInstallerSupportTest {

    @TempDir
    Path temporary;

    @Test
    void attestsExactLoaderAndNormalizedArtifact() throws Exception {
        final Path source = sourceOf(Target.class);
        assertDoesNotThrow(() -> VerifiedHookInstallerSupport.attestClassSource(
                Target.class, Target.class.getClassLoader(), source, "fixture mismatch"));
        assertDoesNotThrow(() -> VerifiedHookInstallerSupport.attestClassSource(
                Target.class,
                Target.class.getClassLoader(),
                source.resolve("nested").resolve(".."),
                "fixture mismatch"));

        final IllegalArgumentException loaderFailure = assertThrows(
                IllegalArgumentException.class,
                () -> VerifiedHookInstallerSupport.attestClassSource(
                        Target.class, new FixtureLoader(), source, "fixture mismatch"));
        assertEquals("fixture mismatch", loaderFailure.getMessage());

        final IllegalArgumentException artifactFailure = assertThrows(
                IllegalArgumentException.class,
                () -> VerifiedHookInstallerSupport.attestClassSource(
                        Target.class,
                        Target.class.getClassLoader(),
                        temporary.resolve("other.jar"),
                        "fixture mismatch"));
        assertEquals("fixture mismatch", artifactFailure.getMessage());
    }

    @Test
    void attestsOnlyBootstrapAndPlatformClassesAsJdkClasses() {
        assertDoesNotThrow(() -> VerifiedHookInstallerSupport.attestJdkClass(String.class, "jdk mismatch"));

        final IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> VerifiedHookInstallerSupport.attestJdkClass(Target.class, "jdk mismatch"));
        assertEquals("jdk mismatch", failure.getMessage());
    }

    @Test
    void readsExactReferenceBytesAndReportsMissingEntries() throws Exception {
        final Path artifact = jarWith(Target.class);
        try (JarFile jar = new JarFile(artifact.toFile())) {
            assertArrayEquals(
                    classBytes(Target.class), VerifiedHookInstallerSupport.readReferenceBytes(jar, Target.class));

            final IllegalArgumentException missing = assertThrows(
                    IllegalArgumentException.class,
                    () -> VerifiedHookInstallerSupport.readReferenceBytes(
                            jar, Other.class, "reference missing: " + Other.class.getName()));
            assertEquals("reference missing: " + Other.class.getName(), missing.getMessage());
        }
    }

    @Test
    void capturesCurrentBytesThroughATransientObserverAndClonesThem() throws Exception {
        final List<ClassFileTransformer> active = new ArrayList<>();
        final byte[] bytes = {1, 2, 3};
        final Instrumentation instrumentation = observingInstrumentation(active, true, bytes, true);

        final byte[] captured = VerifiedHookInstallerSupport.captureLoadedBytes(
                instrumentation, Target.class, "unmodifiable", "absent");

        assertArrayEquals(bytes, captured);
        assertNotSame(bytes, captured);
        assertTrue(active.isEmpty(), "the transient observer must be removed");
    }

    @Test
    void captureFailsClosedForUnmodifiableAbsentOrUndetachableObservers() throws Exception {
        final Instrumentation unmodifiable = observingInstrumentation(new ArrayList<>(), false, new byte[] {1}, true);
        final IllegalStateException unmodifiableFailure = assertThrows(
                IllegalStateException.class,
                () -> VerifiedHookInstallerSupport.captureLoadedBytes(
                        unmodifiable, Target.class, "unmodifiable", "absent"));
        assertEquals("unmodifiable", unmodifiableFailure.getMessage());

        final Instrumentation absent = observingInstrumentation(new ArrayList<>(), true, null, true);
        final IllegalStateException absentFailure = assertThrows(
                IllegalStateException.class,
                () -> VerifiedHookInstallerSupport.captureLoadedBytes(absent, Target.class, "unmodifiable", "absent"));
        assertEquals("absent", absentFailure.getMessage());

        final Instrumentation undetachable = observingInstrumentation(new ArrayList<>(), true, new byte[] {1}, false);
        final IllegalStateException removalFailure = assertThrows(
                IllegalStateException.class,
                () -> VerifiedHookInstallerSupport.captureLoadedBytes(
                        undetachable, Target.class, "unmodifiable", "absent", "observer removal failed"));
        assertEquals("observer removal failed", removalFailure.getMessage());
    }

    @Test
    void hashesWithLowercaseSha256() throws Exception {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                VerifiedHookInstallerSupport.sha256Hex("abc".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }

    @Test
    void retransformsOnlyLoadedExactOwnersFromTheHostLoader() throws Exception {
        final List<Class<?>> retransformed = new ArrayList<>();
        final Instrumentation instrumentation =
                loadedInstrumentation(new Class<?>[] {Target.class, Other.class}, true, retransformed);

        assertEquals(
                1,
                VerifiedHookInstallerSupport.retransformIfLoaded(
                        instrumentation, Target.class.getName(), Target.class.getClassLoader()));
        assertEquals(List.of(Target.class), retransformed);

        retransformed.clear();
        assertEquals(
                0,
                VerifiedHookInstallerSupport.retransformIfLoaded(
                        instrumentation, Target.class.getName(), new FixtureLoader()));
        assertTrue(retransformed.isEmpty());

        retransformed.clear();
        assertEquals(
                2,
                VerifiedHookInstallerSupport.retransformIfLoaded(
                        instrumentation,
                        List.of(Target.class.getName(), Other.class.getName()),
                        Target.class.getClassLoader()));
        assertEquals(List.of(Target.class, Other.class), retransformed);
    }

    @Test
    void retransformSkipsUnmodifiableLoadedOwners() throws Exception {
        final List<Class<?>> retransformed = new ArrayList<>();
        final Instrumentation instrumentation =
                loadedInstrumentation(new Class<?>[] {Target.class}, false, retransformed);

        assertEquals(
                0,
                VerifiedHookInstallerSupport.retransformIfLoaded(
                        instrumentation, Target.class.getName(), Target.class.getClassLoader()));
        assertTrue(retransformed.isEmpty());
    }

    private static Path sourceOf(final Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static byte[] classBytes(final Class<?> type) throws Exception {
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            return input.readAllBytes();
        }
    }

    private Path jarWith(final Class<?> type) throws Exception {
        final Path artifact = temporary.resolve("reference.jar");
        final byte[] bytes = classBytes(type);
        try (var output = new JarOutputStream(Files.newOutputStream(artifact))) {
            output.putNextEntry(new JarEntry(type.getName().replace('.', '/') + ".class"));
            output.write(bytes);
            output.closeEntry();
        }
        return artifact;
    }

    private static Instrumentation observingInstrumentation(
            final List<ClassFileTransformer> active,
            final boolean modifiable,
            final byte[] bytes,
            final boolean removable) {
        return (Instrumentation) Proxy.newProxyInstance(
                VerifiedHookInstallerSupportTest.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "isModifiableClass" -> modifiable;
                    case "addTransformer" -> {
                        active.add((ClassFileTransformer) arguments[0]);
                        yield null;
                    }
                    case "retransformClasses" -> {
                        for (final Class<?> type : (Class<?>[]) arguments[0]) {
                            for (final ClassFileTransformer transformer : List.copyOf(active)) {
                                transformer.transform(
                                        type.getModule(),
                                        type.getClassLoader(),
                                        type.getName().replace('.', '/'),
                                        type,
                                        type.getProtectionDomain(),
                                        bytes);
                            }
                        }
                        yield null;
                    }
                    case "removeTransformer" -> {
                        if (!removable) yield false;
                        yield active.remove((ClassFileTransformer) arguments[0]);
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Instrumentation loadedInstrumentation(
            final Class<?>[] loaded, final boolean modifiable, final List<Class<?>> retransformed) {
        return (Instrumentation) Proxy.newProxyInstance(
                VerifiedHookInstallerSupportTest.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getAllLoadedClasses" -> loaded;
                    case "isModifiableClass" -> modifiable;
                    case "retransformClasses" -> {
                        retransformed.addAll(List.of((Class<?>[]) arguments[0]));
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class FixtureLoader extends ClassLoader {}

    static final class Target {}

    static final class Other {}
}
