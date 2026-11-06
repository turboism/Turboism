package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.composite.CanvasCompositeElisionBridge;
import dev.turboism.adapter.cubism.optimization.composite.CanvasCompositeElisionTarget;
import dev.turboism.adapter.cubism.optimization.composite.CanvasCompositeElisionTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Exact admission, dependency verification and restoration for the test-only
 * canvas-composite elision experiment. Two classes are rewritten by one transformer —
 * {@code javax/swing/RepaintManager$PaintManager} (bootstrap-loaded
 * java.desktop class, attested against the running VM's own jrt bytes) and
 * {@code com/formdev/flatlaf/ui/FlatPanelUI} (FlatLaf JAR beside the host
 * artifact, host class loader, code-source pinned). The JDK caller
 * {@code RepaintManager.paint(...)} is shape-verified as a dependency because
 * the elision relies on its direct-paint fallback. On close both classes are
 * re-captured and must hash back to their pre-rewrite SHA-256.
 *
 * <p>Composition: this transform touches java.desktop and flatlaf classes
 * only — disjoint from the host-jar ({@code ui/CWidget}), mesh and shader
 * targets of the other experiments.</p>
 *
 * <p>The protocol itself lives in {@link VerifiedInstall}. Its admission row never consults the
 * startup policy — the sole switch is {@code -Dturboism.validation.canvasCompositeElision=true} — and
 * its reference bytes come from the running VM and the FlatLaf jar rather than from the admitted
 * Editor artifact, so the target factory does its own loading and attestation.</p>
 */
final class VerifiedCanvasCompositeElisionInstaller implements AutoCloseable {

    /** The admission row this hook froze: no kill switch of its own, JVM17+ for the flatlaf entry. */
    static final Admission ADMISSION =
            new Admission("TURBOISM_CANVAS_COMPOSITE", PolicyCheck.SKIP, JvmCheck.AT_LEAST_17);

    /** The transformer's matched sites across both rewritten classes; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_MATCHES = 2;

    /** The close proof re-captures both rewritten classes; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_OWNERS = 2;

    private static final String PAINT_JRT = "/modules/java.desktop/javax/swing/RepaintManager$PaintManager.class";
    private static final String PAINT_CALLER_JRT = "/modules/java.desktop/javax/swing/RepaintManager.class";

    private final InstallContext context;
    private CanvasCompositeElisionBridge bridge;
    private VerifiedInstall<CanvasCompositeElisionTarget> hook;

    VerifiedCanvasCompositeElisionInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) {
        context = InstallContext.of(instrumentation, artifact, loader);
    }

    private static Optional<CanvasCompositeElisionTarget> resolve(final Path artifact) throws Exception {
        return CanvasCompositeElisionTarget.of(HostArtifactDigest.from(artifact));
    }

    private WeaveSpec<CanvasCompositeElisionTarget> spec(final boolean production) {
        return new WeaveSpec<CanvasCompositeElisionTarget>(
                ADMISSION,
                "canvas composite elision",
                VerifiedCanvasCompositeElisionInstaller::resolve,
                new WeavePlan<>(
                        VerifiedCanvasCompositeElisionInstaller::targets,
                        VerifiedCanvasCompositeElisionInstaller::sitesProven),
                (contract, launch) -> {
                    bridge = new CanvasCompositeElisionBridge(launch.hostLoader());
                    return BridgeLayer.of(() -> bridge.install(production), bridge);
                },
                WeaveEffects.none());
    }

    private static List<WeaveTarget> targets(final CanvasCompositeElisionTarget contract, final WeaveSource source)
            throws Exception {
        final Path flatlaf = flatlafArtifact(source.artifact());
        final byte[] paintReference = jrt(PAINT_JRT);
        final byte[] fillReference;
        try (var jar = new java.util.jar.JarFile(flatlaf.toFile())) {
            fillReference = jar.getInputStream(jar.getJarEntry("com/formdev/flatlaf/ui/FlatPanelUI.class"))
                    .readAllBytes();
        }
        final Class<?> paintClass =
                Class.forName(CanvasCompositeElisionTarget.PAINT_OWNER.replace('/', '.'), false, null);
        VerifiedHookInstallerSupport.attestJdkClass(
                paintClass, "canvas composite elision JDK target attestation failed: " + paintClass.getName());
        final Class<?> fillClass =
                Class.forName(CanvasCompositeElisionTarget.FILL_OWNER.replace('/', '.'), false, source.loader());
        VerifiedHookInstallerSupport.attestClassSource(
                fillClass,
                source.loader(),
                flatlaf,
                "canvas composite elision dependency loader/source mismatch: " + fillClass.getName());
        final CanvasCompositeElisionTransformer transformer =
                new CanvasCompositeElisionTransformer(source.loader(), flatlaf, paintReference, fillReference);
        verifyCaller(source);
        return List.of(
                new WeaveTarget(
                        paintClass,
                        transformer,
                        EXPECTED_MATCHES,
                        transformer::matches,
                        transformer::failure,
                        Set.of(CanvasCompositeElisionTarget.PAINT_OWNER),
                        transformer::beforeSha256),
                new WeaveTarget(
                        fillClass,
                        transformer,
                        EXPECTED_MATCHES,
                        transformer::matches,
                        transformer::failure,
                        Set.of(CanvasCompositeElisionTarget.FILL_OWNER),
                        transformer::beforeSha256));
    }

    /**
     * The elision relies on the JDK's direct-paint fallback: verify the caller's shape so a JDK
     * without it is refused.
     */
    private static void verifyCaller(final WeaveSource source) throws Exception {
        final byte[] caller = jrt(PAINT_CALLER_JRT);
        final List<String> expected = ReviewedMethodShape.read(
                caller,
                CanvasCompositeElisionTarget.PAINT_CALLER_OWNER,
                CanvasCompositeElisionTarget.PAINT_CALLER_METHOD,
                CanvasCompositeElisionTarget.PAINT_CALLER_DESCRIPTOR);
        final Class<?> callerClass =
                Class.forName(CanvasCompositeElisionTarget.PAINT_CALLER_OWNER.replace('/', '.'), false, null);
        VerifiedHookInstallerSupport.attestJdkClass(
                callerClass, "canvas composite elision JDK target attestation failed: " + callerClass.getName());
        if (expected == null
                || !expected.equals(ReviewedMethodShape.read(
                        VerifiedHookInstallerSupport.captureLoadedBytes(
                                source.instrumentation(),
                                callerClass,
                                "canvas composite elision class unmodifiable: " + callerClass.getName(),
                                "canvas composite elision inspection absent: " + callerClass.getName()),
                        CanvasCompositeElisionTarget.PAINT_CALLER_OWNER,
                        CanvasCompositeElisionTarget.PAINT_CALLER_METHOD,
                        CanvasCompositeElisionTarget.PAINT_CALLER_DESCRIPTOR))) {
            throw new IllegalStateException("canvas composite elision fallback caller mismatch: "
                    + CanvasCompositeElisionTarget.PAINT_CALLER_OWNER);
        }
    }

    /** The experiment injects exactly two consult sites, one per rewritten entry. */
    private static String sitesProven(final List<WeaveTarget> targets) {
        final int sites = targets.get(0)
                .transformer(CanvasCompositeElisionTransformer.class)
                .sites();
        return sites == 2 ? null : "sites=" + sites;
    }

    private static Path flatlafArtifact(final Path artifact) throws Exception {
        final Path lib = artifact.toAbsolutePath().normalize().getParent();
        try (var stream = Files.list(lib)) {
            final List<Path> matches = stream.filter(p -> {
                        final String name = p.getFileName().toString();
                        return name.startsWith("flatlaf-")
                                && name.endsWith(".jar")
                                && !name.startsWith("flatlaf-extras");
                    })
                    .sorted()
                    .toList();
            if (matches.size() != 1) {
                throw new IllegalStateException(
                        "canvas composite elision requires exactly one flatlaf jar: " + matches);
            }
            return matches.get(0).toAbsolutePath().normalize();
        }
    }

    private static byte[] jrt(final String path) throws Exception {
        return Files.readAllBytes(FileSystems.getFileSystem(URI.create("jrt:/")).getPath(path));
    }

    synchronized void install() throws Exception {
        install(false);
    }

    /**
     * Installs the weave through {@link VerifiedInstall}.
     *
     * @param production whether the bridge arms its consult immediately
     * @throws IllegalStateException when the artifact is not a reviewed canvas-composite host
     * @throws Exception when registration, retransformation or the proof fails
     */
    synchronized void install(final boolean production) throws Exception {
        if (hook == null) {
            if (!(VerifiedInstall.bind(spec(production), context)
                    instanceof VerifiedInstall.Attempt.Installed<CanvasCompositeElisionTarget> bound)) {
                throw new IllegalStateException(
                        "canvas composite elision refused: host-not-supported-or-hook-disabled");
            }
            hook = bound.hook();
        }
        hook.weave();
    }

    /** Injected consult count for the install marker log. */
    int sites() {
        return hook.targets()
                .get(0)
                .transformer(CanvasCompositeElisionTransformer.class)
                .sites();
    }

    @Override
    public synchronized void close() {
        if (hook == null) return;
        final Map<String, Long> stats = bridge.snapshot();
        try {
            hook.close();
        } catch (RuntimeException | Error failure) {
            RuntimeDiagnostics.info(
                    "bootstrap", "TURBOISM_CANVAS_COMPOSITE closed" + report(stats) + " restored=false");
            throw failure;
        }
        RuntimeDiagnostics.info(
                "bootstrap", "TURBOISM_CANVAS_COMPOSITE closed" + report(stats) + " restored=" + hook.restored());
    }

    /** Per-site counters for the close marker; gauges stay absolute. */
    private static String report(final Map<String, Long> stats) {
        return " paintCalls=" + stats.get("paintCalls")
                + " paintElided=" + stats.get("paintElided")
                + " paintPassed=" + stats.get("paintPassed")
                + " fillCalls=" + stats.get("fillCalls")
                + " fillElided=" + stats.get("fillElided")
                + " fillPassed=" + stats.get("fillPassed")
                + " observerFailures=" + stats.get("observerFailures");
    }

    boolean restored() {
        return hook != null && hook.restored();
    }
}
