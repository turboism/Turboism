package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.inputpath.InputPathElisionBridge;
import dev.turboism.adapter.cubism.optimization.inputpath.InputPathElisionTarget;
import dev.turboism.adapter.cubism.optimization.inputpath.InputPathElisionTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * Exact admission, dependency verification and restoration for the test-only
 * input-path elision experiment. One host class is rewritten —
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
 */
final class VerifiedInputPathElisionInstaller implements AutoCloseable {

    /** Startup hook id used by the safe-mode / disabledHooks policy. */
    static final String HOOK_ID = "cubism.render.input-path-elision";

    /** Exact production admission: reviewed digest + JVM17 + hook policy. */
    static boolean admitted(
            final HostArtifactDigest digest,
            final RuntimeStartupConfig config,
            final boolean requested,
            final Runtime.Version jvm) {
        return requested
                && jvm.feature() >= 17
                && InputPathElisionTarget.of(digest).isPresent()
                && config.hookEnabled(HOOK_ID);
    }

    private final Instrumentation instrumentation;
    private final InputPathElisionTarget target;
    private final Class<?> entry;
    private final InputPathElisionTransformer transformer;
    private final InputPathElisionBridge bridge;
    private boolean installed, restored, registered;

    VerifiedInputPathElisionInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) throws Exception {
        target = InputPathElisionTarget.of(HostArtifactDigest.from(artifact))
                .orElseThrow(() -> new IllegalArgumentException("input path elision unsupported host artifact"));
        if (Runtime.version().feature() < 17) {
            throw new IllegalArgumentException("input path elision requires JVM17+");
        }
        this.instrumentation = instrumentation;
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("retransform unavailable");
        }
        try (JarFile jar = new JarFile(artifact.toFile())) {
            entry = Class.forName(InputPathElisionTarget.OWNER.replace('/', '.'), false, loader);
            VerifiedHookInstallerSupport.attestClassSource(
                    entry,
                    loader,
                    artifact,
                    "input path elision dependency loader/source mismatch: " + entry.getName());
            transformer = new InputPathElisionTransformer(
                    loader, artifact, VerifiedHookInstallerSupport.readReferenceBytes(jar, entry));
            verify(
                    jar,
                    loader,
                    artifact,
                    InputPathElisionTarget.OWNER,
                    InputPathElisionTarget.COMPONENT_METHOD,
                    InputPathElisionTarget.COMPONENT_DESCRIPTOR);
            verify(
                    jar,
                    loader,
                    artifact,
                    InputPathElisionTarget.CURSOR_OWNER,
                    InputPathElisionTarget.CURSOR_ACCESSOR,
                    InputPathElisionTarget.CURSOR_ACCESSOR_DESCRIPTOR);
        }
        bridge = new InputPathElisionBridge(loader);
    }

    private void verify(
            final JarFile jar,
            final ClassLoader loader,
            final Path artifact,
            final String owner,
            final String method,
            final String descriptor)
            throws Exception {
        final Class<?> type = Class.forName(owner.replace('/', '.'), false, loader);
        VerifiedHookInstallerSupport.attestClassSource(
                type, loader, artifact, "input path elision dependency loader/source mismatch: " + type.getName());
        final byte[] actual = VerifiedHookInstallerSupport.captureLoadedBytes(
                instrumentation,
                type,
                "input path elision class unmodifiable: " + type.getName(),
                "input path elision dependency inspection absent: " + type.getName());
        final List<String> expected = ReviewedMethodShape.read(
                VerifiedHookInstallerSupport.readReferenceBytes(jar, type), owner, method, descriptor);
        if (expected == null || !expected.equals(ReviewedMethodShape.read(actual, owner, method, descriptor))) {
            throw new IllegalStateException("input path elision dependency body mismatch: " + owner + "." + method);
        }
    }

    synchronized void install() throws Exception {
        install(false);
    }

    synchronized void install(final boolean production) throws Exception {
        if (installed) return;
        if (!instrumentation.isModifiableClass(entry)) {
            throw new IllegalStateException("input path elision entry unmodifiable: " + entry.getName());
        }
        bridge.install(production);
        try {
            instrumentation.addTransformer(transformer, true);
            registered = true;
            instrumentation.retransformClasses(entry);
            if (transformer.matches() != 1 || transformer.sites() != 2 || transformer.failure() != null) {
                throw new IllegalStateException(
                        "input path elision entry not admitted: " + entry.getName() + " " + transformer.failure());
            }
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

    /** Injected consult count for the install marker log. */
    int sites() {
        return transformer.sites();
    }

    @Override
    public synchronized void close() {
        final Map<String, Long> stats = bridge.snapshot();
        bridge.close();
        if (restored || (!installed && !registered && transformer.beforeSha256() == null)) {
            dev.turboism.runtime.log.RuntimeDiagnostics.info(
                    "bootstrap", "TURBOISM_INPUT_PATH closed" + report(stats) + " installed=false");
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
                        "bootstrap", "TURBOISM_INPUT_PATH closed" + report(stats) + " restored=true");
                return;
            }
            final byte[] original = VerifiedHookInstallerSupport.captureLoadedBytes(
                    instrumentation,
                    entry,
                    "input path elision class unmodifiable: " + entry.getName(),
                    "input path elision dependency inspection absent: " + entry.getName());
            final String hash = VerifiedHookInstallerSupport.sha256Hex(original);
            if (!hash.equals(transformer.beforeSha256())) {
                throw new IllegalStateException("input path elision restoration not proven: " + entry.getName());
            }
            restored = true;
            installed = false;
        } catch (Exception failure) {
            dev.turboism.runtime.log.RuntimeDiagnostics.info(
                    "bootstrap", "TURBOISM_INPUT_PATH closed" + report(stats) + " restored=false reason=" + failure);
            throw new IllegalStateException("input path elision restoration failed", failure);
        }
        dev.turboism.runtime.log.RuntimeDiagnostics.info(
                "bootstrap", "TURBOISM_INPUT_PATH closed" + report(stats) + " restored=true");
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
        return restored;
    }
}
