package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.mesh.MeshMirrorHostProfile;
import dev.turboism.adapter.cubism.mesh.MeshMirrorNativeMethodTransformer;
import dev.turboism.adapter.cubism.mesh.MeshMirrorHelperBootstrap;
import dev.turboism.adapter.cubism.mesh.NativeMeshMirrorBridge;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshEditUiService;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshMirrorAxisService;

import java.lang.instrument.Instrumentation;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Owns the exact reviewed mesh-mirror transformers and bridge lifecycle. */
final class VerifiedMeshMirrorHookInstaller implements AutoCloseable {
    private final Instrumentation instrumentation;
    private final ClassLoader hostClassLoader;
    private final RuntimeMeshMirrorAxisService axis;
    private final RuntimeMeshEditUiService ui;
    private final MeshMirrorNativeMethodTransformer transformer;
    private final String[] targetClassNames;
    private final AtomicBoolean installed = new AtomicBoolean();
    private final Consumer<String> diagnostic;
    private final dev.turboism.sdk.plugin.Registration contributionObserver;

    VerifiedMeshMirrorHookInstaller(
        final Instrumentation instrumentation,
        final ClassLoader hostClassLoader,
        final RuntimeMeshMirrorAxisService axis,
        final RuntimeMeshEditUiService ui,
        final MeshMirrorHostProfile profile
    ) {
        this(instrumentation, hostClassLoader, axis, ui, profile, System.err::println);
    }

    VerifiedMeshMirrorHookInstaller(
        final Instrumentation instrumentation,
        final ClassLoader hostClassLoader,
        final RuntimeMeshMirrorAxisService axis,
        final RuntimeMeshEditUiService ui,
        final MeshMirrorHostProfile profile,
        final Consumer<String> diagnostic
    ) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.axis = Objects.requireNonNull(axis, "axis");
        this.ui = Objects.requireNonNull(ui, "ui");
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        MeshMirrorHelperBootstrap.ensureAvailable(instrumentation, hostClassLoader);
        this.transformer = new MeshMirrorNativeMethodTransformer(profile, hostClassLoader);
        this.targetClassNames = new String[] {
            profile.meshEditorOwner().replace('/', '.'),
            profile.mirrorWidgetOwner().replace('/', '.'),
            profile.mirrorAxisDrawOwner().replace('/', '.')
        };
        NativeMeshMirrorBridge.install(axis, this.ui);
        this.contributionObserver = ui.observeContribution(available -> {
            if (!available && installed.get()) close();
        });
    }

    void install() {
        if (!installed.compareAndSet(false, true)) return;
        instrumentation.addTransformer(transformer, true);
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (type.getClassLoader() == hostClassLoader
                && isTarget(type.getName())
                && instrumentation.isModifiableClass(type)) {
                try {
                    instrumentation.retransformClasses(type);
                } catch (Throwable failure) {
                    close();
                    throw new IllegalStateException("mesh mirror target retransform failed", failure);
                }
            }
        }
    }

    @Override
    public void close() {
        final List<String> failures = new ArrayList<>();
        try {
            if (installed.compareAndSet(true, false)) {
                try {
                    if (!instrumentation.removeTransformer(transformer)) {
                        failures.add("MESH_MIRROR_TRANSFORMER_REMOVE_FAILED");
                    }
                } catch (Throwable failure) {
                    failures.add("MESH_MIRROR_TRANSFORMER_REMOVE_FAILED");
                }
                try {
                    for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                        try {
                            if (type.getClassLoader() == hostClassLoader
                                && isTarget(type.getName())
                                && instrumentation.isModifiableClass(type)) {
                                instrumentation.retransformClasses(type);
                            }
                        } catch (Throwable failure) {
                            failures.add("MESH_MIRROR_RESTORE_FAILED owner=" + safeName(type));
                        }
                    }
                } catch (Throwable failure) {
                    failures.add("MESH_MIRROR_RESTORE_ENUMERATION_FAILED");
                }
            }
        } finally {
            contributionObserver.close();
            NativeMeshMirrorBridge.uninstall();
            ui.resetSession();
            axis.resetSession();
        }
        for (String failure : failures) report(failure);
    }

    private void report(final String message) {
        try {
            diagnostic.accept(message);
        } catch (RuntimeException ignored) {
            System.err.println(message);
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
