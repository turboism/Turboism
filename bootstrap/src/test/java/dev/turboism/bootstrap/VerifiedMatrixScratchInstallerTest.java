package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.optimization.geometry.MatrixScratchTransformer;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Offline protocol tests using actual reference classes, never a running Editor. */
class VerifiedMatrixScratchInstallerTest {
    private static final String GATE = MatrixScratchTransformer.ADMISSION_PROPERTY;
    private final Object previousGate = System.getProperties().get(GATE);

    @AfterEach void restore() {
        if (previousGate == null) System.getProperties().remove(GATE);
        else System.getProperties().put(GATE, previousGate);
    }

    @Test void exactReviewedArtifactsNeedExplicitRequestAndPolicy() {
        var normal = new RuntimeStartupConfig(false, false, false, false);
        for (var digest : List.of(ReviewedHostArtifacts.CUBISM_5_2_03,
                ReviewedHostArtifacts.CUBISM_5_3_02, ReviewedHostArtifacts.CUBISM_5_3_03)) {
            assertTrue(VerifiedMatrixScratchInstaller.admitted(digest, normal, true, 17));
            assertFalse(VerifiedMatrixScratchInstaller.admitted(digest, normal, false, 17));
            assertFalse(VerifiedMatrixScratchInstaller.admitted(digest, normal, true, 16));
            assertFalse(VerifiedMatrixScratchInstaller.admitted(digest,
                new RuntimeStartupConfig(true, false, false, false), true, 17));
            assertFalse(VerifiedMatrixScratchInstaller.admitted(digest,
                new RuntimeStartupConfig(false, false, false, false, false, false, false,
                    Set.of(VerifiedMatrixScratchInstaller.HOOK_ID)), true, 17));
        }
        assertFalse(VerifiedMatrixScratchInstaller.admitted(
            new HostArtifactDigest(1, "1".repeat(64)), normal, true, 17));
        assertFalse(VerifiedMatrixScratchInstaller.admitted(null, normal, true, 17));
    }

    @Test void startupFailureAndShutdownOwnTheMatrixInstallation() throws Exception {
        NativeOptimizationStartupOrder.assertBeforeRuntime("installMatrixScratch", "closeMatrixScratch");
    }

    @Test void armsOnlyAfterTransformAndRestoresBeforeRemovingOwnedGate() throws Exception {
        System.getProperties().remove(GATE);
        try (Fixture f = fixture(); var installer = new VerifiedMatrixScratchInstaller(f.instrumentation, f.artifact, f.loader)) {
            assertFalse(System.getProperties().containsKey(GATE));
            installer.install();
            AtomicBoolean gate = (AtomicBoolean) System.getProperties().get(GATE);
            assertTrue(gate.get());
            assertEquals(2, f.active.size(), "one pure dependency observer and one matrix transform");
            assertTrue(f.installObservedDisarmed);
            installer.close();
            assertFalse(gate.get(), "old bytecode cannot start reuse during restoration");
            assertTrue(f.restoreObservedDisarmed);
            assertTrue(installer.restored());
            assertTrue(f.active.isEmpty());
            assertFalse(System.getProperties().containsKey(GATE));
            installer.close();
            assertThrows(IllegalStateException.class, installer::install);
        }
    }

    @Test void transformationFailureDisarmsAndRollsBack() throws Exception {
        System.getProperties().remove(GATE);
        try (Fixture f = fixture(); var installer = new VerifiedMatrixScratchInstaller(f.instrumentation, f.artifact, f.loader)) {
            f.failTransform = true;
            assertThrows(Exception.class, installer::install);
            assertTrue(f.active.isEmpty());
            assertFalse(System.getProperties().containsKey(GATE));
            assertTrue(installer.restored());
        }
    }

    @Test void occupiedAdmissionIsPreservedAndNeverReused() throws Exception {
        AtomicBoolean foreign = new AtomicBoolean(true);
        System.getProperties().put(GATE, foreign);
        try (Fixture f = fixture(); var installer = new VerifiedMatrixScratchInstaller(f.instrumentation, f.artifact, f.loader)) {
            assertThrows(IllegalStateException.class, installer::install);
            assertSame(foreign, System.getProperties().get(GATE));
            assertTrue(foreign.get());
            assertTrue(f.active.isEmpty());
        }
    }

