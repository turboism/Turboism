package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.filechooser.FileChooserHistoryHostProfile;
import dev.turboism.adapter.cubism.filechooser.FileChooserHistoryNativeMethodTransformer;
import dev.turboism.adapter.cubism.filechooser.NativeFileChooserHistoryBridge;
import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;

import java.lang.instrument.Instrumentation;
import java.util.Objects;
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
    private final AtomicBoolean installed = new AtomicBoolean(false);

    VerifiedFileChooserHistoryHookInstaller(
        final Instrumentation instrumentation,
        final ClassLoader hostClassLoader,
        final FileChooserHistoryHostProfile profile,
        final FileChooserHistoryService service
    ) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        final FileChooserHistoryHostProfile reviewed = Objects.requireNonNull(profile, "profile");
        this.transformer = new FileChooserHistoryNativeMethodTransformer(
            reviewed.fileChooserClassInternalName(),
            reviewed.saveDialogMethods(),
            hostClassLoader
        );
        this.bridge = new NativeFileChooserHistoryBridge(
            Objects.requireNonNull(service, "service"),
            reviewed
        );
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
            int retransformed = 0;
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (targetClassName.equals(loaded.getName())
                    && loaded.getClassLoader() == hostClassLoader
                    && instrumentation.isModifiableClass(loaded)) {
                    System.out.println("FILE-CHOOSER-INSTALL:found-class=" + loaded.getName());
                    instrumentation.retransformClasses(loaded);
                    retransformed++;
                }
            }
            System.out.println("FILE-CHOOSER-INSTALL:target=" + targetClassName
                + " retransformed=" + retransformed);
        } catch (Throwable failure) {
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
}
