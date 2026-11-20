package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.stateelision.RedundantStateElisionBridge;
import dev.turboism.adapter.cubism.optimization.stateelision.RedundantStateElisionTarget;
import dev.turboism.adapter.cubism.optimization.stateelision.RedundantStateElisionTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;

/**
 * Exact admission and restoration for the test-only redundant-GL-state elision
 * experiment on the bundled JOGL {@code GL4bcImpl}. The bundled
 * {@code jogl-all.jar} is digest-attested (the pinned artifact is identical
 * across every reviewed Editor), the {@code GL4bcImpl} class must resolve from
 * the JOGL code source, and its pre-rewrite bytes must match the official
 * class entry. On close {@link VerifiedInstall} removes the transformer and
 * retransforms {@code GL4bcImpl} so the JVM restores its original definition.
 *
 * <p>Composition: the transform touches only JOGL; host wrappers and the
 * upload-elision wrappers stay disjoint in bytecode. The uniform lifecycle
 * transformer shares {@code GL4bcImpl} — its MUTATIONS role wraps exactly the
 * program-lifecycle methods this experiment wraps as entry invalidators.
 * Retransformation replays the transformer chain on the ORIGINAL class bytes
 * in registration order, so the uniform contributor must install first (it is
 * listed earlier in the hook manifest): the lifecycle transformer then always
 * sees clean bytes for its shape checks, while this transform sees
 * lifecycle-instrumented bytes whose 21 tracked setters remain
 * shape-identical to the official ones. The final class carries both:
 * entry-level invalidation notifies ahead of the lifecycle begin/end
 * accounting. Reversing that order would make the lifecycle shape check see
 * this experiment's injected code and fail closed.</p>
 */
final class VerifiedRedundantStateElisionInstaller implements AutoCloseable {

    /** The admission row this hook froze: no kill switch of its own, no JVM predicate. */
    static final Admission ADMISSION = new Admission("TURBOISM_STATE_ELISION", PolicyCheck.SKIP, JvmCheck.ANY);

    /** The one JOGL class target this hook rewrites; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_MATCHES = 1;

    /** The close proof restores the one rewritten class; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_OWNERS = 1;

    private final InstallContext context;
    private RedundantStateElisionBridge bridge;
    private VerifiedInstall<Path> hook;
    private boolean installed;

    VerifiedRedundantStateElisionInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) {
        this.context = InstallContext.of(instrumentation, artifact, loader);
    }

    private WeaveSpec<Path> spec() {
        return new WeaveSpec<>(
                ADMISSION,
                "state elision",
                VerifiedRedundantStateElisionInstaller::resolve,
                new WeavePlan<>(VerifiedRedundantStateElisionInstaller::targets, WeavePlan.PostCheck.NONE),
                (jogl, launch) -> {
                    bridge = new RedundantStateElisionBridge();
                    return BridgeLayer.of(bridge::install, bridge::uninstall);
                },
                new WeaveEffects<>() {
                    @Override
                    public void confirmed(final VerifiedInstall<Path> installedHook) {
                        final RedundantStateElisionTransformer transformer =
                                installedHook.targets().get(0).transformer(RedundantStateElisionTransformer.class);
                        for (final var site : transformer.invalidatorNames().entrySet()) {
                            bridge.tracker().registerInvalidator(site.getKey(), site.getValue());
                        }
                    }
                });
    }

    private static Optional<Path> resolve(final Path artifact) throws Exception {
        final Path jogl = artifact.toAbsolutePath()
                .getParent()
                .resolve("jogl/jogl-all.jar")
                .normalize();
        return ReviewedHostArtifacts.CUBISM_5_3_03_JOGL.equals(HostArtifactDigest.cached(jogl))
                ? Optional.of(jogl)
                : Optional.empty();
    }

    private static List<WeaveTarget> targets(final Path jogl, final WeaveSource source) throws Exception {
        final Class<?> entry =
                Class.forName(RedundantStateElisionTarget.OWNER.replace('/', '.'), false, source.loader());
        VerifiedHookInstallerSupport.attestClassSource(
                entry, entry.getClassLoader(), jogl, "state elision loader/source mismatch: " + entry.getName());
        final byte[] reference;
        try (JarFile jar = new JarFile(jogl.toFile())) {
            reference = VerifiedHookInstallerSupport.readReferenceBytes(
                    jar, entry, "state elision reference class missing");
        }
        final RedundantStateElisionTransformer transformer =
                new RedundantStateElisionTransformer(entry.getClassLoader(), jogl, reference);
        return List.of(
                WeaveTarget.single(entry, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure));
    }

    synchronized void install() throws Exception {
        if (installed) return;
        if (hook == null) {
            if (!(VerifiedInstall.bind(spec(), context) instanceof VerifiedInstall.Attempt.Installed<Path> bound)) {
                throw new IllegalArgumentException("state elision bundled JOGL identity mismatch");
            }
            hook = bound.hook();
        }
        hook.weave();
        installed = true;
    }

    /** Total instrumented methods (tracked + invalidators) of the last rewrite. */
    int sites() {
        return hook.targets()
                .get(0)
                .transformer(RedundantStateElisionTransformer.class)
                .sites();
    }

    @Override
    public synchronized void close() {
        if (hook == null) {
            RuntimeDiagnostics.info("bootstrap", "TURBOISM_STATE_ELISION closed installed=false");
            return;
        }
        final Map<String, Long> stats = bridge.tracker().snapshot(false);
        try {
            hook.close();
        } catch (RuntimeException | Error failure) {
            RuntimeDiagnostics.info(
                    "bootstrap",
                    "TURBOISM_STATE_ELISION closed " + report(stats) + " restored=false reason=" + failure);
            throw failure;
        }
        installed = false;
        RuntimeDiagnostics.info(
                "bootstrap", "TURBOISM_STATE_ELISION closed " + report(stats) + " restored=" + hook.restored());
    }

    /** Per-method elision counters plus invalidation and clear diagnostics. */
    private static String report(final Map<String, Long> stats) {
        final StringBuilder line = new StringBuilder();
        stats.forEach((key, value) -> line.append(key).append('=').append(value).append(' '));
        return line.toString().trim();
    }

    boolean restored() {
        return hook != null && hook.restored();
    }
}
