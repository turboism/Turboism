package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.warpalt.NativeWarpAltMirrorBridge;
import dev.turboism.adapter.cubism.warpalt.WarpAltMirrorHostProfile;
import dev.turboism.adapter.cubism.warpalt.WarpAltMirrorNativeMethodTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Owns the exact reviewed warp Alt-mirror transformer and its two-phase lifecycle:
 * the transformer is installed during premain so the host class is caught on first
 * definition, and the bridge is bound only after the lazy target is defined through
 * the admitted host loader and a participating plugin is admitted.
 */
final class VerifiedWarpAltMirrorHookInstaller implements AutoCloseable {
    private final Instrumentation instrumentation;
    private final WarpAltMirrorNativeMethodTransformer transformer;
    private final String pointMoveClassName;
    private final String dragTickClassName;
    private final Consumer<String> diagnostic;
    private final Object lifecycleLock = new Object();
    private boolean installed;
    private boolean bound;
    private boolean closed;
    private Runnable capabilityRevocation = () -> { };

    VerifiedWarpAltMirrorHookInstaller(
        final Instrumentation instrumentation,
        final ClassLoader hostClassLoader,
        final Path hostArtifact,
        final WarpAltMirrorHostProfile profile
    ) {
        this(instrumentation, hostClassLoader, hostArtifact, profile, ignored -> { });
    }

    VerifiedWarpAltMirrorHookInstaller(
        final Instrumentation instrumentation,
        final ClassLoader hostClassLoader,
        final Path hostArtifact,
        final WarpAltMirrorHostProfile profile,
        final Consumer<String> diagnostic
    ) {
        this(instrumentation, hostClassLoader, hostArtifact, profile, Map.of(), diagnostic);
    }

