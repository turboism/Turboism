package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.mesh.MeshMirrorHostProfile;
import dev.turboism.adapter.cubism.mesh.MeshMirrorNativeMethodTransformer;
import dev.turboism.adapter.cubism.mesh.NativeMeshMirrorBridge;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshEditUiService;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshMirrorAxisService;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Owns the exact reviewed mesh-mirror transformer and its two-phase lifecycle. */
final class VerifiedMeshMirrorHookInstaller implements AutoCloseable {
    private final Instrumentation instrumentation;
    private final ClassLoader hostClassLoader;
    private RuntimeMeshMirrorAxisService axis;
    private RuntimeMeshEditUiService ui;
    private final MeshMirrorNativeMethodTransformer transformer;
    private final String[] targetClassNames;
    private final Consumer<String> diagnostic;
    private final List<String> diagnostics = new ArrayList<>();
    private dev.turboism.sdk.plugin.Registration contributionObserver;
    private final Object lifecycleLock = new Object();
    private boolean installed;
    private boolean bound;
    private boolean closed;

    VerifiedMeshMirrorHookInstaller(
        final Instrumentation instrumentation,
        final ClassLoader hostClassLoader,
        final MeshMirrorHostProfile profile
    ) {
        this(instrumentation, hostClassLoader, null, null, null, profile, System.err::println);
    }

    VerifiedMeshMirrorHookInstaller(
        final Instrumentation instrumentation,
        final ClassLoader hostClassLoader,
        final RuntimeMeshMirrorAxisService axis,
        final RuntimeMeshEditUiService ui,
        final MeshMirrorHostProfile profile,
        final Consumer<String> diagnostic
    ) {
        this(instrumentation, hostClassLoader, null, axis, ui, profile, diagnostic);
    }

    VerifiedMeshMirrorHookInstaller(
        final Instrumentation instrumentation,
        final ClassLoader hostClassLoader,
        final RuntimeMeshMirrorAxisService axis,
        final RuntimeMeshEditUiService ui,
        final MeshMirrorHostProfile profile
    ) {
        this(instrumentation, hostClassLoader, null, axis, ui, profile, System.err::println);
    }

    VerifiedMeshMirrorHookInstaller(
        final Instrumentation instrumentation,
        final ClassLoader hostClassLoader,
        final Path hostArtifact,
        final MeshMirrorHostProfile profile
    ) {
        this(instrumentation, hostClassLoader, hostArtifact, null, null, profile, System.err::println);
    }

    VerifiedMeshMirrorHookInstaller(
        final Instrumentation instrumentation,
        final ClassLoader hostClassLoader,
        final Path hostArtifact,
        final RuntimeMeshMirrorAxisService axis,
        final RuntimeMeshEditUiService ui,
        final MeshMirrorHostProfile profile
    ) {
        this(instrumentation, hostClassLoader, hostArtifact, axis, ui, profile, System.err::println);
    }

