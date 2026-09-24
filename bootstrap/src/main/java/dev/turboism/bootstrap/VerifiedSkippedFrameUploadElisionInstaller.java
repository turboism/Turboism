package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadElisionBridge;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadElisionTarget;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadElisionTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;

/**
 * Exact admission, dependency verification and restoration for the test-only
 * skipped-frame upload elision experiment. Two host classes are rewritten —
 * the float wrapper {@code mesh/a/b} and the integer/index wrapper
 * {@code mesh/a/c}, each inside {@code b(GL2ES2,int)}. The bridge reads
 * {@code a.a.i()}, {@code <wrapper>.b()} and {@code shader/A.a(Buffer)}; every
 * such dependency body is verified against the official artifact before any
 * rewrite. On close each class must hash back to its pre-rewrite SHA-256.
 *
 * <p>Composition: the transform touches only {@code mesh/a/*}; the
 * uniform-location hooks target {@code shader/GShader}, {@code shader/A} and
 * JOGL classes, and the glGetError elision targets {@code shader/A.a(GL,String,Z)}
 * — a different method on a class this experiment does not rewrite, so the
 * dependency check on {@code shader/A.a(Buffer)} still sees the official body
 * under either of those transforms. The two experiments remain disjoint.</p>
 */
final class VerifiedSkippedFrameUploadElisionInstaller implements AutoCloseable {

    private final Instrumentation instrumentation;
    private final SkippedFrameUploadElisionTarget target;
    private final SkippedFrameUploadElisionBridge bridge;
    private final List<Class<?>> entries = new ArrayList<>();
    private final List<SkippedFrameUploadElisionTransformer> transformers = new ArrayList<>();
    private boolean installed, restored;

