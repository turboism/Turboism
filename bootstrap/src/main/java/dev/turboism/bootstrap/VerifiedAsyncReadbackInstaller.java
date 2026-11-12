package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.readback.AsyncReadbackBridge;
import dev.turboism.adapter.cubism.optimization.readback.AsyncReadbackTarget;
import dev.turboism.adapter.cubism.optimization.readback.AsyncReadbackTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 *
 * <p>The protocol itself lives in {@link VerifiedInstall}. Its admission row never consults the
 * startup policy — the sole switch is the persisted {@code launcher.asyncReadback} preference, which
 * production gates before the hook is installed — and its contract payload is the attested JOGL
 * artifact, because every reference byte this weave reads comes from there rather than from the
 * Editor jar.</p>
 */
final class VerifiedAsyncReadbackInstaller implements AutoCloseable {

    /** Stable hook identity used by the contributor and the settings diagnostics. */
    static final String HOOK_ID = "cubism.render.async-readback";

    /** The admission row this hook froze: no kill switch of its own, no JVM predicate. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.SKIP, JvmCheck.ANY);

    /** The one {@code postGL} readback site this hook rewrites; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_MATCHES = 1;

    /** The close proof re-captures the one rewritten class; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_OWNERS = 1;

    static final WeaveSpec<Path> SPEC = new WeaveSpec<Path>(
            ADMISSION,
            "async readback",
            VerifiedAsyncReadbackInstaller::resolve,
            new WeavePlan<>(VerifiedAsyncReadbackInstaller::targets, WeavePlan.PostCheck.NONE),
            (jogl, context) -> BridgeLayer.of(AsyncReadbackBridge::install, AsyncReadbackBridge::uninstall),
            WeaveEffects.none());

    private final InstallContext context;
    private VerifiedInstall<Path> hook;

    VerifiedAsyncReadbackInstaller(final InstallContext context) {
        this.context = context;
    }

    private static Optional<Path> resolve(final Path artifact) throws Exception {
        final Path jogl = joglArtifact(artifact);
        return AsyncReadbackTarget.admitted(HostArtifactDigest.from(jogl)) ? Optional.of(jogl) : Optional.empty();
    }

    private static Path joglArtifact(final Path artifact) {
        return artifact.toAbsolutePath()
                .getParent()
                .resolve("jogl/jogl-all.jar")
                .normalize();
    }

    private static List<WeaveTarget> targets(final Path jogl, final WeaveSource source) throws Exception {
        final Class<?> entry = Class.forName(AsyncReadbackTarget.OWNER.replace('/', '.'), false, source.loader());
        VerifiedHookInstallerSupport.attestClassSource(
                entry, entry.getClassLoader(), jogl, "async readback loader/source mismatch: " + entry.getName());
        // The rewritten bytecode calls the bridge statically, so it must link from the JOGL loader.
        Class.forName(AsyncReadbackTarget.BRIDGE_CLASS, false, entry.getClassLoader());
        final byte[] reference;
        try (JarFile jar = new JarFile(jogl.toFile())) {
            reference = VerifiedHookInstallerSupport.readReferenceBytes(
                    jar, entry, "async readback reference class missing");
        }
        final AsyncReadbackTransformer transformer =
                new AsyncReadbackTransformer(entry.getClassLoader(), jogl, reference);
        return List.of(
                WeaveTarget.single(entry, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure));
    }

    /**
     * Installs the weave through {@link VerifiedInstall}.
     *
     * @throws IllegalArgumentException when the bundled JOGL artifact is not the reviewed one
     * @throws Exception when registration, retransformation or the match proof fails
     */
    synchronized void install() throws Exception {
        if (hook != null) return;
        if (!(VerifiedInstall.install(SPEC, context) instanceof VerifiedInstall.Attempt.Installed<Path> installed)) {
            throw new IllegalArgumentException("async readback bundled JOGL identity mismatch");
        }
        hook = installed.hook();
    }

    /**
     * Unweaves, proves the restoration and reports the counters the workload reconciles against.
     * A close without an installed handle reports the same {@code installed=false} line the
     * hand-rolled protocol always carried.
     */
    @Override
    public synchronized void close() {
        if (hook == null) {
            log("TURBOISM_ASYNC_READBACK closed " + report(AsyncReadbackBridge.snapshot()) + " installed=false");
            return;
        }
        final Map<String, Long> stats = AsyncReadbackBridge.snapshot();
        try {
            hook.close();
        } catch (RuntimeException | Error failure) {
            log("TURBOISM_ASYNC_READBACK closed " + report(stats) + " restored=false reason=" + failure);
            throw failure;
        }
        log("TURBOISM_ASYNC_READBACK closed " + report(stats) + " restored=" + hook.restored());
    }

    boolean restored() {
        return hook != null && hook.restored();
    }

    private static void log(final String line) {
        RuntimeDiagnostics.info("bootstrap", line);
    }

    private static String report(final Map<String, Long> stats) {
        final StringBuilder line = new StringBuilder();
        stats.forEach((key, value) -> line.append(key).append('=').append(value).append(' '));
        return line.toString().trim();
    }
}
