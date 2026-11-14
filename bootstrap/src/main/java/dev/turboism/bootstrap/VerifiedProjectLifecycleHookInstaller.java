package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.lifecycle.EditorLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.NativeProjectLifecycleBridge;
import dev.turboism.adapter.cubism.lifecycle.ProjectFileLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ProjectLifecycleHostProfile;
import dev.turboism.adapter.cubism.lifecycle.ProjectLifecycleNativeMethodTransformer;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
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
            final EditorLifecycleCoordinator editor) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        final ProjectLifecycleHostProfile reviewed = Objects.requireNonNull(profile, "profile");
        this.transformer = new ProjectLifecycleNativeMethodTransformer(reviewed.bindings(), hostClassLoader);
        this.bridge = new NativeProjectLifecycleBridge(
                Objects.requireNonNull(projectFiles, "projectFiles"),
                Objects.requireNonNull(editor, "editor"),
                reviewed.hostVersion());
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
            final var loadedIndex = VerifiedHookInstallerSupport.LoadedClassIndex.snapshot(instrumentation);
            final Set<String> seenLoaded = new java.util.HashSet<>();
            final List<Class<?>> retransformTargets = new ArrayList<>();
            for (Class<?> loaded : loadedIndex.select(hostClassLoader, type -> true)) {
                if (targetClassNames.contains(loaded.getName())) {
                    seenLoaded.add(loaded.getName());
                    if (instrumentation.isModifiableClass(loaded)) {
                        retransformTargets.add(loaded);
                    } else {
                        dev.turboism.runtime.log.RuntimeDiagnostics.warn(
                                "lifecycle",
                                "Lifecycle retransform skipped for " + loaded.getName()
                                        + " loaderMatch=" + (loaded.getClassLoader() == hostClassLoader)
                                        + " modifiable=" + instrumentation.isModifiableClass(loaded));
                    }
                }
            }
            final int retransformed = VerifiedHookInstallerSupport.retransformAll(instrumentation, retransformTargets);
            dev.turboism.runtime.log.RuntimeDiagnostics.info(
                    "lifecycle",
                    "Installed verified lifecycle hooks; retransformed=" + retransformed
                            + " targets=" + targetClassNames
                            + " notYetLoaded="
                            + targetClassNames.stream()
                                    .filter(name -> !seenLoaded.contains(name))
                                    .collect(Collectors.toUnmodifiableSet()));
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
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
