package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.lineupload.LineRendererUploadSplitBridge;
import dev.turboism.adapter.cubism.optimization.lineupload.LineRendererUploadSplitTarget;
import dev.turboism.adapter.cubism.optimization.lineupload.LineRendererUploadSplitTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;

/** Exact-host admission, rewrite and verified restoration for the line-renderer upload split. */
final class VerifiedLineRendererUploadSplitInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.render.line-renderer-upload-split";

    private final Instrumentation instrumentation;
    private final Class<?> target;
    private final LineRendererUploadSplitTransformer transformer;
    private final LineRendererUploadSplitBridge bridge;
    private boolean installed;
    private boolean restored;

    static boolean admitted(final Path artifact, final RuntimeStartupConfig config, final boolean requested)
            throws java.io.IOException {
        return requested
                && config != null
                && config.hookEnabled(HOOK_ID)
                && LineRendererUploadSplitTarget.of(HostArtifactDigest.from(artifact))
                        .isPresent();
    }

    VerifiedLineRendererUploadSplitInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) throws Exception {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        final Path source =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        LineRendererUploadSplitTarget.of(HostArtifactDigest.from(source))
                .orElseThrow(
                        () -> new IllegalArgumentException("line-renderer upload split unsupported host artifact"));
        target = Class.forName(LineRendererUploadSplitTarget.OWNER.replace('/', '.'), false, loader);
        VerifiedHookInstallerSupport.attestClassSource(
                target, loader, source, "line-renderer upload split loader/source mismatch");
        for (final LineRendererUploadSplitTarget.Dep dep : LineRendererUploadSplitTarget.DEPENDENCIES) {
            final Class<?> owner = Class.forName(dep.owner().replace('/', '.'), false, loader);
            VerifiedHookInstallerSupport.attestClassSource(
                    owner, loader, source, "line-renderer upload split dependency loader/source mismatch");
            verifyMethodPresent(owner, dep.name(), dep.descriptor());
        }
        try (JarFile jar = new JarFile(source.toFile())) {
            transformer = new LineRendererUploadSplitTransformer(
                    loader, source, VerifiedHookInstallerSupport.readReferenceBytes(jar, target));
        }
        bridge = new LineRendererUploadSplitBridge(loader);
    }

    private static void verifyMethodPresent(final Class<?> owner, final String name, final String descriptor)
            throws NoSuchMethodException {
        for (final Method method : owner.getMethods()) {
            if (!method.getName().equals(name)) continue;
            final String desc = MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                    .descriptorString();
            if (desc.equals(descriptor)) return;
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + descriptor);
    }

    synchronized void install() throws Exception {
        if (installed) return;
        if (!instrumentation.isRetransformClassesSupported() || !instrumentation.isModifiableClass(target)) {
            throw new IllegalStateException("line-renderer host class cannot be transformed");
        }
        bridge.install();
        try {
            instrumentation.addTransformer(transformer, true);
            installed = true;
            instrumentation.retransformClasses(target);
            if (transformer.matches() != 1 || transformer.failure() != null) {
                throw new IllegalStateException(
                        "line-renderer upload split was not admitted: " + transformer.failure());
            }
        } catch (Exception | Error failure) {
            // @containment-exempt: close runs cleanup before rethrowing with fatal error prioritized
            try {
                close();
            } catch (Exception | Error cleanup) {
                // @containment-exempt: cleanup failure merged, prioritizing fatal error
                dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(
                        dev.turboism.core.runtime.work.FatalErrors.mergeForCleanup(failure, cleanup));
            }
            throw failure;
        }
    }

    synchronized java.util.Map<String, Long> snapshot() {
        return bridge.snapshot();
    }

    @Override
    public synchronized void close() {
        if (!installed) {
            bridge.close();
            return;
        }
        instrumentation.removeTransformer(transformer);
        final AtomicReference<String> observed = new AtomicReference<>();
        final ClassFileTransformer observer = new ClassFileTransformer() {
            @Override
            public byte[] transform(
                    final Module module,
                    final ClassLoader loader,
                    final String name,
                    final Class<?> redefined,
                    final ProtectionDomain domain,
                    final byte[] bytes) {
                if (redefined == target) observed.set(sha256OrUnavailable(bytes));
                return null;
            }
        };
        instrumentation.addTransformer(observer, true);
        try {
            instrumentation.retransformClasses(target);
            restored = transformer.beforeSha256() != null
                    && transformer.beforeSha256().equals(observed.get());
            if (!restored) throw new IllegalStateException("line-renderer upload split restoration not proven");
            installed = false;
        } catch (java.lang.instrument.UnmodifiableClassException failure) {
            throw new IllegalStateException("line-renderer upload split restoration failed", failure);
        } finally {
            instrumentation.removeTransformer(observer);
            bridge.close();
        }
    }

    private static String sha256OrUnavailable(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            return "unavailable";
        }
    }

    boolean restored() {
        return restored;
    }
}
