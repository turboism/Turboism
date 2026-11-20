package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.filechooser.FileChooserHistoryHostProfile;
import dev.turboism.adapter.cubism.filechooser.FileChooserHistoryNativeMethodTransformer;
import dev.turboism.adapter.cubism.filechooser.NativeFileChooserHistoryBridge;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;
import java.lang.instrument.Instrumentation;
import java.util.Objects;
import java.util.Set;

/**
 * Installs the verified file-chooser history transformer: before/after bridge
 * calls on the two save-dialog methods of {@code com.live2d.ui.window.n}.
 * Install/close are idempotent.
 */
final class VerifiedFileChooserHistoryHookInstaller implements AutoCloseable {

    private final HookInstallSession session;

    VerifiedFileChooserHistoryHookInstaller(
            final Instrumentation instrumentation,
            final ClassLoader hostClassLoader,
            final FileChooserHistoryHostProfile profile,
            final FileChooserHistoryService service) {
        this(instrumentation, hostClassLoader, profile, service, null);
    }

    /**
     * @param loadedClasses the loaded-class snapshot of this install phase, or {@code null} to
     *     enumerate the loaded classes on install
     */
    VerifiedFileChooserHistoryHookInstaller(
            final Instrumentation instrumentation,
            final ClassLoader hostClassLoader,
            final FileChooserHistoryHostProfile profile,
            final FileChooserHistoryService service,
            final LoadedClassIndex loadedClasses) {
        Objects.requireNonNull(instrumentation, "instrumentation");
        Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        final FileChooserHistoryHostProfile reviewed = Objects.requireNonNull(profile, "profile");
        final FileChooserHistoryNativeMethodTransformer transformer = new FileChooserHistoryNativeMethodTransformer(
                reviewed.fileChooserClassInternalName(), reviewed.saveDialogMethods(), hostClassLoader);
        final NativeFileChooserHistoryBridge bridge =
                new NativeFileChooserHistoryBridge(Objects.requireNonNull(service, "service"), reviewed);
        this.session = HookInstallSession.builder(
                        instrumentation,
                        hostClassLoader,
                        Set.of(reviewed.fileChooserClassInternalName().replace('/', '.')))
                .loadedClasses(loadedClasses)
                .transformer(transformer)
                .bridge(
                        () -> NativeFileChooserHistoryBridge.install(bridge),
                        () -> NativeFileChooserHistoryBridge.uninstall(bridge))
                .retransformOnInstall((inst, loaded, targets, loader) -> {
                    final int retransformed =
                            VerifiedHookInstallerSupport.retransformIfLoaded(inst, loaded, targets, loader);
                    RuntimeDiagnostics.debug(
                            "file-chooser", "Installed verified file-chooser hooks; retransformed=" + retransformed);
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
