package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.glerror.GlGetErrorElisionTarget;
import dev.turboism.adapter.cubism.optimization.glerror.GlGetErrorElisionTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
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

    VerifiedGlGetErrorElisionInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) throws Exception {
        target = GlGetErrorElisionTarget.of(HostArtifactDigest.from(artifact))
                .orElseThrow(() -> new IllegalArgumentException("glGetError elision unsupported host artifact"));
        if (Runtime.version().feature() < 17) {
            throw new IllegalArgumentException("glGetError elision requires JVM17+");
        }
        this.instrumentation = instrumentation;
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("retransform unavailable");
        }
        entry = Class.forName(target.owner().replace('/', '.'), false, loader);
        try (JarFile jar = new JarFile(artifact.toFile())) {
            VerifiedHookInstallerSupport.attestClassSource(
                    entry, loader, artifact, "glGetError elision loader/source mismatch");
            transformer = new GlGetErrorElisionTransformer(
                    loader, artifact, VerifiedHookInstallerSupport.readReferenceBytes(jar, entry), target);
        }
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
            if (transformer.matches() != 1 || transformer.elided() < 1 || transformer.failure() != null) {
                throw new IllegalStateException("glGetError elision entry not admitted: " + transformer.failure());
            }
            // Publish only after the rewrite is verified: downstream verified
            // installers may then admit the composed (elided) method shape.
            GlGetErrorElisionTransformer.markInstalled(entry.getClassLoader(), target.owner());
        } catch (Exception | Error failure) {
            VerifiedHookInstallerSupport.closeOnInstallFailure(failure, this);
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

    @Override
    public synchronized void close() {
        if (!installed) return;
        instrumentation.removeTransformer(transformer);
        // The marker is dropped before the restore capture so bytes that stay
        // elided after a failed restore are no longer claimed as ours.
        GlGetErrorElisionTransformer.clearInstalled(entry.getClassLoader(), target.owner());
        try {
            final byte[] original = VerifiedHookInstallerSupport.captureLoadedBytes(
                    instrumentation,
                    entry,
                    "glGetError elision class unmodifiable",
                    "glGetError elision inspection absent");
            final String hash = VerifiedHookInstallerSupport.sha256Hex(original);
            if (!hash.equals(transformer.beforeSha256())) {
                throw new IllegalStateException("native glGetError elision restoration not proven: " + target.owner());
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