    VerifiedMeshMirrorHookInstaller(
        final Instrumentation instrumentation,
        final ClassLoader hostClassLoader,
        final Path hostArtifact,
        final RuntimeMeshMirrorAxisService axis,
        final RuntimeMeshEditUiService ui,
        final MeshMirrorHostProfile profile,
        final Consumer<String> diagnostic
    ) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.hostClassLoader = hostClassLoader;
        this.axis = axis;
        this.ui = ui;
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        this.transformer = new MeshMirrorNativeMethodTransformer(
            profile,
            hostClassLoader,
            hostArtifact == null ? null : hostArtifact.toAbsolutePath().normalize(),
            null,
            instrumentation,
            this::report
        );
        // Every owner the transformer may rewrite must be listed here, or a preloaded
        // target would go undetected and cleanup would leave it rewritten.
        final List<String> targets = new ArrayList<>(List.of(
            profile.meshEditorOwner().replace('/', '.'),
            profile.mirrorWidgetOwner().replace('/', '.'),
            profile.mirrorAxisDrawOwner().replace('/', '.')
        ));
        final MeshMirrorHostProfile.LinkedDeletion linked = profile.linkedDeletion();
        if (linked != null) {
            targets.add(linked.pointActionOwner().replace('/', '.'));
            targets.add(linked.edgeActionOwner().replace('/', '.'));
        }
        this.targetClassNames = targets.toArray(new String[0]);
    }

    /** Installs the transformer before runtime/plugin startup; it intentionally stays unbound. */
    void install() {
        synchronized (lifecycleLock) {
            if (closed) throw new IllegalStateException("mesh mirror hook installer is closed");
            if (installed) return;
            try {
                if (!instrumentation.isRetransformClassesSupported()) {
                    throw new IllegalStateException("mesh mirror retransform is not supported");
                }
                rejectLoadedTargets();
                instrumentation.addTransformer(transformer, true);
                installed = true;
                report("MESH_MIRROR_DIAG stage=TRANSFORMER_REGISTERED");
            } catch (Throwable failure) {
                rollback();
                closed = true;
                throw new IllegalStateException("mesh mirror hook installation failed", failure);
            }
        }
    }

    /** Binds runtime services to this already-installed transformer exactly once. */
    void bind() {
        bind(axis, ui);
    }

    void bind(
        final RuntimeMeshMirrorAxisService axis,
        final RuntimeMeshEditUiService ui
    ) {
        Objects.requireNonNull(axis, "axis");
        Objects.requireNonNull(ui, "ui");
        synchronized (lifecycleLock) {
            if (closed) throw new IllegalStateException("mesh mirror hook installer is closed");
            if (!installed) throw new IllegalStateException("mesh mirror transformer is not installed");
            if (bound) return;
            this.axis = axis;
            this.ui = ui;
            try {
                NativeMeshMirrorBridge.diagnostics(this::report);
                contributionObserver = observeContribution(ui);
                NativeMeshMirrorBridge.install(axis, ui, true);
                bound = true;
            } catch (Throwable failure) {
                if (contributionObserver != null) {
                    contributionObserver.close();
                    contributionObserver = null;
                }
                throw new IllegalStateException("mesh mirror runtime binding failed", failure);
            }
        }
    }

    private dev.turboism.sdk.plugin.Registration observeContribution(
        final RuntimeMeshEditUiService service
    ) {
        return service.observeContribution(available -> {
            if (available) NativeMeshMirrorBridge.replayPendingAttach();
            else if (isBound()) close();
        });
    }

    boolean isInstalled() {
        synchronized (lifecycleLock) {
            return installed;
        }
    }

    boolean isBound() {
        synchronized (lifecycleLock) {
            return bound;
        }
    }

    ClassLoader transformerClassLoader() {
        return transformer.admittedClassLoader();
    }

    List<String> diagnostics() {
        return List.copyOf(diagnostics);
    }

    MeshMirrorNativeMethodTransformer.Outcome transformerOutcome() {
        return transformer.outcome();
    }

    int ownedTargetCount() {
        return targetClassNames.length;
    }

    boolean targetTransformed() {
        return transformer.targetTransformed();
    }

    /** Registration and transformation are not attachment; only this reports a control inserted. */
    boolean controlAttached() {
        return NativeMeshMirrorBridge.controlAttached();
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            rollback();
        }
    }

    /** Must be called under lifecycleLock; revokes runtime state even when restoration fails. */
    private void rollback() {
        final List<String> failures = new ArrayList<>();
        bound = false;
        try {
            NativeMeshMirrorBridge.uninstall();
        } catch (Throwable failure) {
            failures.add("MESH_MIRROR_BRIDGE_UNINSTALL_FAILED");
        }
        if (installed) {
            installed = false;
            try {
                if (!instrumentation.removeTransformer(transformer)) {
                    failures.add("MESH_MIRROR_TRANSFORMER_REMOVE_FAILED");
                }
            } catch (Throwable failure) {
                failures.add("MESH_MIRROR_TRANSFORMER_REMOVE_FAILED");
            }
            restoreLoadedTargets(failures);
        }
        if (contributionObserver != null) {
            try {
                contributionObserver.close();
            } catch (Throwable failure) {
                failures.add("MESH_MIRROR_CONTRIBUTION_OBSERVER_CLOSE_FAILED");
            } finally {
                contributionObserver = null;
            }
        }
        if (ui != null) {
            try {
                ui.resetSession();
            } catch (Throwable failure) {
                failures.add("MESH_MIRROR_UI_RESET_FAILED");
            }
        }
        if (axis != null) {
            try {
                axis.resetSession();
            } catch (Throwable failure) {
                failures.add("MESH_MIRROR_AXIS_RESET_FAILED");
            }
        }
        failures.forEach(this::report);
        report("MESH_MIRROR_DIAG stage=HOOK_CLOSED");
    }

    private void restoreLoadedTargets(final List<String> failures) {
        // PREMAIN cannot know the host loader yet, so a null hostClassLoader would widen
        // restoration to every loader. Prefer the loader the transformer actually admitted.
        final ClassLoader owner = transformer.admittedClassLoader() == null
            ? hostClassLoader
            : transformer.admittedClassLoader();
        try {
            for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                try {
                    if ((owner == null || type.getClassLoader() == owner)
                        && isTarget(safeName(type))
                        && instrumentation.isModifiableClass(type)) {
                        instrumentation.retransformClasses(type);
                    }
                } catch (Throwable failure) {
                    failures.add("MESH_MIRROR_RESTORE_FAILED owner=" + safeName(type));
                    failures.add("MESH_MIRROR_DIAG stage=RESTORE_FAILED owner=" + safeName(type));
                }
            }
        } catch (Throwable failure) {
            failures.add("MESH_MIRROR_RESTORE_ENUMERATION_FAILED");
            failures.add("MESH_MIRROR_DIAG stage=RESTORE_FAILED owner=ENUMERATION");
        }
    }

    private void rejectLoadedTargets() {
        final Class<?>[] loaded;
        try {
            loaded = instrumentation.getAllLoadedClasses();
        } catch (Throwable failure) {
            report("MESH_MIRROR_UNAVAILABLE_LOADED_CLASS_ENUMERATION_FAILED");
            throw new IllegalStateException("mesh mirror loaded-class enumeration failed", failure);
        }
        for (Class<?> type : loaded) {
            if (isTarget(safeName(type))) {
                report("MESH_MIRROR_UNAVAILABLE_TARGET_ALREADY_LOADED owner=" + safeName(type));
                throw new IllegalStateException("mesh mirror target is already loaded: " + safeName(type));
            }
        }
    }

    private void report(final String message) {
        diagnostics.add(message);
        try {
            diagnostic.accept(message);
        } catch (Throwable ignored) {
            // Diagnostics must not reopen a closed host boundary.
        }
    }

    private static String safeName(final Class<?> type) {
        try {
            return type.getName();
        } catch (Throwable ignored) {
            return "UNKNOWN";
        }
    }

    private boolean isTarget(final String name) {
        for (String target : targetClassNames) if (name.equals(target)) return true;
        return false;
    }
}