    VerifiedWarpAltMirrorHookInstaller(
        final Instrumentation instrumentation,
        final ClassLoader hostClassLoader,
        final Path hostArtifact,
        final WarpAltMirrorHostProfile profile,
        final Map<String, String> pinnedClassSha256,
        final Consumer<String> diagnostic
    ) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        this.transformer = new WarpAltMirrorNativeMethodTransformer(
            profile,
            hostClassLoader,
            hostArtifact == null ? null : hostArtifact.toAbsolutePath().normalize(),
            pinnedClassSha256,
            this::report
        );
        this.pointMoveClassName = profile.pointMoveOwner().replace('/', '.');
        this.dragTickClassName = profile.dragTickOwner().replace('/', '.');
    }

    /** Installs the transformer during premain; it intentionally stays unbound. */
    void install() {
        synchronized (lifecycleLock) {
            if (closed) throw new IllegalStateException("warp alt mirror hook installer is closed");
            if (installed) return;
            try {
                if (!instrumentation.isRetransformClassesSupported()) {
                    throw new IllegalStateException("warp alt mirror retransform is not supported");
                }
                rejectLoadedTargets();
                instrumentation.addTransformer(transformer, true);
                installed = true;
                report("WARP_ALT_MIRROR_DIAG stage=TRANSFORMER_REGISTERED");
            } catch (Throwable failure) {
                closed = true;
                transformer.deactivate();
                try {
                    instrumentation.removeTransformer(transformer);
                } catch (Throwable cleanupFailure) {
                    if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
                }
                throw new IllegalStateException("warp alt mirror hook installation failed", failure);
            }
        }
    }

    /**
     * Loads the still-lazy exact target through the admitted host loader. The hook is
     * fail-closed: the target must be defined by the admitted loader — forced through
     * the supplied host loader when the drag tick has not loaded it yet — and the
     * exact method must have been transformed.
     */
    void defineLazyTargets(final ClassLoader hostClassLoader) {
        synchronized (lifecycleLock) {
            if (closed) throw new IllegalStateException("warp alt mirror hook installer is closed");
            if (!installed) throw new IllegalStateException("warp alt mirror transformer is not installed");
            String currentName = pointMoveClassName;
            try {
                final ClassLoader admitted = transformer.admittedClassLoader();
                if (admitted != null && hostClassLoader != null && admitted != hostClassLoader) {
                    throw new IllegalStateException("warp alt mirror runtime loader differs from the transformed host");
                }
                final ClassLoader loader = admitted != null ? admitted : hostClassLoader;
                if (loader == null) {
                    throw new IllegalStateException("warp alt mirror host loader is not admitted");
                }
                for (final String name : List.of(pointMoveClassName, dragTickClassName)) {
                    currentName = name;
                    final Class<?> defined = Class.forName(name, false, loader);
                    if (defined.getClassLoader() != loader) {
                        throw new IllegalStateException(
                            "warp alt mirror lazy target loader mismatch: " + name);
                    }
                }
            } catch (ClassNotFoundException | LinkageError failure) {
                throw new IllegalStateException(
                    "warp alt mirror lazy target definition failed for " + currentName, failure);
            }
            if (transformer.admittedClassLoader() == null) {
                throw new IllegalStateException("warp alt mirror host loader is not admitted");
            }
            if (!transformer.transformedOwners().containsAll(List.of(
                pointMoveClassName.replace('.', '/'), dragTickClassName.replace('.', '/')))) {
                throw new IllegalStateException(
                    "warp alt mirror required targets were not transformed: outcome=" + transformer.outcome());
            }
        }
    }

    /** Binds the runtime bridge to this already-installed transformer exactly once. */
    void bind() {
        synchronized (lifecycleLock) {
            if (closed) throw new IllegalStateException("warp alt mirror hook installer is closed");
            if (!installed) throw new IllegalStateException("warp alt mirror transformer is not installed");
            if (bound) return;
            NativeWarpAltMirrorBridge.diagnostics(this::report);
            NativeWarpAltMirrorBridge.install();
            bound = true;
            report("WARP_ALT_MIRROR_DIAG stage=BRIDGE_BOUND");
        }
    }

    /** @return whether the transformer is installed. */
    boolean installed() {
        synchronized (lifecycleLock) {
            return installed;
        }
    }

    /** @return whether the exact target method was transformed successfully. */
    boolean targetTransformed() {
        return transformer.targetTransformed();
    }

    /** Connects runtime capability withdrawal to every close path, including direct close. */
    void onClose(final Runnable revoke) {
        Objects.requireNonNull(revoke, "revoke");
        synchronized (lifecycleLock) {
            if (closed) {
                revoke.run();
            } else {
                capabilityRevocation = revoke;
            }
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            transformer.deactivate();
            final List<Throwable> failures = new ArrayList<>();
            try { capabilityRevocation.run(); }
            catch (Throwable failure) { failures.add(failure); }
            capabilityRevocation = () -> { };
            if (bound) {
                bound = false;
                try { NativeWarpAltMirrorBridge.uninstall(); }
                catch (Throwable failure) { failures.add(failure); }
            }
            if (installed) {
                installed = false;
                try {
                    if (!instrumentation.removeTransformer(transformer)) {
                        failures.add(new IllegalStateException("warp transformer removal was not confirmed"));
                    }
                } catch (Throwable failure) { failures.add(failure); }
                restoreLoadedTargets(failures);
            }
            report("WARP_ALT_MIRROR_DIAG stage=HOOK_CLOSED");
            if (!failures.isEmpty()) {
                final IllegalStateException failure = new IllegalStateException("warp hook cleanup incomplete");
                failures.forEach(failure::addSuppressed);
                throw failure;
            }
        }
    }

    private void restoreLoadedTargets(final List<Throwable> failures) {
        final ClassLoader owner = transformer.admittedClassLoader();
        final var transformed = transformer.transformedOwners();
        if (owner == null || transformed.isEmpty()) return;
        try {
            for (final Class<?> type : instrumentation.getAllLoadedClasses()) {
                if (type.getClassLoader() != owner || !transformed.contains(type.getName().replace('.', '/'))) {
                    continue;
                }
                try {
                    if (!instrumentation.isModifiableClass(type)) {
                        throw new IllegalStateException("warp target is not modifiable: " + type.getName());
                    }
                    instrumentation.retransformClasses(type);
                } catch (Throwable failure) {
                    failures.add(failure);
                    report("WARP_ALT_MIRROR_RESTORE_FAILED owner=" + type.getName());
                }
            }
        } catch (Throwable failure) {
            failures.add(failure);
            report("WARP_ALT_MIRROR_RESTORE_ENUMERATION_FAILED");
        }
    }

    /** Premain guarantee: the exact targets must not already be defined when we register. */
    private void rejectLoadedTargets() {
        for (final String name : List.of(pointMoveClassName, dragTickClassName)) {
            for (final Class<?> type : instrumentation.getAllLoadedClasses()) {
                if (name.equals(type.getName())) {
                    throw new IllegalStateException(
                        "warp alt mirror target is already loaded: " + name);
                }
            }
        }
    }

    private void report(final String message) {
        try {
            diagnostic.accept(message);
        } catch (RuntimeException | Error ignored) {
            // A broken diagnostic sink must never break hook lifecycle.
        }
    }
}