    @Test void changedNativeMathCannotBeAdmitted() throws Exception {
        System.getProperties().remove(GATE);
        try (Fixture f = fixture()) {
            f.changeMatrixBody = true;
            assertThrows(IllegalStateException.class,
                () -> new VerifiedMatrixScratchInstaller(f.instrumentation, f.artifact, f.loader));
            assertTrue(f.active.isEmpty());
            assertFalse(System.getProperties().containsKey(GATE));
        }
    }

    @Test void dependencyChangeBetweenPreparationAndInstallIsRejected() throws Exception {
        System.getProperties().remove(GATE);
        try (Fixture f = fixture(); var installer = new VerifiedMatrixScratchInstaller(f.instrumentation, f.artifact, f.loader)) {
            f.changeMatrixBody = true;
            assertThrows(IllegalStateException.class, installer::install);
            assertTrue(f.active.isEmpty());
            assertFalse(System.getProperties().containsKey(GATE));
        }
    }

    @Test void laterObservedMathChangeRetiresReuseWithoutRewritingTheDependency() throws Exception {
        System.getProperties().remove(GATE);
        try (Fixture f = fixture(); var installer = new VerifiedMatrixScratchInstaller(f.instrumentation, f.artifact, f.loader)) {
            installer.install();
            var gate = (AtomicBoolean) System.getProperties().get(GATE);
            assertTrue(gate.get());
            f.changeMatrixBody = true;
            f.instrumentation.retransformClasses(f.loader.loadClass(MatrixScratchTransformer.MATRIX.replace('/', '.')));
            assertFalse(gate.get(), "a changed dependency must retire reuse");
            f.changeMatrixBody = false;
            f.instrumentation.retransformClasses(f.loader.loadClass(MatrixScratchTransformer.MATRIX.replace('/', '.')));
            assertFalse(gate.get(), "later matching bytecode must not revive the gate");
        }
    }

    @Test void failedRemovalCannotBeReportedAsRestoredAndCanBeRetried() throws Exception {
        System.getProperties().remove(GATE);
        try (Fixture f = fixture(); var installer = new VerifiedMatrixScratchInstaller(f.instrumentation, f.artifact, f.loader)) {
            installer.install();
            var gate = (AtomicBoolean) System.getProperties().get(GATE);
            f.failRemoval = true;
            assertThrows(IllegalStateException.class, installer::close);
            assertFalse(gate.get());
            assertFalse(installer.restored());
            assertFalse(System.getProperties().containsKey(GATE));
            f.failRemoval = false;
            installer.close();
            assertTrue(installer.restored());
            assertTrue(f.active.isEmpty());
        }
    }

