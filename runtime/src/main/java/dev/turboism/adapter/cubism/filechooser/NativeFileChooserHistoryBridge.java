package dev.turboism.adapter.cubism.filechooser;

import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Static bridge called only by verified file-chooser bytecode transformers.
 *
 * <p>Ingress is fail-closed: when the separation feature is disabled, when the
 * dialog was not opened from an export flow, or when reflection fails, the
 * bridge does nothing and never destabilizes the save dialog.
 */
public final class NativeFileChooserHistoryBridge {

    private static final AtomicReference<NativeFileChooserHistoryBridge> INSTALLED =
        new AtomicReference<>();

    private final FileChooserHistoryService service;
    private final FileChooserHistoryHostProfile profile;

    public NativeFileChooserHistoryBridge(
        final FileChooserHistoryService service,
        final FileChooserHistoryHostProfile profile
    ) {
        this.service = Objects.requireNonNull(service, "service");
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    public static void install(final NativeFileChooserHistoryBridge bridge) {
        if (!INSTALLED.compareAndSet(null, Objects.requireNonNull(bridge, "bridge"))) {
            throw new IllegalStateException("Native file-chooser history bridge is already installed.");
        }
    }

    public static void uninstall(final NativeFileChooserHistoryBridge bridge) {
        INSTALLED.compareAndSet(Objects.requireNonNull(bridge, "bridge"), null);
    }

    public static void onSaveDialogPreparing(final Object chooser) {
        final NativeFileChooserHistoryBridge bridge = INSTALLED.get();
        if (bridge == null) {
            return;
        }
        bridge.prepare(chooser);
    }

    public static void onSaveDialogFinished(final Object chooser) {
        final NativeFileChooserHistoryBridge bridge = INSTALLED.get();
        if (bridge == null) {
            return;
        }
        bridge.finish(chooser);
    }

    private void prepare(final Object chooser) {
        try {
            if (!service.exportSeparationEnabled() || !isExportContext()) {
                return;
            }
            final Optional<Path> exportDirectory = service.exportRecentDirectory();
            if (exportDirectory.isEmpty()) {
                return;
            }
            FileChooserHistoryHostAdapter.applyHistory(
                chooser,
                List.of(exportDirectory.orElseThrow().toFile())
            );
        } catch (Throwable failure) {
            System.err.println(
                "Turboism file-chooser history apply failed safely: "
                    + failure.getClass().getName() + ": " + failure.getMessage()
            );
        }
    }

    private void finish(final Object chooser) {
        try {
            if (!service.exportSeparationEnabled() || !isExportContext()) {
                return;
            }
            final List<File> history = FileChooserHistoryHostAdapter.captureHistory(chooser);
            if (history.isEmpty()) {
                return;
            }
            service.setExportRecentDirectory(history.get(0).toPath());
        } catch (Throwable failure) {
            System.err.println(
                "Turboism file-chooser history capture failed safely: "
                    + failure.getClass().getName() + ": " + failure.getMessage()
            );
        }
    }

    private boolean isExportContext() {
        final StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        if (stack == null) {
            return false;
        }
        for (StackTraceElement element : stack) {
            if (element == null) {
                continue;
            }
            final String className = element.getClassName() == null
                ? "" : element.getClassName().trim();
            if (profile.exportContextClassNames().contains(className)) {
                return true;
            }
        }
        return false;
    }
}
