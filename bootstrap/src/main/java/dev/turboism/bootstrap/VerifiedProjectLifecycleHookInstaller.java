package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.lifecycle.EditorLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.NativeProjectLifecycleBridge;
import dev.turboism.adapter.cubism.lifecycle.ProjectFileLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ProjectLifecycleHostProfile;
import dev.turboism.adapter.cubism.lifecycle.ProjectLifecycleNativeMethodTransformer;

import java.lang.instrument.Instrumentation;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** Installs exact model/animation file and editor-exit lifecycle transformers. */
final class VerifiedProjectLifecycleHookInstaller implements AutoCloseable {

    private final Instrumentation instrumentation;
    private final ClassLoader hostClassLoader;
    private final ProjectLifecycleNativeMethodTransformer transformer;
    private final NativeProjectLifecycleBridge bridge;
    private final Set<String> targetClassNames;
    private final AtomicBoolean installed = new AtomicBoolean(false);

    VerifiedProjectLifecycleHookInstaller(
        final Instrumentation instrumentation,
        final ClassLoader hostClassLoader,
        final ProjectLifecycleHostProfile profile,
        final ProjectFileLifecycleCoordinator projectFiles,
        final EditorLifecycleCoordinator editor
    ) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        final ProjectLifecycleHostProfile reviewed = Objects.requireNonNull(profile, "profile");
        this.transformer = new ProjectLifecycleNativeMethodTransformer(
            reviewed.bindings(),
            hostClassLoader
        );
        this.bridge = new NativeProjectLifecycleBridge(
            Objects.requireNonNull(projectFiles, "projectFiles"),
            Objects.requireNonNull(editor, "editor"),
            reviewed.hostVersion()
        );
        this.targetClassNames = reviewed.bindings().stream()
            .map(binding -> binding.ownerInternalName().replace('/', '.'))
            .collect(Collectors.toUnmodifiableSet());
    }

    void install() throws Exception {
        if (!installed.compareAndSet(false, true)) return;
        if (!instrumentation.isRetransformClassesSupported()) {
            installed.set(false);
            throw new IllegalStateException("Class retransformation is unavailable.");
        }
        NativeProjectLifecycleBridge.install(bridge);
        instrumentation.addTransformer(transformer, true);
        try {
            int retransformed = 0;
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (targetClassNames.contains(loaded.getName())) {
                    if (loaded.getClassLoader() == hostClassLoader
                        && instrumentation.isModifiableClass(loaded)) {
                        instrumentation.retransformClasses(loaded);
                        retransformed++;
                    }
                }
            }
            dev.turboism.runtime.log.RuntimeDiagnostics.debug(
                "lifecycle",
                "Installed verified lifecycle hooks; retransformed=" + retransformed
            );
        } catch (Throwable failure) {
            close();
            throw failure;
        }
    }

    @Override
    public void close() {
        if (!installed.compareAndSet(true, false)) return;
        instrumentation.removeTransformer(transformer);
        NativeProjectLifecycleBridge.uninstall(bridge);
    }
}
