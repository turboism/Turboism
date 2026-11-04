package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.stateelision.RedundantStateElisionBridge;
import dev.turboism.adapter.cubism.optimization.stateelision.RedundantStateElisionTarget;
import dev.turboism.adapter.cubism.optimization.stateelision.RedundantStateElisionTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * Exact admission and restoration for the test-only redundant-GL-state elision
 * experiment on the bundled JOGL {@code GL4bcImpl}. The bundled
 * {@code jogl-all.jar} is digest-attested (the pinned artifact is identical
 * across every reviewed Editor), the {@code GL4bcImpl} class must resolve from
 * the JOGL code source, and its pre-rewrite bytes must match the official
 * class entry. On close the class must hash back to its pre-rewrite SHA-256.
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

    private final Instrumentation instrumentation;
    private final Class<?> entry;
    private final RedundantStateElisionTransformer transformer;
    private final RedundantStateElisionBridge bridge;
    private final Path joglArtifact;
    private boolean installed, restored, registered;

    VerifiedRedundantStateElisionInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) throws Exception {
        this.instrumentation = instrumentation;
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("retransform unavailable");
        }
        joglArtifact = artifact.toAbsolutePath()
                .getParent()
                .resolve("jogl/jogl-all.jar")
                .normalize();
        if (!ReviewedHostArtifacts.CUBISM_5_3_03_JOGL.equals(HostArtifactDigest.from(joglArtifact))) {
            throw new IllegalArgumentException("state elision bundled JOGL identity mismatch");
        }
        entry = Class.forName(RedundantStateElisionTarget.OWNER.replace('/', '.'), false, loader);
        VerifiedHookInstallerSupport.attestClassSource(
                entry,
                entry.getClassLoader(),
                joglArtifact,
                "state elision loader/source mismatch: " + entry.getName());
        final byte[] reference;
        try (JarFile jar = new JarFile(joglArtifact.toFile())) {
            reference = VerifiedHookInstallerSupport.readReferenceBytes(
                    jar, entry, "state elision reference class missing");
        }
        transformer = new RedundantStateElisionTransformer(entry.getClassLoader(), joglArtifact, reference);
        bridge = new RedundantStateElisionBridge();
    }

    synchronized void install() throws Exception {
        if (installed) return;
        if (!instrumentation.isModifiableClass(entry)) {
            throw new IllegalStateException("state elision entry unmodifiable: " + entry.getName());
        }
        try {
            instrumentation.addTransformer(transformer, true);
            registered = true;
            instrumentation.retransformClasses(entry);
            if (transformer.matches() != 1 || transformer.failure() != null) {
                throw new IllegalStateException(
                        "state elision entry not admitted: " + entry.getName() + " " + transformer.failure());
            }
            for (final var site : transformer.invalidatorNames().entrySet()) {
                bridge.tracker().registerInvalidator(site.getKey(), site.getValue());
            }
            bridge.install();
            installed = true;
        } catch (Exception | Error failure) {
            VerifiedHookInstallerSupport.closeOnInstallFailure(failure, this);
            throw failure;
        }
    }

    /** Total instrumented methods (tracked + invalidators) of the last rewrite. */
    int sites() {
        return transformer.sites();
    }

    @Override
    public synchronized void close() {
        final Map<String, Long> stats = bridge.tracker().snapshot(false);
        bridge.uninstall();
        if (restored || (!installed && !registered && transformer.beforeSha256() == null)) {
            dev.turboism.runtime.log.RuntimeDiagnostics.info(
                    "bootstrap", "TURBOISM_STATE_ELISION closed " + report(stats) + " installed=false");
            return;
        }
        if (registered) {
            instrumentation.removeTransformer(transformer);
            registered = false;
        }
        try {
            // A transformer that never observed bytes rewrote nothing.
            if (transformer.beforeSha256() == null) {
                restored = true;
                installed = false;
                dev.turboism.runtime.log.RuntimeDiagnostics.info(
                        "bootstrap", "TURBOISM_STATE_ELISION closed " + report(stats) + " restored=true");
                return;
            }
            final byte[] original = VerifiedHookInstallerSupport.captureLoadedBytes(
                    instrumentation, entry, null, "state elision class capture absent: " + entry.getName());
            final String hash = VerifiedHookInstallerSupport.sha256Hex(original);
            if (!hash.equals(transformer.beforeSha256())) {
                throw new IllegalStateException("state elision restoration not proven: " + entry.getName());
            }
            restored = true;
            installed = false;
        } catch (Exception failure) {
            dev.turboism.runtime.log.RuntimeDiagnostics.info(
                    "bootstrap",
                    "TURBOISM_STATE_ELISION closed " + report(stats) + " restored=false reason=" + failure);
            throw new IllegalStateException("state elision restoration failed", failure);
        }
        dev.turboism.runtime.log.RuntimeDiagnostics.info(
                "bootstrap", "TURBOISM_STATE_ELISION closed " + report(stats) + " restored=true");
    }

    /** Per-method elision counters plus invalidation and clear diagnostics. */
    private static String report(final Map<String, Long> stats) {
        final StringBuilder line = new StringBuilder();
        stats.forEach((key, value) -> line.append(key).append('=').append(value).append(' '));
        return line.toString().trim();
    }

    boolean restored() {
        return restored;
    }
}
