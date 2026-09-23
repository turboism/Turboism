package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.glerror.GlGetErrorElisionTarget;
import dev.turboism.adapter.cubism.optimization.glerror.GlGetErrorElisionTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;

/**
 * Exact glGetError-elision admission and restoration for the test-only timing-leg
 * experiment. One host class is rewritten: the shader helper owning the
 * unconditional error-check marker. The reference bytecode comes from the official
 * artifact itself and the marker method's reviewed shape must match before any
 * rewrite; on close the class is re-captured and must hash back to its pre-rewrite
 * SHA-256. This installer performs no runtime bridge and never alters GL call
 * semantics outside the reviewed marker method.
 */
final class VerifiedGlGetErrorElisionInstaller implements AutoCloseable {

    private final Instrumentation instrumentation;
    private final GlGetErrorElisionTarget target;
    private final Class<?> entry;
    private final GlGetErrorElisionTransformer transformer;
    private boolean installed, restored;

    VerifiedGlGetErrorElisionInstaller(final Instrumentation instrumentation,
                                       final Path artifact, final ClassLoader loader)
            throws Exception {
        target = GlGetErrorElisionTarget.of(HostArtifactDigest.from(artifact))
            .orElseThrow(() -> new IllegalArgumentException(
                "glGetError elision unsupported host artifact"));
        if (Runtime.version().feature() < 17) {
            throw new IllegalArgumentException("glGetError elision requires JVM17+");
        }
        this.instrumentation = instrumentation;
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("retransform unavailable");
        }
        entry = Class.forName(target.owner().replace('/', '.'), false, loader);
        try (JarFile jar = new JarFile(artifact.toFile())) {
            attest(entry, loader, artifact);
            transformer = new GlGetErrorElisionTransformer(
                loader, artifact, reference(jar, entry), target);
        }
    }

    private static void attest(final Class<?> type, final ClassLoader loader, final Path artifact)
            throws Exception {
        if (type.getClassLoader() != loader
            || !Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath().normalize().equals(artifact.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("glGetError elision loader/source mismatch");
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
            throw new IllegalStateException("glGetError elision class unmodifiable");
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
            throw new IllegalStateException("glGetError elision inspection absent");
        }
        return result.get();
    }

    synchronized void install() throws Exception {
        if (installed) return;
        if (!instrumentation.isModifiableClass(entry)) {
            throw new IllegalStateException("glGetError elision entry unmodifiable");
        }
        try {
            instrumentation.addTransformer(transformer, true);
            installed = true;
            instrumentation.retransformClasses(entry);
            if (transformer.matches() != 1 || transformer.elided() < 1
                || transformer.failure() != null) {
                throw new IllegalStateException("glGetError elision entry not admitted: "
                    + transformer.failure());
            }
        } catch (Exception | Error failure) {
            try {
                close();
            } catch (Exception | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Returns the replaced {@code glGetError} site count for the install marker log. */
    int elided() {
        return transformer.elided();
    }

    /** Returns the reviewed owner/method/descriptor for the install marker log. */
    String targetDescription() {
        return target.owner() + "." + target.method() + target.descriptor();
    }

    @Override public synchronized void close() {
        if (!installed) return;
        instrumentation.removeTransformer(transformer);
        try {
            final byte[] original = capture(entry);
            final String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(original));
            if (!hash.equals(transformer.beforeSha256())) {
                throw new IllegalStateException(
                    "native glGetError elision restoration not proven: " + target.owner());
            }
            restored = true;
            installed = false;
        } catch (Exception failure) {
            throw new IllegalStateException("glGetError elision restoration failed", failure);
        }
    }

    boolean restored() {
        return restored;
    }
}
