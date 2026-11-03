package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedHostContract;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.modelinstances.ModelInstanceCoalesceBridge;
import dev.turboism.adapter.cubism.optimization.modelinstances.ModelInstanceCoalesceTarget;
import dev.turboism.adapter.cubism.optimization.modelinstances.ModelInstanceCoalesceTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * Exact admission, dependency-body verification and restoration for the native
 * model-instance coalescer.
 *
 * <p>The three rewritten classes are pinned through the existing reviewed
 * model-update class evidence. Every method the state machine invokes or the
 * transformer rewrites is compared against the official artifact bytes before any
 * retransformation is published, and close re-captures each class to prove the
 * original bytes returned.</p>
 */
final class VerifiedModelInstanceCoalesceInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.model-instance-update-coalesce";

    private static final String DOCUMENT = "com/live2d/cubism/doc/modeling/CModelingDocument";
    private static final String UNDO_GROUP = "com/live2d/undo/GroupUndo";

    private static final List<ReviewedHostContract.Candidate<ModelInstanceCoalesceTarget>> CANDIDATES =
            ReviewedHostContract.candidates(
                    ModelInstanceCoalesceTarget.reviewedClassSha256(), ModelInstanceCoalesceTarget::new);

    private final Instrumentation instrumentation;
    private final ModelInstanceCoalesceTarget target;
    private final Class<?> source;
    private final Class<?> document;
    private final Class<?> editMode;
    private final Class<?> undoManager;
    private final Class<?> undoGroup;
    private final ModelInstanceCoalesceTransformer transformer;
    private final ModelInstanceCoalesceBridge bridge;
    private boolean installed;
    private boolean restored;

    static boolean admitted(final Path artifact, final boolean requested, final int jvm) {
        return requested && jvm >= 17 && ReviewedHostContract.resolved(artifact, CANDIDATES);
    }

    VerifiedModelInstanceCoalesceInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) throws Exception {
        final var contract = ReviewedHostContract.requireBound(
                ReviewedHostContract.resolve(artifact, CANDIDATES), "model-instance coalescing");
        target = contract.contract();
        if (Runtime.version().feature() < 17) {
            throw new IllegalArgumentException("model-instance coalescing requires JVM17+");
        }
        this.instrumentation = instrumentation;
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("retransform unavailable");
        }
        source = Class.forName(ModelInstanceCoalesceTarget.SOURCE_OWNER.replace('/', '.'), false, loader);
        document = Class.forName(DOCUMENT.replace('/', '.'), false, loader);
        editMode = Class.forName(ModelInstanceCoalesceTarget.EDIT_MODE_OWNER.replace('/', '.'), false, loader);
        undoManager = Class.forName(ModelInstanceCoalesceTarget.UNDO_MANAGER_OWNER.replace('/', '.'), false, loader);
        undoGroup = Class.forName(UNDO_GROUP.replace('/', '.'), false, loader);

        final Map<Class<?>, byte[]> observed = new HashMap<>();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            for (final Class<?> type : List.of(source, document, editMode, undoManager, undoGroup)) {
                VerifiedHookInstallerSupport.attestClassSource(
                        type, loader, artifact, "model-instance coalescer dependency loader/source mismatch");
                verifyMethod(instrumentation, jar, type, observed);
            }
            transformer = new ModelInstanceCoalesceTransformer(
                    loader,
                    artifact,
                    VerifiedHookInstallerSupport.readReferenceBytes(jar, source),
                    VerifiedHookInstallerSupport.readReferenceBytes(jar, editMode),
                    VerifiedHookInstallerSupport.readReferenceBytes(jar, undoManager));
        }
        bridge = new ModelInstanceCoalesceBridge(loader);
        contract.requireUnchanged(artifact);
    }

    private static void verifyMethod(
            final Instrumentation instrumentation,
            final JarFile jar,
            final Class<?> type,
            final Map<Class<?>, byte[]> observed)
            throws Exception {
        for (final Dependency dependency : dependenciesFor(type)) {
            byte[] actual = observed.get(type);
            if (actual == null) {
                actual = VerifiedHookInstallerSupport.captureLoadedBytes(
                        instrumentation,
                        type,
                        "model-instance coalescer dependency unmodifiable",
                        "model-instance coalescer dependency inspection absent");
                observed.put(type, actual);
            }
            final String owner = type.getName().replace('.', '/');
            final var expected = ReviewedMethodShape.read(
                    VerifiedHookInstallerSupport.readReferenceBytes(jar, type),
                    owner,
                    dependency.name(),
                    dependency.descriptor());
            if (expected == null) {
                throw new IllegalStateException("reviewed coalescer dependency absent: " + owner + "."
                        + dependency.name() + dependency.descriptor());
            }
            final var found = ReviewedMethodShape.read(actual, owner, dependency.name(), dependency.descriptor());
            if (!expected.equals(found)) {
                throw new IllegalStateException(
                        "model-instance coalescer dependency body changed: " + owner + "." + dependency.name());
            }
        }
    }

    private static List<Dependency> dependenciesFor(final Class<?> type) {
        final String name = type.getName();
        if (ModelInstanceCoalesceTarget.SOURCE_OWNER.replace('/', '.').equals(name)) {
            return List.of(new Dependency("getDocument", "()Lcom/live2d/cubism/doc/modeling/CModelingDocument;"));
        }
        if (DOCUMENT.equals(name)) {
            return List.of(
                    new Dependency("getCurrentEditMode", "()Lcom/live2d/cubism/doc/modeling/CModelingEditMode_Base;"));
        }
        if (ModelInstanceCoalesceTarget.EDIT_MODE_OWNER.replace('/', '.').equals(name)) {
            return List.of(
                    new Dependency("isEditing", "()Z"),
                    new Dependency("getCurrentUndo", "()Lcom/live2d/undo/GroupUndo;"),
                    new Dependency("getDoc", "()Lcom/live2d/cubism/doc/IDocument;"));
        }
        if (UNDO_GROUP.equals(name)) {
            return List.of(new Dependency("getEditCount", "()I"));
        }
        return List.of();
    }

    private record Dependency(String name, String descriptor) {}

    synchronized void install() throws Exception {
        if (installed) return;
        for (final Class<?> type : List.of(source, editMode, undoManager)) {
            if (!instrumentation.isModifiableClass(type)) {
                throw new IllegalStateException("model-instance coalescer target unmodifiable: " + type.getName());
            }
        }
        bridge.install();
        try {
            instrumentation.addTransformer(transformer, true);
            installed = true;
            instrumentation.retransformClasses(source, editMode, undoManager);
            if (transformer.matches() != 4 || transformer.failure() != null) {
                throw new IllegalStateException(
                        "model-instance coalescer seams not admitted: " + transformer.failure());
            }
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
        bridge.close();
        if (!installed) return;
        instrumentation.removeTransformer(transformer);
        if (transformer.beforeSha256().isEmpty()) {
            installed = false;
            return;
        }
        try {
            for (final Class<?> type : List.of(source, editMode, undoManager)) {
                final byte[] original = VerifiedHookInstallerSupport.captureLoadedBytes(
                        instrumentation,
                        type,
                        "model-instance coalescer dependency unmodifiable",
                        "model-instance coalescer dependency inspection absent");
                final String expected =
                        transformer.beforeSha256().get(type.getName().replace('.', '/'));
                if (expected == null || !expected.equals(VerifiedHookInstallerSupport.sha256Hex(original))) {
                    throw new IllegalStateException(
                            "native model-instance coalescer restoration not proven: " + type.getName());
                }
            }
            restored = true;
            installed = false;
        } catch (Exception failure) {
            throw new IllegalStateException("model-instance coalescer restoration failed", failure);
        }
    }

    boolean restored() {
        return restored;
    }

    /** Reviewed generation bound to the actual artifact. */
    String sourceVersion() {
        return target.version();
    }

    /** Coalescer counters for the installation log. */
    Map<String, Long> snapshot() {
        return bridge.snapshot();
    }
}
