package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.deferred.DeferredGlErrorCheckTarget;
import dev.turboism.adapter.cubism.optimization.deferred.DeferredGlErrorCheckTransformer;
import dev.turboism.adapter.cubism.optimization.glerror.GlGetErrorElisionTransformer;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationLifecycleTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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
        return requested
                && jvm >= 17
                && config.hookEnabled(HOOK_ID)
                && DeferredGlErrorCheckTarget.of(digest).isPresent();
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
        attest(errorType, loader, artifact);
        attest(frameType, loader, artifact);
        // The frame-end report needs the host logger and GLException shape the
        // bridge binds lazily; refuse admission up front when they are absent.
        if (UniformLocationHookBridge.DeferredAccessors.resolve(loader) == null) {
            throw new IllegalStateException("deferred error check report path absent");
        }
        try (JarFile jar = new JarFile(artifact.toFile())) {
            final byte[] errorReference = reference(jar, errorType);
            final byte[] frameReference = reference(jar, frameType);
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
                    capture(errorType),
                    target.errorOwner(),
                    DeferredGlErrorCheckTarget.ERROR_METHOD,
                    DeferredGlErrorCheckTarget.ERROR_DESCRIPTOR,
                    errorReference,
                    errorComposed);
            verify(
                    frameType,
                    capture(frameType),
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

    private static void attest(final Class<?> type, final ClassLoader loader, final Path artifact) throws Exception {
        if (type.getClassLoader() != loader
                || type.getProtectionDomain().getCodeSource() == null
                || !Path.of(type.getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI())
                        .toAbsolutePath()
                        .normalize()
                        .equals(artifact.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("deferred-check loader/source mismatch: " + type.getName());
        }
    }

    private static byte[] reference(final JarFile jar, final Class<?> type) throws Exception {
        final var entry = jar.getJarEntry(type.getName().replace('.', '/') + ".class");
        if (entry == null) {
            throw new IllegalArgumentException("deferred-check reference class missing: " + type.getName());
        }
        try (var input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    private byte[] capture(final Class<?> type) throws Exception {
        if (!instrumentation.isModifiableClass(type)) {
            throw new IllegalStateException("deferred-check class unmodifiable: " + type.getName());
        }
        final AtomicReference<byte[]> result = new AtomicReference<>();
        final ClassFileTransformer observer = new ClassFileTransformer() {
            @Override
            public byte[] transform(
                    final Module module,
                    final ClassLoader loader,
                    final String name,
                    final Class<?> redefined,
                    final ProtectionDomain domain,
                    final byte[] bytes) {
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
            throw new IllegalStateException("deferred-check capture absent: " + type.getName());
        }
        return result.get();
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
            try {
                close();
            } catch (Exception | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
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
                final String hash = HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(capture(owner.getKey())));
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
