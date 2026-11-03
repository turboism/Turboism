package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.clipmask.ClipMaskDeduplicationBridge;
import dev.turboism.adapter.cubism.optimization.clipmask.ClipMaskDeduplicationTarget;
import dev.turboism.adapter.cubism.optimization.clipmask.ClipMaskDeduplicationTransformer;
import dev.turboism.adapter.cubism.optimization.clipmask.ClipMaskScissorBridge;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Exact admission, weaving and restoration for the zero-quality-loss clipping-mask
 * deduplication optimization.
 */
final class VerifiedClipMaskDeduplicationInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.render.clip-mask-dedupe";

    private final Instrumentation instrumentation;
    private final ClassLoader hostClassLoader;
    private final String cubismVersion;
    private final String renderSystemOwner;
    private final Set<String> owners;
    private final ClipMaskDeduplicationTransformer transformer;
    private final ClipMaskDeduplicationBridge bridge;
    private ClipMaskScissorBridge scissorBridge;
    private String scissorFailure;
    private final Set<Class<?>> retransformed = new LinkedHashSet<>();
    private boolean installed;
    private boolean restored;

    VerifiedClipMaskDeduplicationInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) throws Exception {
        this.instrumentation = instrumentation;
        this.hostClassLoader = loader;
        final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
        cubismVersion = ReviewedHostArtifacts.cubismVersionOf(digest)
                .orElseThrow(() -> new IllegalArgumentException("clip mask dedupe unsupported host artifact"));
        renderSystemOwner = ClipMaskDeduplicationTarget.renderSystemOwner(cubismVersion);
        final String renderMethodDesc = ClipMaskDeduplicationTarget.renderMethodDescriptor(cubismVersion);
        owners = Set.of(renderSystemOwner);

        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("clip mask dedupe requires class retransformation");
        }

        final Map<String, byte[]> references = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            final var entry = jar.getJarEntry(renderSystemOwner + ".class");
            if (entry == null) {
                throw new IllegalArgumentException("clip mask dedupe selector owner absent: " + renderSystemOwner);
            }
            try (var input = jar.getInputStream(entry)) {
                references.put(renderSystemOwner, input.readAllBytes());
            }
        }
        transformer =
                new ClipMaskDeduplicationTransformer(loader, artifact, renderSystemOwner, renderMethodDesc, references);
        bridge = new ClipMaskDeduplicationBridge(loader, cubismVersion);
        try {
            scissorBridge = new ClipMaskScissorBridge(loader, cubismVersion);
        } catch (Exception | LinkageError refused) {
            scissorFailure = refused.toString();
        }
    }

    synchronized void install() throws Exception {
        if (installed) return;
        bridge.attachScissorBridge(scissorBridge);
        bridge.install();
        if (scissorBridge != null) {
            try {
                scissorBridge.install();
            } catch (Exception | LinkageError refused) {
                scissorFailure = refused.toString();
                bridge.attachScissorBridge(null);
                try {
                    scissorBridge.close();
                } catch (Exception | LinkageError ignored) {
                    // The bridge refused installation before owning any slot.
                }
                scissorBridge = null;
            }
        }
        try {
            instrumentation.addTransformer(transformer, true);
            retransformIfLoaded();
            installed = true;
        } catch (Exception | Error failure) {
            try {
                close();
            } catch (Exception | Error cleanup) {
                dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(
                        dev.turboism.core.runtime.work.FatalErrors.mergeForCleanup(failure, cleanup));
            }
            throw failure;
        }
    }

    private void retransformIfLoaded() throws Exception {
        final Set<Class<?>> targets = new LinkedHashSet<>();
        for (final Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (loaded == null) continue;
            final String name = loaded.getName().replace('.', '/');
            if (owners.contains(name) && loaded.getClassLoader() == hostClassLoader) {
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

    String scissorFailure() {
        return scissorFailure;
    }

    Map<String, Long> snapshot() {
        final Map<String, Long> merged = new LinkedHashMap<>(bridge.snapshot());
        if (scissorBridge != null) {
            scissorBridge.snapshot().forEach((key, value) -> merged.put("scissor." + key, value));
        } else {
            merged.put("scissor.active", 0L);
        }
        return Map.copyOf(merged);
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
        } finally {
            try {
                if (scissorBridge != null) scissorBridge.close();
            } finally {
                bridge.close();
                installed = false;
            }
        }
    }
}
