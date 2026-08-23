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
 * <p>Both contexts are handled when separation is enabled: export-flow save
 * dialogs apply/capture {@code exportRecentDirectory}, all other save dialogs
 * (project saves) apply/capture {@code projectRecentDirectory}. Ingress is
 * fail-closed: when the separation feature is disabled or reflection fails,
 * the bridge does nothing and never destabilizes the save dialog.
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

    /**
     * Publishes {@code bridge} as the single process-wide target for transformed save-dialog calls.
     *
     * <p>At most one bridge may be installed at a time; the swap is atomic, so concurrent installs
     * cannot both win.
     *
     * @param bridge the bridge to install
     * @throws IllegalStateException if a bridge is already installed
     * @throws NullPointerException if {@code bridge} is {@code null}
     */
    public static void install(final NativeFileChooserHistoryBridge bridge) {
        if (!INSTALLED.compareAndSet(null, Objects.requireNonNull(bridge, "bridge"))) {
            throw new IllegalStateException("Native file-chooser history bridge is already installed.");
        }
    }

    /**
     * Removes {@code bridge} if it is the currently installed one; a no-op otherwise, so an
     * out-of-order uninstall cannot detach a bridge someone else installed. Transformed bytecode
     * remains in place and simply becomes inert.
     *
     * @param bridge the bridge to remove
     * @throws NullPointerException if {@code bridge} is {@code null}
     */
    public static void uninstall(final NativeFileChooserHistoryBridge bridge) {
        INSTALLED.compareAndSet(Objects.requireNonNull(bridge, "bridge"), null);
    }

    /**
     * Ingress called by transformed host bytecode as a save dialog is being prepared; applies the
     * remembered directory for the current context (export vs. project, decided by scanning the
     * calling stack for the profile's export-context classes).
     *
     * <p>Runs on the host's dialog thread. Fail-closed and never throws: it returns silently when no
     * bridge is installed, when export separation is disabled, or when no directory is remembered,
     * and any failure below it is caught and logged rather than propagated into the save dialog.
     *
     * @param chooser the host file-chooser instance being prepared
     */
    public static void onSaveDialogPreparing(final Object chooser) {
        final NativeFileChooserHistoryBridge bridge = INSTALLED.get();
        if (bridge == null) {
            return;
        }
        bridge.prepare(chooser);
    }

    /**
     * Ingress called by transformed host bytecode once a save dialog has produced its result;
     * captures the chooser's most recent directory into the export or project slot depending on the
     * calling context.
     *
     * <p>Runs on the host's dialog thread. Fail-closed and never throws: it returns silently when no
     * bridge is installed, when export separation is disabled, or when the chooser reported no
     * history, and any failure below it is caught and logged.
     *
     * @param chooser the host file-chooser instance that just finished
     */
    public static void onSaveDialogFinished(final Object chooser) {
        final NativeFileChooserHistoryBridge bridge = INSTALLED.get();
        if (bridge == null) {
            return;
        }
        bridge.finish(chooser);
    }

    private void prepare(final Object chooser) {
        try {
            if (!service.exportSeparationEnabled()) {
                return;
            }
            final Optional<Path> directory = isExportContext()
                ? service.exportRecentDirectory()
                : service.projectRecentDirectory();
            if (directory.isEmpty()) {
                return;
            }
            FileChooserHistoryHostAdapter.applyHistory(
                chooser,
                List.of(directory.orElseThrow().toFile())
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
            if (!service.exportSeparationEnabled()) {
                return;
            }
            final List<File> history = FileChooserHistoryHostAdapter.captureHistory(chooser);
            if (history.isEmpty()) {
                return;
            }
            if (isExportContext()) {
                service.setExportRecentDirectory(history.get(0).toPath());
            } else {
                service.setProjectRecentDirectory(history.get(0).toPath());
            }
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
