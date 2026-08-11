package dev.turboism.adapter.cubism.performance;

import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Non-mutating rollback evidence observer pinned to the same host loader and
 * artifact as {@link PerformanceProbeMethodTransformer}. During cleanup
 * retransformation (after the mutating transformer is removed) it records the
 * SHA-256 of the restored bytes it actually sees, exactly once per target owner.
 */
public final class PerformanceProbeRollbackObserver implements ClassFileTransformer {

    private final ClassLoader expectedLoader;
    private final Path expectedArtifact;
    private final Set<String> ownerInternalNames;
    private final ConcurrentHashMap<String, String> observed = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private final AtomicBoolean restoring = new AtomicBoolean();

    public PerformanceProbeRollbackObserver(
        final ClassLoader expectedLoader,
        final Path expectedArtifact,
        final List<PerformanceProbeMethodTransformer.Target> targets
    ) {
        this.expectedLoader = expectedLoader;
        this.expectedArtifact = expectedArtifact == null ? null : expectedArtifact.toAbsolutePath().normalize();
        this.ownerInternalNames = targets.stream()
            .map(PerformanceProbeMethodTransformer.Target::ownerInternalName)
            .collect(Collectors.toUnmodifiableSet());
    }

    /** Arm restoration observation; must be called before the cleanup retransformation. */
    public void beginRestoration() {
        restoring.set(true);
    }

    @Override
    public byte[] transform(
        final Module module,
        final ClassLoader loader,
        final String className,
        final Class<?> classBeingRedefined,
        final ProtectionDomain protectionDomain,
        final byte[] classfileBuffer
    ) {
        if (!restoring.get() || classfileBuffer == null) return null;
        if (expectedLoader != null && loader != expectedLoader) return null;
        if (expectedArtifact != null
            && !PerformanceProbeMethodTransformer.comesFromArtifact(protectionDomain, expectedArtifact)) {
            return null;
        }
        if (!ownerInternalNames.contains(className)) return null;
        observed.put(className, PerformanceProbeMethodTransformer.sha256(classfileBuffer));
        counts.computeIfAbsent(className, ignored -> new AtomicInteger()).incrementAndGet();
        return null;
    }

    /** Restored-byte SHA-256 keyed by owner internal name. */
    public Map<String, String> observedSha256() {
        return Map.copyOf(observed);
    }

    /** Restoration observations per owner internal name; each owner must be observed exactly once. */
    public Map<String, Integer> observationCounts() {
        final Map<String, Integer> copy = new HashMap<>();
        counts.forEach((owner, count) -> copy.put(owner, count.get()));
        return Map.copyOf(copy);
    }
}