    VerifiedSkippedFrameUploadElisionInstaller(final Instrumentation instrumentation,
                                               final Path artifact, final ClassLoader loader)
            throws Exception {
        target = SkippedFrameUploadElisionTarget.of(HostArtifactDigest.from(artifact))
            .orElseThrow(() -> new IllegalArgumentException(
                "upload elision unsupported host artifact"));
        if (Runtime.version().feature() < 17) {
            throw new IllegalArgumentException("upload elision requires JVM17+");
        }
        this.instrumentation = instrumentation;
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("retransform unavailable");
        }
        try (JarFile jar = new JarFile(artifact.toFile())) {
            for (final String owner : SkippedFrameUploadElisionTarget.OWNERS) {
                final Class<?> type = Class.forName(owner.replace('/', '.'), false, loader);
                attest(type, loader, artifact);
                entries.add(type);
                transformers.add(new SkippedFrameUploadElisionTransformer(
                    loader, artifact, reference(jar, type), owner));
            }
            verify(jar, loader, artifact, SkippedFrameUploadElisionTarget.BASE_OWNER,
                "i", "()Ljava/nio/IntBuffer;");
            verify(jar, loader, artifact, SkippedFrameUploadElisionTarget.BASE_OWNER,
                "k", "()Z");
            for (final String owner : SkippedFrameUploadElisionTarget.OWNERS) {
                verify(jar, loader, artifact, owner, "b", "()Ljava/nio/Buffer;");
            }
            verify(jar, loader, artifact, SkippedFrameUploadElisionTarget.SIZE_OWNER,
                "a", "(Ljava/nio/Buffer;)J");
        }
        bridge = new SkippedFrameUploadElisionBridge(loader);
    }

    private static void attest(final Class<?> type, final ClassLoader loader,
                               final Path artifact) throws Exception {
        if (type.getClassLoader() != loader
            || !Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath().normalize().equals(artifact.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException(
                "upload elision dependency loader/source mismatch: " + type.getName());
        }
    }

    private void verify(final JarFile jar, final ClassLoader loader, final Path artifact,
                        final String owner, final String method, final String descriptor)
            throws Exception {
        final Class<?> type = Class.forName(owner.replace('/', '.'), false, loader);
        attest(type, loader, artifact);
        final byte[] actual = capture(type);
        final List<String> expected =
            ReviewedMethodShape.read(reference(jar, type), owner, method, descriptor);
        if (expected == null
            || !expected.equals(ReviewedMethodShape.read(actual, owner, method, descriptor))) {
            throw new IllegalStateException(
                "upload elision dependency body mismatch: " + owner + "." + method);
        }
    }

    private static byte[] reference(final JarFile jar, final Class<?> type) throws Exception {
        try (var input = jar.getInputStream(
                jar.getJarEntry(type.getName().replace('.', '/') + ".class"))) {
            return input.readAllBytes();
        }
    }

    private byte[] capture(final Class<?> type) throws Exception {
        if (!instrumentation.isModifiableClass(type)) {
            throw new IllegalStateException("upload elision class unmodifiable: " + type.getName());
        }
        final AtomicReference<byte[]> result = new AtomicReference<>();
        final ClassFileTransformer observer = new ClassFileTransformer() {
            @Override public byte[] transform(final Module module, final ClassLoader loader,
                                              final String name, final Class<?> redefined,
                                              final ProtectionDomain domain, final byte[] bytes) {
                if (redefined == type) result.set(bytes.clone());
                return null;
            }
        };
        instrumentation.addTransformer(observer, true);
        try {
            instrumentation.retransformClasses(type);
        } finally {
            instrumentation.removeTransformer(observer);
        }
        if (result.get() == null) {
            throw new IllegalStateException(
                "upload elision dependency inspection absent: " + type.getName());
        }
        return result.get();
    }

    synchronized void install() throws Exception {
        if (installed) return;
        for (final Class<?> type : entries) {
            if (!instrumentation.isModifiableClass(type)) {
                throw new IllegalStateException("upload elision entry unmodifiable: "
                    + type.getName());
            }
        }
        bridge.install();
        try {
            for (int i = 0; i < entries.size(); i++) {
                final SkippedFrameUploadElisionTransformer transformer = transformers.get(i);
                instrumentation.addTransformer(transformer, true);
                instrumentation.retransformClasses(entries.get(i));
                if (transformer.matches() != 1 || transformer.guarded() != 2
                    || transformer.failure() != null) {
                    throw new IllegalStateException("upload elision entry not admitted: "
                        + entries.get(i).getName() + " " + transformer.failure());
                }
            }
            installed = true;
        } catch (Exception | Error failure) {
            try {
                close();
            } catch (Exception | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Guarded upload call sites across both rewritten wrappers. */
    int sites() {
        int total = 0;
        for (final SkippedFrameUploadElisionTransformer transformer : transformers) {
            total += transformer.guarded();
        }
        return total;
    }

    @Override public synchronized void close() {
        final Map<String, Long> stats = bridge.snapshot();
        bridge.close();
        if (!installed) {
            dev.turboism.runtime.log.RuntimeDiagnostics.info("bootstrap",
                "TURBOISM_UPLOAD_ELISION closed elided=" + stats.get("elided")
                + " passed=" + stats.get("passed") + " calls=" + stats.get("calls")
                + " clears=" + stats.get("clears") + " installed=false");
            return;
        }
        for (final SkippedFrameUploadElisionTransformer transformer : transformers) {
            instrumentation.removeTransformer(transformer);
        }
        try {
            for (int i = 0; i < entries.size(); i++) {
                final byte[] original = capture(entries.get(i));
                final String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(original));
                if (!hash.equals(transformers.get(i).beforeSha256())) {
                    throw new IllegalStateException("upload elision restoration not proven: "
                        + entries.get(i).getName());
                }
            }
            restored = true;
            installed = false;
        } catch (Exception failure) {
            dev.turboism.runtime.log.RuntimeDiagnostics.info("bootstrap",
                "TURBOISM_UPLOAD_ELISION closed elided=" + stats.get("elided")
                + " passed=" + stats.get("passed") + " calls=" + stats.get("calls")
                + " clears=" + stats.get("clears")
                + " contextClears=" + stats.get("contextClears")
                + " nonSkippedClears=" + stats.get("nonSkippedClears")
                + " lifecycleClears=" + stats.get("lifecycleClears")
                + " exceptionClears=" + stats.get("exceptionClears")
                + " observerFailures=" + stats.get("observerFailures")
                + " restored=false reason=" + failure);
            throw new IllegalStateException("upload elision restoration failed", failure);
        }
        dev.turboism.runtime.log.RuntimeDiagnostics.info("bootstrap",
            "TURBOISM_UPLOAD_ELISION closed elided=" + stats.get("elided")
            + " passed=" + stats.get("passed") + " calls=" + stats.get("calls")
            + " clears=" + stats.get("clears")
            + " contextClears=" + stats.get("contextClears")
            + " nonSkippedClears=" + stats.get("nonSkippedClears")
            + " lifecycleClears=" + stats.get("lifecycleClears")
            + " exceptionClears=" + stats.get("exceptionClears")
            + " observerFailures=" + stats.get("observerFailures")
            + " restored=true");
    }

    boolean restored() {
        return restored;
    }
}
