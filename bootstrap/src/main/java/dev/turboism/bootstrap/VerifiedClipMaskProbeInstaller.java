package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.performance.ClipMaskProbeBridge;
import dev.turboism.adapter.cubism.performance.ClipMaskProbeMethodTransformer;
import dev.turboism.adapter.cubism.performance.ClipMaskProbeTargets;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;

/**
 * Exact admission, weaving and restoration for the measurement-only clipping-mask
 * probe.
 *
 * <p>The probe observes the reviewed render path on every admitted Cubism version. It
 * is not a product optimization: the woven bytes only consult JDK functional-interface
 * slots, so a refused or failed installation leaves the host byte-identical and merely
 * measures nothing. Admission still fails closed — the artifact must be a reviewed
 * digest, the loader and code source must be attested, and every selector's
 * {@code ReviewedMethodShape} must equal the official artifact's reference shape.</p>
 *
 * <p>Owners load with the modeling document rather than the agent, so the transformer
 * is registered retransformation-capable: already-loaded owners are retransformed now
 * and later ones are woven on load. The install marker therefore reports which owners
 * are still pending; the workload-side {@code clipMask.active} counter is the real
 * evidence that the probe is live.</p>
 */
final class VerifiedClipMaskProbeInstaller implements AutoCloseable {

    private final Instrumentation instrumentation;
    private final ClassLoader hostClassLoader;
    private final String cubismVersion;
    private final String artifactSha256;
    private final List<ClipMaskProbeTargets.Slot> slots;
    private final Set<String> owners;
    private final ClipMaskProbeMethodTransformer transformer;
    private final ClipMaskProbeBridge bridge;
    private final Set<Class<?>> retransformed = new LinkedHashSet<>();
    private volatile Set<String> pendingOwners = Set.of();
    private boolean installed;
    private boolean restored;

    VerifiedClipMaskProbeInstaller(final Instrumentation instrumentation, final Path artifact, final ClassLoader loader)
            throws Exception {
        this.instrumentation = instrumentation;
        this.hostClassLoader = loader;
        final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
        cubismVersion = ReviewedHostArtifacts.cubismVersionOf(digest)
                .orElseThrow(() -> new IllegalArgumentException("clip mask probe unsupported host artifact"));
        artifactSha256 = digest.sha256();
        slots = ClipMaskProbeTargets.forVersion(cubismVersion);
        owners = Set.copyOf(ClipMaskProbeMethodTransformer.owners(slots));
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("clip mask probe requires class retransformation");
        }
        final Map<String, byte[]> references = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            for (final String owner : owners) {
                final var entry = jar.getJarEntry(owner + ".class");
                if (entry == null) {
                    throw new IllegalArgumentException("clip mask probe selector owner absent: " + owner);
                }
                try (var input = jar.getInputStream(entry)) {
                    references.put(owner, input.readAllBytes());
                }
            }
        }
        transformer = new ClipMaskProbeMethodTransformer(loader, artifact, references, slots);
        bridge = new ClipMaskProbeBridge(loader);
    }

    synchronized void install() throws Exception {
        if (installed) return;
        bridge.install();
        try {
            instrumentation.addTransformer(transformer, true);
            retransformIfLoaded();
            installed = true;
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

    private void retransformIfLoaded() throws Exception {
        final Set<String> pending = new TreeSet<>(owners);
        for (final Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (loaded == null
                    || loaded.getClassLoader() != hostClassLoader
                    || !owners.contains(loaded.getName().replace('.', '/'))) {
                continue;
            }
            pending.remove(loaded.getName().replace('.', '/'));
            if (!instrumentation.isModifiableClass(loaded)) continue;
            instrumentation.retransformClasses(loaded);
            retransformed.add(loaded);
        }
        pendingOwners = pending;
    }

    /** @return how many consults were injected so far */
    int sites() {
        return transformer.sites();
    }

    /** @return the owners that were not loaded at install time and will be woven on load */
    Set<String> pendingOwners() {
        return pendingOwners;
    }

    /**
     * @return the owners already woven; an owner absent from this set either has not
     *     loaded yet or failed its shape gate
     */
    Set<String> wovenOwners() {
        return new TreeSet<>(transformer.beforeSha256().keySet());
    }

    /** @return the latest refusal reason, or {@code null} when every transform matched */
    String failure() {
        return transformer.failure();
    }

    String cubismVersion() {
        return cubismVersion;
    }

    String artifactSha256() {
        return artifactSha256;
    }

    @Override
    public synchronized void close() {
        final Map<String, Long> stats = bridge.snapshot();
        bridge.close();
        if (!installed || restored) {
            installed = false;
            return;
        }
        instrumentation.removeTransformer(transformer);
        Throwable failure = null;
        final List<Class<?>> verify = new ArrayList<>(retransformed);
        for (final Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (loaded != null
                    && loaded.getClassLoader() == hostClassLoader
                    && transformer.beforeSha256().containsKey(loaded.getName().replace('.', '/'))
                    && instrumentation.isModifiableClass(loaded)
                    && !verify.contains(loaded)) {
                verify.add(loaded);
            }
        }
        for (final Class<?> type : verify) {
            final String before = transformer.beforeSha256().get(type.getName().replace('.', '/'));
            if (before == null) continue;
            try {
                if (!instrumentation.isModifiableClass(type)) continue;
                instrumentation.retransformClasses(type);
                final String hash =
                        VerifiedHookInstallerSupport.sha256Hex(VerifiedHookInstallerSupport.captureLoadedBytes(
                                instrumentation,
                                type,
                                "clip mask probe class unmodifiable: " + type.getName(),
                                "clip mask probe inspection absent: " + type.getName()));
                if (!hash.equals(before)) {
                    throw new IllegalStateException("clip mask probe restoration not proven: " + type.getName());
                }
            } catch (Exception | Error problem) {
                // @containment-exempt: problem is merged and rethrown below with fatal error prioritized
                failure = dev.turboism.core.runtime.work.FatalErrors.mergeForCleanup(failure, problem);
            }
        }
        installed = false;
        restored = failure == null;
        dev.turboism.runtime.log.RuntimeDiagnostics.info(
                "bootstrap",
                "TURBOISM_CLIP_MASK_PROBE closed" + ClipMaskProbeBridge.report(stats) + " restored=" + restored);
        if (failure != null) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
            throw new IllegalStateException("clip mask probe restoration failed", failure);
        }
    }

    /** @return whether every woven class hashed back to its pre-probe bytes on close */
    boolean restored() {
        return restored;
    }
}
