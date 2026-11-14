package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.filechooser.FileChooserHistoryHostProfile;
import dev.turboism.adapter.cubism.filechooser.FileChooserHistoryNativeMethodTransformer;
import dev.turboism.adapter.cubism.filechooser.NativeFileChooserHistoryBridge;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;
import java.lang.instrument.Instrumentation;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Installs the verified file-chooser history transformer: before/after bridge
 * calls on the two save-dialog methods of {@code com.live2d.ui.window.n}.
 * Install/close are idempotent.
 */
final class VerifiedFileChooserHistoryHookInstaller implements AutoCloseable {

    private final Instrumentation instrumentation;
    private final ClassLoader hostClassLoader;
    private final FileChooserHistoryNativeMethodTransformer transformer;
    private final NativeFileChooserHistoryBridge bridge;
    private final String targetClassName;
    private final LoadedClassIndex loadedClasses;
    private final AtomicBoolean installed = new AtomicBoolean(false);

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
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.loadedClasses = loadedClasses;
        final FileChooserHistoryHostProfile reviewed = Objects.requireNonNull(profile, "profile");
        this.transformer = new FileChooserHistoryNativeMethodTransformer(
                reviewed.fileChooserClassInternalName(), reviewed.saveDialogMethods(), hostClassLoader);
        this.bridge = new NativeFileChooserHistoryBridge(Objects.requireNonNull(service, "service"), reviewed);
        this.targetClassName = reviewed.fileChooserClassInternalName().replace('/', '.');
    }

    void install() throws Exception {
        if (!installed.compareAndSet(false, true)) {
            return;
        }
        if (!instrumentation.isRetransformClassesSupported()) {
            installed.set(false);
            throw new IllegalStateException("Class retransformation is unavailable.");
        }
        NativeFileChooserHistoryBridge.install(bridge);
        instrumentation.addTransformer(transformer, true);
        try {
            final int retransformed = VerifiedHookInstallerSupport.retransformIfLoaded(
                    instrumentation, loadedClasses(), Set.of(targetClassName), hostClassLoader);
            dev.turboism.runtime.log.RuntimeDiagnostics.debug(
                    "file-chooser", "Installed verified file-chooser hooks; retransformed=" + retransformed);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            close();
            throw failure;
        }
    }

    @Override
    public void close() {
        if (!installed.compareAndSet(true, false)) {
            return;
        }
        instrumentation.removeTransformer(transformer);
        NativeFileChooserHistoryBridge.uninstall(bridge);
    }

    /**
     * @return the phase snapshot this installer was handed, or a fresh one when it installs on its
     *     own outside a phase pass
     */
    private LoadedClassIndex loadedClasses() {
        return loadedClasses != null ? loadedClasses : LoadedClassIndex.snapshot(instrumentation);
    }
}
