package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.lifecycle.EditorLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.NativeProjectLifecycleBridge;
import dev.turboism.adapter.cubism.lifecycle.ProjectFileLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ProjectLifecycleHostProfile;
import dev.turboism.adapter.cubism.lifecycle.ProjectLifecycleNativeMethodTransformer;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Installs exact model/animation file and editor-exit lifecycle transformers. */
final class VerifiedProjectLifecycleHookInstaller implements AutoCloseable {

    private final HookInstallSession session;

    VerifiedProjectLifecycleHookInstaller(
            final Instrumentation instrumentation,
            final ClassLoader hostClassLoader,
            final ProjectLifecycleHostProfile profile,
            final ProjectFileLifecycleCoordinator projectFiles,
            final EditorLifecycleCoordinator editor) {
        this(instrumentation, hostClassLoader, profile, projectFiles, editor, null);
    }

    /**
     * @param loadedClasses the loaded-class snapshot of this install phase, or {@code null} to
     *     enumerate the loaded classes on install
     */
    VerifiedProjectLifecycleHookInstaller(
            final Instrumentation instrumentation,
            final ClassLoader hostClassLoader,
            final ProjectLifecycleHostProfile profile,
            final ProjectFileLifecycleCoordinator projectFiles,
            final EditorLifecycleCoordinator editor,
            final LoadedClassIndex loadedClasses) {
        Objects.requireNonNull(instrumentation, "instrumentation");
        Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        final ProjectLifecycleHostProfile reviewed = Objects.requireNonNull(profile, "profile");
        final ProjectLifecycleNativeMethodTransformer transformer =
                new ProjectLifecycleNativeMethodTransformer(reviewed.bindings(), hostClassLoader);
        final NativeProjectLifecycleBridge bridge = new NativeProjectLifecycleBridge(
                Objects.requireNonNull(projectFiles, "projectFiles"),
                Objects.requireNonNull(editor, "editor"),
                reviewed.hostVersion());
        final Set<String> targetClassNames = reviewed.bindings().stream()
                .map(binding -> binding.ownerInternalName().replace('/', '.'))
                .collect(Collectors.toUnmodifiableSet());
        this.session = HookInstallSession.builder(instrumentation, hostClassLoader, targetClassNames)
                .loadedClasses(loadedClasses)
                .transformer(transformer)
                .bridge(
                        () -> NativeProjectLifecycleBridge.install(bridge),
                        () -> NativeProjectLifecycleBridge.uninstall(bridge))
                .retransformOnInstall((inst, loadedIndex, targets, loader) -> {
                    final Set<String> seenLoaded = new HashSet<>();
                    final List<Class<?>> retransformTargets = new ArrayList<>();
                    for (final Class<?> loaded : loadedIndex.select(loader, type -> true)) {
                        if (targetClassNames.contains(loaded.getName())) {
                            seenLoaded.add(loaded.getName());
                            if (inst.isModifiableClass(loaded)) {
                                retransformTargets.add(loaded);
                            } else {
                                RuntimeDiagnostics.warn(
                                        "lifecycle",
                                        "Lifecycle retransform skipped for " + loaded.getName()
                                                + " loaderMatch=" + (loaded.getClassLoader() == loader)
                                                + " modifiable=" + inst.isModifiableClass(loaded));
                            }
                        }
                    }
                    final int retransformed = VerifiedHookInstallerSupport.retransformAll(inst, retransformTargets);
                    RuntimeDiagnostics.info(
                            "lifecycle",
                            "Installed verified lifecycle hooks; retransformed=" + retransformed
                                    + " targets=" + targetClassNames
                                    + " notYetLoaded="
                                    + targetClassNames.stream()
                                            .filter(name -> !seenLoaded.contains(name))
                                            .collect(Collectors.toUnmodifiableSet()));
                })
                .build();
    }

    void install() throws Exception {
        session.install();
    }

    @Override
    public void close() {
        session.close();
    }
}
