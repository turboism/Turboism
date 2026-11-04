package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.composite.CanvasCompositeElisionBridge;
import dev.turboism.adapter.cubism.optimization.composite.CanvasCompositeElisionTarget;
import dev.turboism.adapter.cubism.optimization.composite.CanvasCompositeElisionTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * Exact admission, dependency verification and restoration for the test-only
 * canvas-composite elision experiment. Two classes are rewritten —
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
 */
final class VerifiedCanvasCompositeElisionInstaller implements AutoCloseable {

    private static final String PAINT_JRT = "/modules/java.desktop/javax/swing/RepaintManager$PaintManager.class";
    private static final String PAINT_CALLER_JRT = "/modules/java.desktop/javax/swing/RepaintManager.class";

    private final Instrumentation instrumentation;
    private final Class<?> paintClass;
    private final Class<?> fillClass;
    private final CanvasCompositeElisionTransformer transformer;
    private final CanvasCompositeElisionBridge bridge;
    private boolean installed, restored, registered;

    VerifiedCanvasCompositeElisionInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) throws Exception {
        CanvasCompositeElisionTarget.of(HostArtifactDigest.from(artifact))
                .orElseThrow(() -> new IllegalArgumentException("canvas composite elision unsupported host artifact"));
        if (Runtime.version().feature() < 17) {
            throw new IllegalArgumentException("canvas composite elision requires JVM17+");
        }
        this.instrumentation = instrumentation;
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("retransform unavailable");
        }
        final Path flatlaf = flatlafArtifact(artifact);
        final byte[] paintReference = jrt(PAINT_JRT);
        final byte[] fillReference;
        try (JarFile jar = new JarFile(flatlaf.toFile())) {
            fillReference = jar.getInputStream(jar.getJarEntry("com/formdev/flatlaf/ui/FlatPanelUI.class"))
                    .readAllBytes();
        }
        paintClass = Class.forName(CanvasCompositeElisionTarget.PAINT_OWNER.replace('/', '.'), false, null);
        VerifiedHookInstallerSupport.attestJdkClass(
                paintClass, "canvas composite elision JDK target attestation failed: " + paintClass.getName());
        fillClass = Class.forName(CanvasCompositeElisionTarget.FILL_OWNER.replace('/', '.'), false, loader);
        VerifiedHookInstallerSupport.attestClassSource(
                fillClass,
                loader,
                flatlaf,
                "canvas composite elision dependency loader/source mismatch: " + fillClass.getName());
        transformer = new CanvasCompositeElisionTransformer(loader, flatlaf, paintReference, fillReference);
        // The elision relies on the JDK's direct-paint fallback: verify the
        // caller's shape so a JDK without it is refused.
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
                                instrumentation,
                                callerClass,
                                "canvas composite elision class unmodifiable: " + callerClass.getName(),
                                "canvas composite elision inspection absent: " + callerClass.getName()),
                        CanvasCompositeElisionTarget.PAINT_CALLER_OWNER,
                        CanvasCompositeElisionTarget.PAINT_CALLER_METHOD,
                        CanvasCompositeElisionTarget.PAINT_CALLER_DESCRIPTOR))) {
            throw new IllegalStateException("canvas composite elision fallback caller mismatch: "
                    + CanvasCompositeElisionTarget.PAINT_CALLER_OWNER);
        }
        bridge = new CanvasCompositeElisionBridge(loader);
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

    synchronized void install(final boolean production) throws Exception {
        if (installed) return;
        for (Class<?> entry : List.of(paintClass, fillClass)) {
            if (!instrumentation.isModifiableClass(entry)) {
                throw new IllegalStateException("canvas composite elision entry unmodifiable: " + entry.getName());
            }
        }
        bridge.install(production);
        try {
            instrumentation.addTransformer(transformer, true);
            registered = true;
            instrumentation.retransformClasses(paintClass, fillClass);
            if (transformer.matches() != 2 || transformer.sites() != 2 || transformer.failure() != null) {
                throw new IllegalStateException(
                        "canvas composite elision entries not admitted: " + transformer.failure());
            }
            installed = true;
        } catch (Exception | Error failure) {
            VerifiedHookInstallerSupport.closeOnInstallFailure(failure, this);
            throw failure;
        }
    }

    /** Injected consult count for the install marker log. */
    int sites() {
        return transformer.sites();
    }

    @Override
    public synchronized void close() {
        final Map<String, Long> stats = bridge.snapshot();
        bridge.close();
        if (restored) return;
        Throwable failure = null;
        if (registered) {
            try {
                instrumentation.removeTransformer(transformer);
                registered = false;
            } catch (Exception | Error problem) {
                // @containment-exempt: problem is merged and rethrown below with fatal error prioritized
                failure = dev.turboism.core.runtime.work.FatalErrors.mergeForCleanup(failure, problem);
            }
        }
        for (Class<?> entry : List.of(paintClass, fillClass)) {
            final String before = transformer.beforeSha256(entry.getName().replace('.', '/'));
            if (before == null) continue;
            try {
                final String hash =
                        VerifiedHookInstallerSupport.sha256Hex(VerifiedHookInstallerSupport.captureLoadedBytes(
                                instrumentation,
                                entry,
                                "canvas composite elision class unmodifiable: " + entry.getName(),
                                "canvas composite elision inspection absent: " + entry.getName()));
                if (!hash.equals(before)) {
                    throw new IllegalStateException(
                            "canvas composite elision restoration not proven: " + entry.getName());
                }
            } catch (Exception | Error problem) {
                // @containment-exempt: problem is merged and rethrown below with fatal error prioritized
                failure = dev.turboism.core.runtime.work.FatalErrors.mergeForCleanup(failure, problem);
            }
        }
        installed = false;
        restored = failure == null;
        dev.turboism.runtime.log.RuntimeDiagnostics.info(
                "bootstrap", "TURBOISM_CANVAS_COMPOSITE closed" + report(stats) + " restored=" + restored);
        if (failure != null) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
            throw new IllegalStateException("canvas composite elision restoration failed", failure);
        }
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
        return restored;
    }
}
