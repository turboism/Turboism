package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.readback.AsyncReadbackBridge;
import dev.turboism.adapter.cubism.optimization.readback.AsyncReadbackTarget;
import dev.turboism.adapter.cubism.optimization.readback.AsyncReadbackTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * Exact admission and restoration for the opt-in pipelined canvas readback on the bundled JOGL
 * {@code GLJPanel$OffscreenBackend}.
 *
 * <p>The bundled {@code jogl-all.jar} is digest-attested, the backend class must resolve from the
 * JOGL code source, the bridge class must be visible from the same loader (the rewritten
 * {@code INVOKESTATIC} has to link), and the pre-rewrite {@code postGL} shape must equal the
 * official class entry. On close the class must hash back to its pre-rewrite SHA-256.</p>
 *
 * <p>Composition: no other Turboism transformer touches this JOGL class, so the rewrite sees the
 * official bytes on every retransformation and the shape gate is stable.</p>
 */
final class VerifiedAsyncReadbackInstaller implements AutoCloseable {

    /** Stable hook identity used by the contributor and the settings diagnostics. */
    static final String HOOK_ID = "cubism.render.async-readback";

    private final Instrumentation instrumentation;
    private final Class<?> entry;
    private final AsyncReadbackTransformer transformer;
    private final Path joglArtifact;
    private boolean installed;
    private boolean restored;
    private boolean registered;

    VerifiedAsyncReadbackInstaller(final Instrumentation instrumentation, final Path artifact, final ClassLoader loader)
            throws Exception {
        this.instrumentation = instrumentation;
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("retransform unavailable");
        }
        joglArtifact = artifact.toAbsolutePath()
                .getParent()
                .resolve("jogl/jogl-all.jar")
                .normalize();
        if (!AsyncReadbackTarget.admitted(HostArtifactDigest.from(joglArtifact))) {
            throw new IllegalArgumentException("async readback bundled JOGL identity mismatch");
        }
        entry = Class.forName(AsyncReadbackTarget.OWNER.replace('/', '.'), false, loader);
        VerifiedHookInstallerSupport.attestClassSource(
                entry,
                entry.getClassLoader(),
                joglArtifact,
                "async readback loader/source mismatch: " + entry.getName());
        // The rewritten bytecode calls the bridge statically, so it must link from the JOGL loader.
        Class.forName(AsyncReadbackTarget.BRIDGE_CLASS, false, entry.getClassLoader());
        final byte[] reference;
        try (JarFile jar = new JarFile(joglArtifact.toFile())) {
            reference = VerifiedHookInstallerSupport.readReferenceBytes(
                    jar, entry, "async readback reference class missing");
        }
        transformer = new AsyncReadbackTransformer(entry.getClassLoader(), joglArtifact, reference);
    }

    synchronized void install() throws Exception {
        if (installed) {
            return;
        }
        if (!instrumentation.isModifiableClass(entry)) {
            throw new IllegalStateException("async readback entry unmodifiable: " + entry.getName());
        }
        try {
            instrumentation.addTransformer(transformer, true);
            registered = true;
            instrumentation.retransformClasses(entry);
            if (transformer.matches() != 1 || transformer.failure() != null) {
                throw new IllegalStateException(
                        "async readback entry not admitted: " + entry.getName() + " " + transformer.failure());
            }
            AsyncReadbackBridge.install();
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

    @Override
    public synchronized void close() {
        final Map<String, Long> stats = AsyncReadbackBridge.snapshot();
        AsyncReadbackBridge.uninstall();
        if (restored || (!installed && !registered && transformer.beforeSha256() == null)) {
            dev.turboism.runtime.log.RuntimeDiagnostics.info(
                    "bootstrap", "TURBOISM_ASYNC_READBACK closed " + report(stats) + " installed=false");
            return;
        }
        if (registered) {
            instrumentation.removeTransformer(transformer);
            registered = false;
        }
        try {
            if (transformer.beforeSha256() == null) {
                restored = true;
                installed = false;
                dev.turboism.runtime.log.RuntimeDiagnostics.info(
                        "bootstrap", "TURBOISM_ASYNC_READBACK closed " + report(stats) + " restored=true");
                return;
            }
            final byte[] original = VerifiedHookInstallerSupport.captureLoadedBytes(
                    instrumentation, entry, null, "async readback class capture absent: " + entry.getName());
            if (!VerifiedHookInstallerSupport.sha256Hex(original).equals(transformer.beforeSha256())) {
                throw new IllegalStateException("async readback restoration not proven: " + entry.getName());
            }
            restored = true;
            installed = false;
        } catch (Exception failure) {
            dev.turboism.runtime.log.RuntimeDiagnostics.info(
                    "bootstrap",
                    "TURBOISM_ASYNC_READBACK closed " + report(stats) + " restored=false reason=" + failure);
            throw new IllegalStateException("async readback restoration failed", failure);
        }
        dev.turboism.runtime.log.RuntimeDiagnostics.info(
                "bootstrap", "TURBOISM_ASYNC_READBACK closed " + report(stats) + " restored=true");
    }

    private static String report(final Map<String, Long> stats) {
        final StringBuilder line = new StringBuilder();
        stats.forEach((key, value) -> line.append(key).append('=').append(value).append(' '));
        return line.toString().trim();
    }

    boolean restored() {
        return restored;
    }
}
