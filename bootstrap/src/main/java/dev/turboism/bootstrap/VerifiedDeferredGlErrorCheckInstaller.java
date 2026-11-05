package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.deferred.DeferredGlErrorCheckTarget;
import dev.turboism.adapter.cubism.optimization.deferred.DeferredGlErrorCheckTransformer;
import dev.turboism.adapter.cubism.optimization.glerror.GlGetErrorElisionTransformer;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationLifecycleTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * Exact deferred GL error-check admission and restoration for the production
 * {@code launcher.mesaGlThread} option. Two host classes are rewritten: the
 * shader error-check helper (its {@code glGetError} site becomes a deferred
 * checkpoint) and the render-scope owner (every {@code render3d} return
 * consults the armed frame-end report). Reference bytecode comes from the
 * official artifact; when the upstream uniform-location lifecycle transform
 * is installed, the composed shapes it produces over the same reference bytes
 * are admitted explicitly via a private probe — never by trusting observed
 * chain output. On close each class must hash back to its pre-rewrite
 * SHA-256.
 */
final class VerifiedDeferredGlErrorCheckInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.render.mesa-gl-thread";

    /** The admission row this hook froze: policy id, kill-switch semantics and JVM predicate. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    private final Instrumentation instrumentation;
    private final DeferredGlErrorCheckTarget target;
    private final Class<?> errorType, frameType;
    private final DeferredGlErrorCheckTransformer transformer;
    private boolean installed, restored;

    static boolean admitted(
            final HostArtifactDigest digest,
            final RuntimeStartupConfig config,
            final boolean requested,
            final int jvm) {
        return VerifiedInstall.admits(
                ADMISSION,
                config,
                requested,
                jvm,
                () -> DeferredGlErrorCheckTarget.of(digest).isPresent());
    }

    VerifiedDeferredGlErrorCheckInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) throws Exception {
        target = DeferredGlErrorCheckTarget.of(HostArtifactDigest.from(artifact))
                .orElseThrow(() -> new IllegalArgumentException("deferred error check unsupported host artifact"));
        if (Runtime.version().feature() < 17) {
            throw new IllegalArgumentException("deferred error check requires JVM17+");
        }
        this.instrumentation = instrumentation;
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("retransform unavailable");
        }
        if (GlGetErrorElisionTransformer.isInstalled(loader, target.errorOwner())) {
            // The test-only elision already consumed the error-check site;
            // deferred checking is the production replacement, not a stacking
            // mode, so the pair stays mutually exclusive.
            throw new IllegalStateException("deferred error check excludes the test-only elision");
        }
        errorType = Class.forName(target.errorOwner().replace('/', '.'), false, loader);
        frameType = Class.forName(target.frameOwner().replace('/', '.'), false, loader);
        VerifiedHookInstallerSupport.attestClassSource(
                errorType, loader, artifact, "deferred-check loader/source mismatch: " + errorType.getName());
        VerifiedHookInstallerSupport.attestClassSource(
                frameType, loader, artifact, "deferred-check loader/source mismatch: " + frameType.getName());
        // The frame-end report needs the host logger and GLException shape the
        // bridge binds lazily; refuse admission up front when they are absent.
        if (UniformLocationHookBridge.DeferredAccessors.resolve(loader) == null) {
            throw new IllegalStateException("deferred error check report path absent");
        }
        try (JarFile jar = new JarFile(artifact.toFile())) {
            final byte[] errorReference = VerifiedHookInstallerSupport.readReferenceBytes(
                    jar, errorType, "deferred-check reference class missing: " + errorType.getName());
            final byte[] frameReference = VerifiedHookInstallerSupport.readReferenceBytes(
                    jar, frameType, "deferred-check reference class missing: " + frameType.getName());
            transformer = new DeferredGlErrorCheckTransformer(loader, artifact, errorReference, frameReference, target);
            final List<String> errorComposed = composedShape(
                    loader,
                    artifact,
                    errorReference,
                    UniformLocationLifecycleTransformer.Role.ERROR,
                    errorType,
                    target.errorOwner(),
                    DeferredGlErrorCheckTarget.ERROR_METHOD,
                    DeferredGlErrorCheckTarget.ERROR_DESCRIPTOR);
            if (errorComposed != null) {
                transformer.acceptComposedShape(target.errorOwner(), errorComposed);
            }
            final List<String> frameComposed = composedShape(
                    loader,
                    artifact,
                    frameReference,
                    UniformLocationLifecycleTransformer.Role.FRAME,
                    frameType,
                    target.frameOwner(),
                    DeferredGlErrorCheckTarget.FRAME_METHOD,
                    DeferredGlErrorCheckTarget.FRAME_DESCRIPTOR);
            if (frameComposed != null) {
                transformer.acceptComposedShape(target.frameOwner(), frameComposed);
            }
            verify(
                    errorType,
                    VerifiedHookInstallerSupport.captureLoadedBytes(
                            instrumentation,
                            errorType,
                            "deferred-check class unmodifiable: " + errorType.getName(),
                            "deferred-check capture absent: " + errorType.getName()),
                    target.errorOwner(),
                    DeferredGlErrorCheckTarget.ERROR_METHOD,
                    DeferredGlErrorCheckTarget.ERROR_DESCRIPTOR,
                    errorReference,
                    errorComposed);
            verify(
                    frameType,
                    VerifiedHookInstallerSupport.captureLoadedBytes(
                            instrumentation,
                            frameType,
                            "deferred-check class unmodifiable: " + frameType.getName(),
                            "deferred-check capture absent: " + frameType.getName()),
                    target.frameOwner(),
                    DeferredGlErrorCheckTarget.FRAME_METHOD,
                    DeferredGlErrorCheckTarget.FRAME_DESCRIPTOR,
                    frameReference,
                    frameComposed);
        }
    }

    /**
     * Runs the upstream lifecycle transform over the attested reference bytes
     * and reads back the exact method shape it produces — the input this
     * transform observes when that hook is installed. {@code null} when the
     * upstream rewrite does not apply; only the official shape is then
     * admitted. The shape is derived, never trusted from chain output.
     */
    private static List<String> composedShape(
            final ClassLoader loader,
            final Path artifact,
            final byte[] reference,
            final UniformLocationLifecycleTransformer.Role role,
            final Class<?> type,
            final String owner,
            final String method,
            final String descriptor) {
        try {
            final var probe = new UniformLocationLifecycleTransformer(
                    loader, artifact, reference, role, HostArtifactDigest.from(artifact));
            final byte[] output = probe.transform(
                    type.getModule(),
                    loader,
                    type.getName().replace('.', '/'),
                    null,
                    type.getProtectionDomain(),
                    reference);
            return output == null ? null : ReviewedMethodShape.read(output, owner, method, descriptor);
        } catch (Exception | LinkageError failure) {
            return null;
        }
    }

    private static void verify(
            final Class<?> type,
            final byte[] actual,
            final String owner,
            final String method,
            final String descriptor,
            final byte[] reference,
            final List<String> composed)
            throws Exception {
        final List<String> official = ReviewedMethodShape.read(reference, owner, method, descriptor);
        final List<String> observed = ReviewedMethodShape.read(actual, owner, method, descriptor);
        if (official == null || observed == null || (!official.equals(observed) && !observed.equals(composed))) {
            throw new IllegalStateException("deferred-check body mismatch: " + owner);
        }
    }

    synchronized void install() throws Exception {
        if (installed) return;
        if (!instrumentation.isModifiableClass(errorType) || !instrumentation.isModifiableClass(frameType)) {
            throw new IllegalStateException("deferred-check class unmodifiable");
        }
        try {
            instrumentation.addTransformer(transformer, true);
            installed = true;
            instrumentation.retransformClasses(errorType, frameType);
            if (transformer.matches() != 2 || transformer.failure() != null) {
                throw new IllegalStateException("deferred-check not admitted: " + transformer.failure());
            }
        } catch (Exception | Error failure) {
            VerifiedHookInstallerSupport.closeOnInstallFailure(failure, this);
            throw failure;
        }
    }

    /** Returns the reviewed target description for the install marker log. */
    String targetDescription() {
        return target.errorOwner() + "." + DeferredGlErrorCheckTarget.ERROR_METHOD
                + DeferredGlErrorCheckTarget.ERROR_DESCRIPTOR + " + "
                + target.frameOwner() + "." + DeferredGlErrorCheckTarget.FRAME_METHOD
                + DeferredGlErrorCheckTarget.FRAME_DESCRIPTOR;
    }

    @Override
    public synchronized void close() {
        if (!installed) return;
        instrumentation.removeTransformer(transformer);
        restored = true;
        for (final Map.Entry<Class<?>, String> owner : Map.ofEntries(
                        Map.entry(errorType, target.errorOwner()), Map.entry(frameType, target.frameOwner()))
                .entrySet()) {
            try {
                final String hash =
                        VerifiedHookInstallerSupport.sha256Hex(VerifiedHookInstallerSupport.captureLoadedBytes(
                                instrumentation,
                                owner.getKey(),
                                "deferred-check class unmodifiable: "
                                        + owner.getKey().getName(),
                                "deferred-check capture absent: "
                                        + owner.getKey().getName()));
                if (!hash.equals(transformer.beforeSha256(owner.getValue()))) {
                    throw new IllegalStateException("deferred-check restoration not proven: " + owner.getValue());
                }
            } catch (Exception failure) {
                restored = false;
                throw new IllegalStateException("deferred-check restoration failed", failure);
            }
        }
        installed = false;
    }

    boolean restored() {
        return restored;
    }
}
