package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.extendedinterpolation.ExtendedInterpolationSetupBridge;
import dev.turboism.adapter.cubism.optimization.extendedinterpolation.ExtendedInterpolationSetupTarget;
import dev.turboism.adapter.cubism.optimization.extendedinterpolation.ExtendedInterpolationSetupTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Exact admission, weaving and restoration for the incremental
 * {@code CModelSource.setup()} optimization.
 *
 * <p>Admission is the reviewed artifact digest plus the artifact's own anchor proof: exactly two
 * forced extended-interpolation rebuilds in {@code setup()}, each preceded by the four reviewed
 * default arguments. An unreviewed artifact, a repackaged class or any changed anchor refuses the
 * hook and leaves the official setup path untouched.</p>
 */
final class VerifiedExtendedInterpolationSetupInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.editor-model.extended-interpolation-incremental-setup";

    private final Instrumentation instrumentation;
    private final ClassLoader hostClassLoader;
    private final String cubismVersion;
    private final ExtendedInterpolationSetupTransformer transformer;
    private final ExtendedInterpolationSetupBridge bridge;
    private final Set<Class<?>> retransformed = new LinkedHashSet<>();
    private boolean installed;
    private boolean restored;

    VerifiedExtendedInterpolationSetupInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) throws Exception {
        this.instrumentation = instrumentation;
        this.hostClassLoader = loader;
        final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
        cubismVersion = ReviewedHostArtifacts.cubismVersionOf(digest)
                .orElseThrow(() -> new IllegalArgumentException("incremental setup unsupported host artifact"));
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("incremental setup requires class retransformation");
        }
        final byte[] reference;
        try (JarFile jar = new JarFile(artifact.toFile())) {
            final var entry = jar.getJarEntry(ExtendedInterpolationSetupTarget.MODEL_SOURCE + ".class");
            if (entry == null) {
                throw new IllegalArgumentException(
                        "incremental setup owner absent: " + ExtendedInterpolationSetupTarget.MODEL_SOURCE);
            }
            try (var input = jar.getInputStream(entry)) {
                reference = input.readAllBytes();
            }
        }
        transformer = new ExtendedInterpolationSetupTransformer(loader, artifact, reference);
        bridge = new ExtendedInterpolationSetupBridge(loader);
    }

    synchronized void install() throws Exception {
        if (installed) return;
        bridge.install();
        try {
            instrumentation.addTransformer(transformer, true);
            retransformIfLoaded();
            installed = true;
        } catch (Exception | Error failure) {
            VerifiedHookInstallerSupport.closeOnInstallFailure(failure, this);
            throw failure;
        }
    }

    private void retransformIfLoaded() throws Exception {
        final Set<Class<?>> targets = new LinkedHashSet<>();
        for (final Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (loaded == null) continue;
            if (ExtendedInterpolationSetupTarget.MODEL_SOURCE.replace('/', '.').equals(loaded.getName())
                    && loaded.getClassLoader() == hostClassLoader) {
                targets.add(loaded);
            }
        }
        if (!targets.isEmpty()) {
            instrumentation.retransformClasses(targets.toArray(Class<?>[]::new));
            retransformed.addAll(targets);
        }
    }

    int sites() {
        return transformer.sites();
    }

    String cubismVersion() {
        return cubismVersion;
    }

    String failure() {
        return transformer.failure();
    }

    Map<String, Long> snapshot() {
        return bridge.snapshot();
    }

    @Override
    public synchronized void close() throws Exception {
        if (restored) return;
        restored = true;
        try {
            instrumentation.removeTransformer(transformer);
            if (!retransformed.isEmpty()) {
                instrumentation.retransformClasses(retransformed.toArray(Class<?>[]::new));
                retransformed.clear();
            }
        } catch (RuntimeException ignored) {
            /* The disabled bridge keeps any remaining definition on the official path. */
        } finally {
            bridge.close();
            installed = false;
        }
    }
}