    private static Fixture fixture() throws Exception {
        String supplied = System.getenv("TURBOISM_UNIFORM_HOST_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(), "explicit reviewed archive is required");
        return new Fixture(Path.of(supplied).toAbsolutePath().normalize());
    }

    private static final class Fixture implements AutoCloseable {
        final Path artifact;
        final URLClassLoader loader;
        final Instrumentation instrumentation;
        final List<ClassFileTransformer> active = new ArrayList<>();
        boolean failTransform, failRemoval, changeMatrixBody;
        boolean installObservedDisarmed, restoreObservedDisarmed, observedInstallation;

        Fixture(Path artifact) throws Exception {
            this.artifact = artifact;
            List<URL> urls = new ArrayList<>();
            try (var paths = Files.walk(artifact.getParent(), 3)) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".jar")).toList()) urls.add(path.toUri().toURL());
            }
            loader = new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader());
            instrumentation = (Instrumentation) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Instrumentation.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isRetransformClassesSupported", "isModifiableClass" -> true;
                    case "addTransformer" -> { active.add((ClassFileTransformer) args[0]); yield null; }
                    case "removeTransformer" -> {
                        if (failRemoval && args[0] instanceof MatrixScratchTransformer) yield false;
                        yield active.remove(args[0]);
                    }
                    case "retransformClasses" -> {
                        boolean installing = active.stream().anyMatch(t -> t instanceof MatrixScratchTransformer);
                        Object entry = System.getProperties().get(GATE);
                        if (installing) {
                            installObservedDisarmed = entry instanceof AtomicBoolean gate && !gate.get();
                            observedInstallation = true;
                            if (failTransform) { failTransform = false; throw new UnmodifiableClassException("injected failure"); }
                        } else if (observedInstallation) {
                            restoreObservedDisarmed = !(entry instanceof AtomicBoolean gate) || !gate.get();
                        }
                        for (Class<?> type : (Class<?>[]) args[0]) {
                            byte[] bytes;
                            try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                                bytes = input.readAllBytes();
                            }
                            if (changeMatrixBody && type.getName().equals(MatrixScratchTransformer.MATRIX.replace('/', '.'))) {
                                bytes = changeMatrix(bytes);
                            }
                            for (ClassFileTransformer transformer : List.copyOf(active)) {
                                byte[] next = transformer.transform(type.getModule(), type.getClassLoader(),
                                    type.getName().replace('.', '/'), type, type.getProtectionDomain(), bytes);
                                if (next != null) bytes = next;
                            }
                        }
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        }
        @Override public void close() throws Exception { loader.close(); }
    }

    /** One schema-preserving instruction mutation in the trusted test reference, without ASM outside runtime. */
    private static byte[] changeMatrix(byte[] original) {
        byte[] changed = original.clone();
        var in = java.nio.ByteBuffer.wrap(changed);
        assertEquals(0xcafebabe, in.getInt());
        in.getShort(); in.getShort();
        String[] utf = new String[Short.toUnsignedInt(in.getShort())];
        for (int index = 1; index < utf.length; index++) {
            int tag = Byte.toUnsignedInt(in.get());
            switch (tag) {
                case 1 -> {
                    byte[] text = new byte[Short.toUnsignedInt(in.getShort())];
                    in.get(text);
                    utf[index] = new String(text, java.nio.charset.StandardCharsets.UTF_8);
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.position(in.position() + 4);
                case 5, 6 -> { in.position(in.position() + 8); index++; }
                case 7, 8, 16, 19, 20 -> in.position(in.position() + 2);
                case 15 -> in.position(in.position() + 3);
                default -> throw new AssertionError("unexpected constant-pool tag: " + tag);
            }
        }
        in.position(in.position() + 6);
        int interfaces = Short.toUnsignedInt(in.getShort());
        in.position(in.position() + 2 * interfaces);
        int fields = Short.toUnsignedInt(in.getShort());
        for (int field = 0; field < fields; field++) {
            in.position(in.position() + 6);
            skipAttributes(in);
        }
        int methods = Short.toUnsignedInt(in.getShort());
        for (int method = 0; method < methods; method++) {
            in.getShort();
            String name = utf[Short.toUnsignedInt(in.getShort())];
            String descriptor = utf[Short.toUnsignedInt(in.getShort())];
            int attributes = Short.toUnsignedInt(in.getShort());
            for (int attribute = 0; attribute < attributes; attribute++) {
                String kind = utf[Short.toUnsignedInt(in.getShort())];
                int length = in.getInt(), start = in.position();
                if (name.equals("b") && descriptor.equals("([F[F[FZ)V") && kind.equals("Code")) {
                    // Code starts after max_stack, max_locals and code_length.
                    // Both argument 1 and argument 2 have type float[]: the class
                    // remains structurally valid, but its first null check changes.
                    assertEquals(0x2b, Byte.toUnsignedInt(changed[start + 8]), "expected initial aload_1");
                    changed[start + 8] = 0x2c;
                    return changed;
                }
                in.position(start + length);
            }
        }
        throw new AssertionError("native multiplication Code attribute absent");
    }

    private static void skipAttributes(java.nio.ByteBuffer in) {
        int count = Short.toUnsignedInt(in.getShort());
        for (int attribute = 0; attribute < count; attribute++) {
            in.getShort();
            int length = in.getInt();
            in.position(in.position() + length);
        }
    }
}
