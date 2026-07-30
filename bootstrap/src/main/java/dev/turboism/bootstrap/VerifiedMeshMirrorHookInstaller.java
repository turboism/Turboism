package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.mesh.MeshMirrorHostProfile;
import dev.turboism.adapter.cubism.mesh.MeshMirrorNativeMethodTransformer;
import dev.turboism.adapter.cubism.mesh.NativeMeshMirrorBridge;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshEditUiService;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshMirrorAxisService;

import java.lang.instrument.Instrumentation;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the exact reviewed mesh-mirror transformers and bridge lifecycle. */
final class VerifiedMeshMirrorHookInstaller implements AutoCloseable {
    private final Instrumentation instrumentation;
    private final ClassLoader hostClassLoader;
    private final RuntimeMeshMirrorAxisService axis;
    private final MeshMirrorNativeMethodTransformer transformer;
    private final String[] targetClassNames;
    private final AtomicBoolean installed = new AtomicBoolean();

    VerifiedMeshMirrorHookInstaller(
        final Instrumentation instrumentation,
        final ClassLoader hostClassLoader,
        final RuntimeMeshMirrorAxisService axis,
        final RuntimeMeshEditUiService ui,
        final MeshMirrorHostProfile profile
    ) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.axis = Objects.requireNonNull(axis, "axis");
        this.transformer = new MeshMirrorNativeMethodTransformer(profile, hostClassLoader);
        this.targetClassNames = new String[] {
            profile.meshEditorOwner().replace('/', '.'),
            profile.mirrorWidgetOwner().replace('/', '.'),
            profile.mirrorAxisDrawOwner().replace('/', '.')
        };
        NativeMeshMirrorBridge.install(axis, Objects.requireNonNull(ui, "ui"));
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
        if (installed.compareAndSet(true, false)) instrumentation.removeTransformer(transformer);
        NativeMeshMirrorBridge.uninstall();
        axis.setCurrentAngleDegrees(0.0f);
    }

    private boolean isTarget(final String name) {
        for (String target : targetClassNames) if (name.equals(target)) return true;
        return false;
    }
}
