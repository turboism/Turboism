package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.inspector.InspectorPropertyBypassBridge;
import dev.turboism.adapter.cubism.optimization.inspector.InspectorPropertyBypassTarget;
import dev.turboism.adapter.cubism.optimization.inspector.InspectorPropertyBypassTransformer;
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
 * Exact admission, weaving and restoration for the inspector visual-property bypass.
 *
 * <p>The hook is limited to reviewed Cubism 5.3 artifacts whose inspector wrappers expose the
 * reviewed alpha/colour composition setters. Every setter is verified against the official bytes
 * in the running artifact before it is rewritten; an absent seam, a changed method body or a
 * missing drawable shader entry refuses the whole hook and leaves native behaviour untouched.</p>
 */
final class VerifiedInspectorPropertyBypassInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.editor-model.inspector-property-bypass";

    private final Instrumentation instrumentation;
    private final ClassLoader hostClassLoader;
    private final String cubismVersion;
    private final Map<String, Class<?>> targets;
    private final InspectorPropertyBypassTransformer transformer;
    private final InspectorPropertyBypassBridge bridge;
    private final Set<Class<?>> retransformed = new LinkedHashSet<>();
    private boolean installed;
    private boolean restored;

    static boolean admitted(final Path artifact, final boolean requested, final int jvm) {
        if (!requested || jvm < 17) return false;
        try {
            final String version = ReviewedHostArtifacts.cubismVersionOf(HostArtifactDigest.from(artifact))
                    .orElse("");
            if (!InspectorPropertyBypassTarget.supports(version)) return false;
            try (JarFile jar = new JarFile(artifact.toFile())) {
                for (final InspectorPropertyBypassTarget.Seam seam : InspectorPropertyBypassTarget.SEAMS) {
                    if (jar.getJarEntry(seam.owner() + ".class") == null) return false;
                }
            }
            return true;
        } catch (Exception | LinkageError rejected) {
            return false;
        }
    }

    VerifiedInspectorPropertyBypassInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) throws Exception {
        this.instrumentation = instrumentation;
        this.hostClassLoader = loader;
        final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
        cubismVersion = ReviewedHostArtifacts.cubismVersionOf(digest)
                .filter(InspectorPropertyBypassTarget::supports)
                .orElseThrow(() -> new IllegalArgumentException("inspector bypass unsupported host artifact"));
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("inspector bypass requires class retransformation");
        }

        final Map<String, byte[]> references = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            for (final InspectorPropertyBypassTarget.Seam seam : InspectorPropertyBypassTarget.SEAMS) {
                final var entry = jar.getJarEntry(seam.owner() + ".class");
                if (entry == null) {
                    throw new IllegalArgumentException("inspector bypass seam absent: " + seam.owner());
                }
                try (var input = jar.getInputStream(entry)) {
                    references.put(seam.owner(), input.readAllBytes());
                }
            }
        }
        transformer = new InspectorPropertyBypassTransformer(loader, artifact, references);
        bridge = new InspectorPropertyBypassBridge(loader);

        final Map<String, Class<?>> loaded = new LinkedHashMap<>();
        for (final InspectorPropertyBypassTarget.Seam seam : InspectorPropertyBypassTarget.SEAMS) {
            final Class<?> type = Class.forName(seam.binaryName(), false, loader);
            VerifiedHookInstallerSupport.attestClassSource(
                    type, loader, artifact, "inspector bypass dependency loader/source mismatch");
            if (!instrumentation.isModifiableClass(type)) {
                throw new IllegalStateException("inspector bypass target unmodifiable: " + type.getName());
            }
            loaded.put(seam.owner(), type);
        }
        targets = Map.copyOf(loaded);
    }

    synchronized void install() throws Exception {
        if (installed) return;
        bridge.install();
        try {
            instrumentation.addTransformer(transformer, true);
            installed = true;
            final Class<?>[] all = targets.values().toArray(Class<?>[]::new);
            instrumentation.retransformClasses(all);
            retransformed.addAll(targets.values());
            if (transformer.matches() != targets.size() || transformer.failure() != null) {
                throw new IllegalStateException("inspector bypass seams not admitted: " + transformer.failure());
            }
        } catch (Exception | Error failure) {
            VerifiedHookInstallerSupport.closeOnInstallFailure(failure, this);
            throw failure;
        }
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
            // The disabled bridge keeps any remaining definition on the official path.
        } finally {
            bridge.close();
            installed = false;
        }
    }

    boolean restored() {
        return restored;
    }

    /** Reviewed generation bound to the actual artifact. */
    String cubismVersion() {
        return cubismVersion;
    }

    /** Rewritten landscape. */
    int sites() {
        return transformer.matches();
    }

    String failure() {
        return transformer.failure();
    }

    Map<String, Long> snapshot() {
        return bridge.snapshot();
    }
}
