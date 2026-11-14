package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.mesh.MeshMirrorHostProfile;
import dev.turboism.adapter.cubism.mesh.MeshMirrorNativeMethodTransformer;
import dev.turboism.adapter.cubism.mesh.NativeMeshMirrorBridge;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshEditUiService;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshMirrorAxisService;
import dev.turboism.core.runtime.work.FatalErrors;
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
    private final LoadedClassIndex loadedClasses;
    private final List<String> diagnostics = new ArrayList<>();
    private dev.turboism.sdk.plugin.Registration contributionObserver;
    private final Object lifecycleLock = new Object();
    private boolean installed;
    private boolean bound;
    private boolean closed;

    VerifiedMeshMirrorHookInstaller(
            final Instrumentation instrumentation,
            final ClassLoader hostClassLoader,
            final MeshMirrorHostProfile profile) {
        this(instrumentation, hostClassLoader, null, null, null, profile, ignored -> {});
    }

    VerifiedMeshMirrorHookInstaller(
            final Instrumentation instrumentation,
            final ClassLoader hostClassLoader,
            final RuntimeMeshMirrorAxisService axis,
            final RuntimeMeshEditUiService ui,
            final MeshMirrorHostProfile profile,
            final Consumer<String> diagnostic) {
        this(instrumentation, hostClassLoader, null, axis, ui, profile, diagnostic);
    }

    VerifiedMeshMirrorHookInstaller(
            final Instrumentation instrumentation,
            final ClassLoader hostClassLoader,
            final RuntimeMeshMirrorAxisService axis,
            final RuntimeMeshEditUiService ui,
            final MeshMirrorHostProfile profile) {
        this(instrumentation, hostClassLoader, null, axis, ui, profile, ignored -> {});
    }

    VerifiedMeshMirrorHookInstaller(
            final Instrumentation instrumentation,
            final ClassLoader hostClassLoader,
            final Path hostArtifact,
            final MeshMirrorHostProfile profile) {
        this(instrumentation, hostClassLoader, hostArtifact, null, null, profile, ignored -> {});
    }

    VerifiedMeshMirrorHookInstaller(
            final Instrumentation instrumentation,
            final ClassLoader hostClassLoader,
            final Path hostArtifact,
            final RuntimeMeshMirrorAxisService axis,
            final RuntimeMeshEditUiService ui,
            final MeshMirrorHostProfile profile) {
        this(instrumentation, hostClassLoader, hostArtifact, axis, ui, profile, ignored -> {});
    }

    VerifiedMeshMirrorHookInstaller(
            final Instrumentation instrumentation,
            final ClassLoader hostClassLoader,
            final Path hostArtifact,
            final RuntimeMeshMirrorAxisService axis,
            final RuntimeMeshEditUiService ui,
            final MeshMirrorHostProfile profile,
            final Consumer<String> diagnostic) {
        this(instrumentation, hostClassLoader, hostArtifact, axis, ui, profile, java.util.Set.of(), diagnostic);
    }

    VerifiedMeshMirrorHookInstaller(
            final Instrumentation instrumentation,
            final ClassLoader hostClassLoader,
            final Path hostArtifact,
            final RuntimeMeshMirrorAxisService axis,
            final RuntimeMeshEditUiService ui,
            final MeshMirrorHostProfile profile,
            final java.util.Set<String> targetOwners,
            final Consumer<String> diagnostic) {
        this(instrumentation, hostClassLoader, hostArtifact, axis, ui, profile, targetOwners, diagnostic, null);
    }

    /**
     * @param loadedClasses the loaded-class snapshot of this install phase, or {@code null} to
     *     enumerate the loaded classes on install
     */
    VerifiedMeshMirrorHookInstaller(
            final Instrumentation instrumentation,
            final ClassLoader hostClassLoader,
            final Path hostArtifact,
            final RuntimeMeshMirrorAxisService axis,
            final RuntimeMeshEditUiService ui,
            final MeshMirrorHostProfile profile,
            final java.util.Set<String> targetOwners,
            final Consumer<String> diagnostic,
            final LoadedClassIndex loadedClasses) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.hostClassLoader = hostClassLoader;
        this.axis = axis;
        this.ui = ui;
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        this.loadedClasses = loadedClasses;
        this.transformer = new MeshMirrorNativeMethodTransformer(
                profile,
                hostClassLoader,
                hostArtifact == null ? null : hostArtifact.toAbsolutePath().normalize(),
                null,
                instrumentation,
                targetOwners,
                this::report);
        // Every owner the transformer may rewrite must be listed here, or a preloaded
        // target would go undetected and cleanup would leave it rewritten.
        final List<String> targets = new ArrayList<>(List.of(
                profile.meshEditorOwner().replace('/', '.'),
                profile.mirrorWidgetOwner().replace('/', '.')));
        if (profile.mirrorAxisDrawOwner() != null) {
            targets.add(profile.mirrorAxisDrawOwner().replace('/', '.'));
        }
        final MeshMirrorHostProfile.LegacyAxisDraw legacyAxisDraw = profile.legacyAxisDraw();
        if (legacyAxisDraw != null) {
            targets.add(legacyAxisDraw.enclosingOwner().replace('/', '.'));
        }
        final MeshMirrorHostProfile.SelectedPointMove move = profile.selectedPointMove();
        if (move != null) targets.add(move.owner().replace('/', '.'));
        final MeshMirrorHostProfile.LinkedDeletion linked = profile.linkedDeletion();
        if (linked != null) {
            targets.add(linked.pointActionOwner().replace('/', '.'));
            targets.add(linked.edgeActionOwner().replace('/', '.'));
            targets.add(linked.eraserActionOwner().replace('/', '.'));
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
                FatalErrors.rethrowIfFatal(failure);
                rollback();
                closed = true;
                throw new IllegalStateException("mesh mirror hook installation failed", failure);
            }
        }
    }

    /**
     * Loads any still-lazy exact targets while the admitted host loader is known. The hook is
     * fail-closed: every target must be defined by that loader and observed by Instrumentation.
     */
    void defineLazyTargets() {
        defineLazyTargets(hostClassLoader);
    }

    void defineLazyTargets(final ClassLoader locatedHostLoader) {
        synchronized (lifecycleLock) {
            if (closed) throw new IllegalStateException("mesh mirror hook installer is closed");
            if (!installed) throw new IllegalStateException("mesh mirror transformer is not installed");
            final ClassLoader admitted = transformer.admittedClassLoader();
            if (admitted != null && locatedHostLoader != null && admitted != locatedHostLoader) {
                throw new IllegalStateException("mesh mirror runtime loader differs from the transformed host");
            }
            final ClassLoader loader = admitted == null ? locatedHostLoader : admitted;
            if (loader == null) throw new IllegalStateException("mesh mirror host loader is not admitted");
            try {
                final List<String> missing = new ArrayList<>(List.of(targetClassNames));
                for (String target : targetClassNames) {
                    final Class<?> defined = Class.forName(target, false, loader);
                    if (defined.getClassLoader() != loader) {
                        throw new IllegalStateException("mesh mirror lazy target loader mismatch: " + target);
                    }
                }
                // The forced definitions above are newer than any snapshot taken before them.
                final LoadedClassIndex loaded = LoadedClassIndex.snapshot(instrumentation);
                for (Class<?> type : loaded.select(loader, ignored -> true)) {
                    missing.remove(safeName(type));
                }
                if (!missing.isEmpty()) {
                    throw new IllegalStateException("mesh mirror lazy targets were not observed: " + missing);
                }
                final var transformed = transformer.transformedOwners();
                for (final String target : targetClassNames) {
                    if (!transformed.contains(target.replace('.', '/'))) {
                        throw new IllegalStateException("mesh mirror required target was not transformed: " + target);
                    }
                }
            } catch (ClassNotFoundException | LinkageError failure) {
                throw new IllegalStateException("mesh mirror lazy target definition failed", failure);
            }
        }
    }

    /** Binds runtime services to this already-installed transformer exactly once. */
    void bind() {
        bind(axis, ui);
    }

    void bind(final RuntimeMeshMirrorAxisService axis, final RuntimeMeshEditUiService ui) {
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
                FatalErrors.rethrowIfFatal(failure);
                if (contributionObserver != null) {
                    contributionObserver.close();
                    contributionObserver = null;
                }
                throw new IllegalStateException("mesh mirror runtime binding failed", failure);
            }
        }
    }

    private dev.turboism.sdk.plugin.Registration observeContribution(final RuntimeMeshEditUiService service) {
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
        transformer.deactivate();
        final List<String> failures = new ArrayList<>();
        bound = false;
        try {
            NativeMeshMirrorBridge.uninstall();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.add("MESH_MIRROR_BRIDGE_UNINSTALL_FAILED");
        }
        if (installed) {
            installed = false;
            try {
                if (!instrumentation.removeTransformer(transformer)) {
                    failures.add("MESH_MIRROR_TRANSFORMER_REMOVE_FAILED");
                }
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                failures.add("MESH_MIRROR_TRANSFORMER_REMOVE_FAILED");
            }
            restoreLoadedTargets(failures);
        }
        if (contributionObserver != null) {
            try {
                contributionObserver.close();
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                failures.add("MESH_MIRROR_CONTRIBUTION_OBSERVER_CLOSE_FAILED");
            } finally {
                contributionObserver = null;
            }
        }
        if (ui != null) {
            try {
                ui.resetSession();
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                failures.add("MESH_MIRROR_UI_RESET_FAILED");
            }
        }
        if (axis != null) {
            try {
                axis.resetSession();
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                failures.add("MESH_MIRROR_AXIS_RESET_FAILED");
            }
        }
        failures.forEach(this::report);
        report("MESH_MIRROR_DIAG stage=HOOK_CLOSED");
    }

    private void restoreLoadedTargets(final List<String> failures) {
        // PREMAIN cannot know the host loader yet, so a null hostClassLoader would widen
        // restoration to every loader. Prefer the loader the transformer actually admitted.
        final ClassLoader owner =
                transformer.admittedClassLoader() == null ? hostClassLoader : transformer.admittedClassLoader();
        if (owner == null) return;
        try {
            // One enumeration, but one request per owner: a refused owner must not hide the
            // restore of the owners that follow it.
            final List<Class<?>> targets = LoadedClassIndex.snapshot(instrumentation)
                    .select(owner, type -> isTarget(safeName(type)) && instrumentation.isModifiableClass(type));
            for (final Class<?> type : targets) {
                try {
                    VerifiedHookInstallerSupport.retransformAll(instrumentation, List.of(type));
                } catch (Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                    failures.add("MESH_MIRROR_RESTORE_FAILED owner=" + safeName(type));
                    failures.add("MESH_MIRROR_DIAG stage=RESTORE_FAILED owner=" + safeName(type));
                }
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.add("MESH_MIRROR_RESTORE_ENUMERATION_FAILED");
            failures.add("MESH_MIRROR_DIAG stage=RESTORE_FAILED owner=ENUMERATION");
        }
    }

    private void rejectLoadedTargets() {
        final LoadedClassIndex loaded;
        try {
            loaded = loadedClasses();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            report("MESH_MIRROR_UNAVAILABLE_LOADED_CLASS_ENUMERATION_FAILED");
            throw new IllegalStateException("mesh mirror loaded-class enumeration failed", failure);
        }
        for (final String target : targetClassNames) {
            if (loaded.containsName(target)) {
                report("MESH_MIRROR_UNAVAILABLE_TARGET_ALREADY_LOADED owner=" + target);
                throw new IllegalStateException("mesh mirror target is already loaded: " + target);
            }
        }
    }

    /**
     * @return the phase snapshot this installer was handed, or a fresh one when it installs on its
     *     own outside a phase pass
     */
    private LoadedClassIndex loadedClasses() {
        return loadedClasses != null ? loadedClasses : LoadedClassIndex.snapshot(instrumentation);
    }

    private void report(final String message) {
        diagnostics.add(message);
        try {
            diagnostic.accept(message);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // Diagnostics must not reopen a closed host boundary.
        }
    }

    private static String safeName(final Class<?> type) {
        return type.getName();
    }

    private boolean isTarget(final String name) {
        for (String target : targetClassNames) if (name.equals(target)) return true;
        return false;
    }
}
