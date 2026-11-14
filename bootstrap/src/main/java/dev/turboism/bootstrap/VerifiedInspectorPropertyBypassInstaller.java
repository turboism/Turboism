package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.inspector.InspectorPropertyBypassBridge;
import dev.turboism.adapter.cubism.optimization.inspector.InspectorPropertyBypassTarget;
import dev.turboism.adapter.cubism.optimization.inspector.InspectorPropertyBypassTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;

/**
 * Exact admission, weaving and restoration for the inspector visual-property bypass.
 *
 * <p>The hook is limited to reviewed Cubism 5.3 artifacts whose inspector wrappers expose the
 * reviewed alpha/colour composition setters. Every setter is verified against the official bytes
 * in the running artifact before it is rewritten; an absent seam, a changed method body or a
 * missing drawable shader entry refuses the whole hook and leaves native behaviour untouched.</p>
 *
 * <p>The protocol itself lives in {@link VerifiedInstall}: the six reviewed setter classes are
 * retransformed in one call and every one of them is re-captured on close, so a weave that does not
 * hash back is reported instead of passing for a complete installation.</p>
 */
final class VerifiedInspectorPropertyBypassInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.editor-model.inspector-property-bypass";

    /** The admission row this hook froze: policy id, kill-switch semantics and JVM predicate. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.SKIP, JvmCheck.AT_LEAST_17);

    /**
     * One rewritten setter per reviewed seam — the alpha and colour composition setters of the
     * ArtMesh, Part and Alias wrappers; pinned by {@code WeaveSpecTest}.
     */
    static final int EXPECTED_MATCHES = 6;

    /** The close proof re-captures all six rewritten setters; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_OWNERS = 6;

    private final InstallContext context;
    private InspectorPropertyBypassBridge bridge;
    private VerifiedInstall<String> hook;

    VerifiedInspectorPropertyBypassInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) {
        context = InstallContext.of(instrumentation, artifact, loader);
    }

    static boolean admitted(final Path artifact, final boolean requested, final int jvm) {
        // This hook's switch is the preference/validation flag, so the startup policy is never consulted.
        if (!VerifiedInstall.admits(ADMISSION, null, requested, jvm)) return false;
        try {
            return resolve(artifact).isPresent();
        } catch (Exception | LinkageError rejected) {
            return false;
        }
    }

    /**
     * @return the reviewed generation when the artifact is a reviewed 5.3 host carrying every setter
     *     seam this hook rewrites
     */
    private static Optional<String> resolve(final Path artifact) throws Exception {
        final String version = ReviewedHostArtifacts.cubismVersionOf(HostArtifactDigest.cached(artifact))
                .orElse("");
        if (!InspectorPropertyBypassTarget.supports(version)) return Optional.empty();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            for (final InspectorPropertyBypassTarget.Seam seam : InspectorPropertyBypassTarget.SEAMS) {
                if (jar.getJarEntry(seam.owner() + ".class") == null) return Optional.empty();
            }
        }
        return Optional.of(version);
    }

    private WeaveSpec<String> spec() {
        return new WeaveSpec<String>(
                ADMISSION,
                "inspector bypass",
                VerifiedInspectorPropertyBypassInstaller::resolve,
                new WeavePlan<>(VerifiedInspectorPropertyBypassInstaller::targets, WeavePlan.PostCheck.NONE),
                (version, launch) -> {
                    bridge = new InspectorPropertyBypassBridge(launch.hostLoader());
                    return BridgeLayer.of(bridge::install, bridge);
                },
                WeaveEffects.none());
    }

    private static List<WeaveTarget> targets(final String cubismVersion, final WeaveSource source) throws Exception {
        final Map<String, Class<?>> seams = new LinkedHashMap<>();
        final Map<String, byte[]> references = new LinkedHashMap<>();
        for (final InspectorPropertyBypassTarget.Seam seam : InspectorPropertyBypassTarget.SEAMS) {
            final Class<?> type = source.load(seam.owner());
            references.put(seam.owner(), source.referenceBytes(type));
            seams.put(seam.owner(), type);
        }
        final InspectorPropertyBypassTransformer transformer =
                new InspectorPropertyBypassTransformer(source.loader(), source.artifact(), references);
        final List<WeaveTarget> targets = new ArrayList<>(seams.size());
        for (final Class<?> type : seams.values()) {
            targets.add(
                    new WeaveTarget(type, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure));
        }
        return targets;
    }

    /**
     * Installs the weave through {@link VerifiedInstall}.
     *
     * @throws IllegalStateException when the artifact is not a reviewed inspector-bypass host
     * @throws Exception when registration, retransformation or the seam proof fails
     */
    synchronized void install() throws Exception {
        if (hook != null) return;
        if (!(VerifiedInstall.install(spec(), context)
                instanceof VerifiedInstall.Attempt.Installed<String> installed)) {
            throw new IllegalStateException("inspector bypass refused: host-not-supported-or-hook-disabled");
        }
        hook = installed.hook();
    }

    @Override
    public synchronized void close() {
        if (hook != null) hook.close();
    }

    boolean restored() {
        return hook != null && hook.restored();
    }

    /** Reviewed generation bound to the actual artifact. */
    String cubismVersion() {
        return hook.contract();
    }

    /** Rewritten landscape. */
    int sites() {
        return hook.targets()
                .get(0)
                .transformer(InspectorPropertyBypassTransformer.class)
                .matches();
    }

    String failure() {
        return hook.targets()
                .get(0)
                .transformer(InspectorPropertyBypassTransformer.class)
                .failure();
    }

    Map<String, Long> snapshot() {
        return bridge.snapshot();
    }
}
