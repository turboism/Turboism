package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.composite.CanvasCompositeElisionBridge;
import dev.turboism.adapter.cubism.optimization.composite.CanvasCompositeElisionTarget;
import dev.turboism.adapter.cubism.optimization.composite.CanvasCompositeElisionTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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

    private static final String PAINT_JRT =
        "/modules/java.desktop/javax/swing/RepaintManager$PaintManager.class";
    private static final String PAINT_CALLER_JRT =
        "/modules/java.desktop/javax/swing/RepaintManager.class";

    private final Instrumentation instrumentation;
    private final Class<?> paintClass;
    private final Class<?> fillClass;
    private final CanvasCompositeElisionTransformer transformer;
    private final CanvasCompositeElisionBridge bridge;
    private boolean installed, restored;

    VerifiedCanvasCompositeElisionInstaller(final Instrumentation instrumentation,
                                            final Path artifact, final ClassLoader loader)
            throws Exception {
        CanvasCompositeElisionTarget.of(HostArtifactDigest.from(artifact))
            .orElseThrow(() -> new IllegalArgumentException(
                "canvas composite elision unsupported host artifact"));
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
            fillReference = jar.getInputStream(
                jar.getJarEntry("com/formdev/flatlaf/ui/FlatPanelUI.class")).readAllBytes();
        }
        paintClass = Class.forName(
            CanvasCompositeElisionTarget.PAINT_OWNER.replace('/', '.'), false, null);
        attestJdk(paintClass);
        fillClass = Class.forName(
            CanvasCompositeElisionTarget.FILL_OWNER.replace('/', '.'), false, loader);
        attest(fillClass, loader, flatlaf);
        transformer = new CanvasCompositeElisionTransformer(
            loader, flatlaf, paintReference, fillReference);
        // The elision relies on the JDK's direct-paint fallback: verify the
        // caller's shape so a JDK without it is refused.
        final byte[] caller = jrt(PAINT_CALLER_JRT);
        final List<String> expected = ReviewedMethodShape.read(caller,
            CanvasCompositeElisionTarget.PAINT_CALLER_OWNER,
            CanvasCompositeElisionTarget.PAINT_CALLER_METHOD,
            CanvasCompositeElisionTarget.PAINT_CALLER_DESCRIPTOR);
        final Class<?> callerClass = Class.forName(
            CanvasCompositeElisionTarget.PAINT_CALLER_OWNER.replace('/', '.'), false, null);
        attestJdk(callerClass);
        if (expected == null || !expected.equals(ReviewedMethodShape.read(capture(callerClass),
                CanvasCompositeElisionTarget.PAINT_CALLER_OWNER,
                CanvasCompositeElisionTarget.PAINT_CALLER_METHOD,
                CanvasCompositeElisionTarget.PAINT_CALLER_DESCRIPTOR))) {
            throw new IllegalStateException(
                "canvas composite elision fallback caller mismatch: "
                    + CanvasCompositeElisionTarget.PAINT_CALLER_OWNER);
        }
        bridge = new CanvasCompositeElisionBridge(loader);
    }

    private static Path flatlafArtifact(final Path artifact) throws Exception {
        final Path lib = artifact.toAbsolutePath().normalize().getParent();
        try (var stream = Files.list(lib)) {
            final List<Path> matches = stream
                .filter(p -> {
                    final String name = p.getFileName().toString();
                    return name.startsWith("flatlaf-") && name.endsWith(".jar")
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
        return Files.readAllBytes(FileSystems.getFileSystem(URI.create("jrt:/"))
            .getPath(path));
    }

    private static void attestJdk(final Class<?> type) {
        if ((type.getClassLoader() != null
                && type.getClassLoader() != ClassLoader.getPlatformClassLoader())
            || (type.getProtectionDomain() != null
                && type.getProtectionDomain().getCodeSource() != null)) {
            throw new IllegalArgumentException(
                "canvas composite elision JDK target attestation failed: " + type.getName());
        }
    }

    private static void attest(final Class<?> type, final ClassLoader loader,
                               final Path artifact) throws Exception {
        if (type.getClassLoader() != loader
            || !Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath().normalize().equals(artifact)) {
            throw new IllegalArgumentException(
                "canvas composite elision dependency loader/source mismatch: " + type.getName());
        }
    }

    private byte[] capture(final Class<?> type) throws Exception {
        if (!instrumentation.isModifiableClass(type)) {
            throw new IllegalStateException(
                "canvas composite elision class unmodifiable: " + type.getName());
        }
        final AtomicReference<byte[]> result = new AtomicReference<>();
        final ClassFileTransformer observer = new ClassFileTransformer() {
            @Override public byte[] transform(final Module module, final ClassLoader loader,
                                              final String name, final Class<?> redefined,
                                              final ProtectionDomain domain, final byte[] bytes) {
                if (redefined == type) result.set(bytes.clone());
                return null;
            }
        };
        instrumentation.addTransformer(observer, true);
        try {
            instrumentation.retransformClasses(type);
        } finally {
            instrumentation.removeTransformer(observer);
        }
        if (result.get() == null) {
            throw new IllegalStateException(
                "canvas composite elision inspection absent: " + type.getName());
        }
        return result.get();
    }

    synchronized void install() throws Exception {
        install(false);
    }

    synchronized void install(final boolean production) throws Exception {
        if (installed) return;
        for (Class<?> entry : List.of(paintClass, fillClass)) {
            if (!instrumentation.isModifiableClass(entry)) {
                throw new IllegalStateException("canvas composite elision entry unmodifiable: "
                    + entry.getName());
            }
        }
        bridge.install(production);
        try {
            instrumentation.addTransformer(transformer, true);
            instrumentation.retransformClasses(paintClass, fillClass);
            if (transformer.matches() != 2 || transformer.sites() != 2
                || transformer.failure() != null) {
                throw new IllegalStateException("canvas composite elision entries not admitted: "
                    + transformer.failure());
            }
            installed = true;
        } catch (Exception | Error failure) {
            try {
                close();
            } catch (Exception | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Injected consult count for the install marker log. */
    int sites() {
        return transformer.sites();
    }

    @Override public synchronized void close() {
        final Map<String, Long> stats = bridge.snapshot();
        bridge.close();
        if (!installed) {
            dev.turboism.runtime.log.RuntimeDiagnostics.info("bootstrap",
                "TURBOISM_CANVAS_COMPOSITE closed" + report(stats) + " installed=false");
            return;
        }
        instrumentation.removeTransformer(transformer);
        try {
            for (Class<?> entry : List.of(paintClass, fillClass)) {
                final byte[] original = capture(entry);
                final String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(original));
                if (!hash.equals(transformer.beforeSha256(
                        entry.getName().replace('.', '/')))) {
                    throw new IllegalStateException(
                        "canvas composite elision restoration not proven: " + entry.getName());
                }
            }
            restored = true;
            installed = false;
        } catch (Exception failure) {
            dev.turboism.runtime.log.RuntimeDiagnostics.info("bootstrap",
                "TURBOISM_CANVAS_COMPOSITE closed" + report(stats)
                    + " restored=false reason=" + failure);
            throw new IllegalStateException("canvas composite elision restoration failed", failure);
        }
        dev.turboism.runtime.log.RuntimeDiagnostics.info("bootstrap",
            "TURBOISM_CANVAS_COMPOSITE closed" + report(stats) + " restored=true");
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
