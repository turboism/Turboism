package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.inputpath.InputPathElisionBridge;
import dev.turboism.adapter.cubism.optimization.inputpath.InputPathElisionTarget;
import dev.turboism.adapter.cubism.optimization.inputpath.InputPathElisionTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Exact admission, dependency verification and restoration for the input-path elision transform,
 * in its validation and production modes. One host class is rewritten —
 * {@code com/live2d/ui/CWidget}, inside {@code requestFocus()V} and
 * {@code setCursor(Lcom/live2d/type/CCursor;)V}. The bridge reads
 * {@code CWidget.getJComponent()} and {@code CCursor.getJCursor()}; every such
 * dependency body is verified against the official artifact before any rewrite.
 * On close the class is re-captured and must hash back to its pre-rewrite
 * SHA-256.
 *
 * <p>Composition: the transform touches only {@code ui/CWidget}; the
 * upload-elision transform targets {@code mesh/a/*}, the glGetError elision
 * {@code shader/A.a(GL,String,Z)} and the uniform-location hooks
 * {@code shader/*} plus JOGL classes — disjoint owners, so this experiment
 * coexists with all of them.</p>
 *
 * <p>The protocol itself lives in {@link VerifiedInstall}. The validation mode never consults the
 * startup policy — the harness owns its gate — while the production mode keeps the kill switch.</p>
 */
final class VerifiedInputPathElisionInstaller implements AutoCloseable {

    /** Startup hook id used by the safe-mode / disabledHooks policy. */
    static final String HOOK_ID = "cubism.render.input-path-elision";

    /** The admission row this hook froze: policy id, kill-switch semantics and JVM predicate. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    /** The validation-mode row: the harness's system property is the only switch. */
    private static final Admission VALIDATION_ADMISSION =
            new Admission(HOOK_ID, PolicyCheck.SKIP, JvmCheck.AT_LEAST_17);

    /** The one rewritten entry method; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_MATCHES = 1;

    /** The close proof re-captures the one rewritten class; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_OWNERS = 1;

    /** Exact production admission: reviewed digest + JVM17 + hook policy. */
    static boolean admitted(
            final HostArtifactDigest digest,
            final RuntimeStartupConfig config,
            final boolean requested,
            final Runtime.Version jvm) {
        return VerifiedInstall.admits(
                ADMISSION,
                config,
                requested,
                jvm.feature(),
                () -> InputPathElisionTarget.of(digest).isPresent());
    }

    private final InstallContext context;
    private InputPathElisionBridge bridge;
    private VerifiedInstall<InputPathElisionTarget> hook;

    VerifiedInputPathElisionInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) {
        this(InstallContext.of(instrumentation, artifact, loader));
    }

    VerifiedInputPathElisionInstaller(final InstallContext context) {
        this.context = context;
    }

    private WeaveSpec<InputPathElisionTarget> spec(final boolean production) {
        return new WeaveSpec<InputPathElisionTarget>(
                production ? ADMISSION : VALIDATION_ADMISSION,
                "input path elision",
                VerifiedInputPathElisionInstaller::resolve,
                new WeavePlan<>(
                        VerifiedInputPathElisionInstaller::targets, VerifiedInputPathElisionInstaller::sitesProven),
                (target, launch) -> {
                    bridge = new InputPathElisionBridge(launch.hostLoader());
                    return BridgeLayer.of(() -> bridge.install(production), bridge);
                },
                WeaveEffects.none());
    }

    private static Optional<InputPathElisionTarget> resolve(final Path artifact) throws Exception {
        return InputPathElisionTarget.of(HostArtifactDigest.cached(artifact));
    }

    private static List<WeaveTarget> targets(final InputPathElisionTarget target, final WeaveSource source)
            throws Exception {
        final Class<?> entry = source.load(InputPathElisionTarget.OWNER);
        final InputPathElisionTransformer transformer =
                new InputPathElisionTransformer(source.loader(), source.artifact(), source.referenceBytes(entry));
        verify(
                source,
                InputPathElisionTarget.OWNER,
                InputPathElisionTarget.COMPONENT_METHOD,
                InputPathElisionTarget.COMPONENT_DESCRIPTOR);
        verify(
                source,
                InputPathElisionTarget.CURSOR_OWNER,
                InputPathElisionTarget.CURSOR_ACCESSOR,
                InputPathElisionTarget.CURSOR_ACCESSOR_DESCRIPTOR);
        return List.of(
                WeaveTarget.single(entry, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure));
    }

    private static void verify(
            final WeaveSource source, final String owner, final String method, final String descriptor)
            throws Exception {
        final Class<?> type = source.load(owner);
        final byte[] actual = VerifiedHookInstallerSupport.captureLoadedBytes(
                source.instrumentation(),
                type,
                "input path elision class unmodifiable: " + type.getName(),
                "input path elision dependency inspection absent: " + type.getName());
        final List<String> expected = ReviewedMethodShape.read(source.referenceBytes(type), owner, method, descriptor);
        if (expected == null || !expected.equals(ReviewedMethodShape.read(actual, owner, method, descriptor))) {
            throw new IllegalStateException("input path elision dependency body mismatch: " + owner + "." + method);
        }
    }

    /** The experiment injects exactly two consult sites, one per rewritten method. */
    private static String sitesProven(final List<WeaveTarget> targets) {
        final int sites =
                targets.get(0).transformer(InputPathElisionTransformer.class).sites();
        return sites == 2 ? null : "sites=" + sites;
    }

    synchronized void install() throws Exception {
        install(false);
    }

    /**
     * Installs the weave through {@link VerifiedInstall}.
     *
     * @param production whether the bridge arms its consult immediately and the kill switch applies
     * @throws IllegalStateException when the artifact is not a reviewed input-path host
     * @throws Exception when registration, retransformation or the proof fails
     */
    synchronized void install(final boolean production) throws Exception {
        if (hook == null) {
            if (!(VerifiedInstall.bind(spec(production), context)
                    instanceof VerifiedInstall.Attempt.Installed<InputPathElisionTarget> bound)) {
                throw new IllegalStateException("input path elision refused: host-not-supported-or-hook-disabled");
            }
            hook = bound.hook();
        }
        hook.weave();
    }

    /** Injected consult count for the install marker log. */
    int sites() {
        return hook.targets()
                .get(0)
                .transformer(InputPathElisionTransformer.class)
                .sites();
    }

    @Override
    public synchronized void close() {
        if (hook == null) return;
        final Map<String, Long> stats = bridge.snapshot();
        try {
            hook.close();
        } catch (RuntimeException | Error failure) {
            log(stats, false);
            throw failure;
        }
        log(stats, hook.restored());
    }

    private static void log(final Map<String, Long> stats, final boolean restored) {
        RuntimeDiagnostics.info("bootstrap", "TURBOISM_INPUT_PATH closed" + report(stats) + " restored=" + restored);
    }

    /** Per-site counters for the close marker; gauges stay absolute. */
    private static String report(final Map<String, Long> stats) {
        return " focusCalls=" + stats.get("focusCalls")
                + " focusElided=" + stats.get("focusElided")
                + " focusPassed=" + stats.get("focusPassed")
                + " cursorCalls=" + stats.get("cursorCalls")
                + " cursorElided=" + stats.get("cursorElided")
                + " cursorPassed=" + stats.get("cursorPassed")
                + " observerFailures=" + stats.get("observerFailures");
    }

    boolean restored() {
        return hook != null && hook.restored();
    }
}
