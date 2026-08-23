package dev.turboism.plugin.backup;

import dev.turboism.plugin.backup.b1.application.ConfigBindingResult;
import dev.turboism.plugin.backup.b1.application.WebDavSettingsBinding;
import dev.turboism.plugin.backup.b1.application.WebDavSettingsDialog;
import dev.turboism.plugin.backup.webdav.WebDavConfig;
import dev.turboism.plugin.backup.webdav.WebDavSyncTarget;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.backup.BackupCompletedEvent;
import dev.turboism.sdk.cubism.backup.EditorAutoBackupService;
import dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings;
import dev.turboism.sdk.cubism.hook.AnimationFileHooks;
import dev.turboism.sdk.cubism.hook.ModelFileHooks;
import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * WebDAV auto-backup sync plugin: subscribes to {@link BackupCompletedEvent}
 * and uploads the new backup artifacts through the JDK-only
 * {@link WebDavSyncTarget}; observes model/animation saves and triggers the
 * save-triggered backup through {@link EditorAutoBackupService#backupAfterSave};
 * and exposes the endpoint settings through the {@code Turboism/WebDAV 备份设置}
 * menu dialog. Configuration comes from {@code backup/webdav.cfg}; credentials
 * are never written to logs.
 */
public final class BackupPlugin implements TurboismPlugin, ModelFileHooks, AnimationFileHooks {

    static final String OPEN_SETTINGS_ACTION_ID = "backup.webdav.settings.open";
    static final String MENU_ROOT = "Turboism";
    static final String MENU_LABEL = "WebDAV 备份设置";
    static final int MENU_ORDER = 100;

    private final WebDavSettingsBinding binding = new WebDavSettingsBinding();
    private PluginContext context;
    private volatile WebDavSyncTarget target;
    private volatile Registration actionRegistration;
    private volatile Registration menuRegistration;
    private volatile boolean enabled;
    private volatile WebDavConfig lastSavedConfig;
    private volatile WebDavConfig.RemoteTrigger triggerMode = WebDavConfig.RemoteTrigger.SAVE_TRIGGERED;
    private final Set<File> pendingTempFiles = ConcurrentHashMap.newKeySet();
    private final Set<String> scannedArtifacts = ConcurrentHashMap.newKeySet();
    private volatile Thread scannerThread;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        binding.init(context.config()).whenComplete((result, failure) -> {
            if (result == ConfigBindingResult.APPLIED) {
                context.logger().info("WebDAV backup sync binding initialized");
                // The binding may have been enabled (and refreshTarget() raced)
                // before the async registerSchema completed; rebuild the target now.
                if (enabled) {
                    refreshTarget();
                }
            } else {
                context.logger().warn("WebDAV backup sync binding unavailable: "
                    + (failure == null ? result : failure.getClass().getSimpleName()));
            }
        });
    }

    @Override
    public void enable() {
        binding.enable();
        refreshTarget();
        registerMenuAndAction();
        enabled = true;
    }

    @Override
    public void disable() {
        enabled = false;
        binding.disable();
        target = null;
        lastSavedConfig = null;
        stopScanner();
        cancelTargetRetry();
        cleanupPendingTempFiles();
        closeRegistrations();
    }

    @Override
    public void shutdown() {
        disable();
        binding.shutdown();
        context = null;
    }

    @Override
    public void onModelSaved(final ProjectContentSnapshot model) {
        backupAfterSave(model);
    }

    @Override
    public void onAnimationSaved(final ProjectContentSnapshot animation) {
        backupAfterSave(animation);
    }

    /**
     * Rebuilds the sync target from the bound config (fail closed when
     * unavailable). A null config means the binding is not initialized yet
     * (async registerSchema) or broken: schedule a bounded retry with backoff
     * so the init/enable race cannot leave the plugin without a target.
     */
    void refreshTarget() {
        if (context == null) {
            return;
        }
        final PluginLogger logger = context.logger();
        final CompletionStage<WebDavConfig> read = binding.read();
        read.whenComplete((config, failure) -> {
            if (config == null) {
                target = null;
                if (failure != null) {
                    logger.warn("WEBDAV_TARGET_UNAVAILABLE reason="
                        + failure.getClass().getSimpleName());
                    return;
                }
                scheduleTargetRetry(logger);
                return;
            }
            targetRetryAttempts = 0;
            target = buildTarget(config);
            logger.info("WEBDAV_TARGET_READY url=" + sanitizedUrl(config)
                + " remotePath=" + config.remotePath()
                + " enabled=" + config.enabled());
            syncTriggerMode(config);
        });
    }

    /**
     * Deterministic target construction from the exact config the dialog just
     * persisted — no async binding read involved. Remembered in
     * {@link #lastSavedConfig} so a later event can rebuild the target lazily.
     */
    void applySavedConfig(final WebDavConfig config) {
        Objects.requireNonNull(config, "config");
        lastSavedConfig = config;
        target = buildTarget(config);
        requireContext().logger().info("WEBDAV_TARGET_READY url=" + sanitizedUrl(config)
            + " remotePath=" + config.remotePath()
            + " enabled=" + config.enabled());
        syncTriggerMode(config);
    }

    /** Shared target construction with the put-ok/error diagnostics routing. */
    private WebDavSyncTarget buildTarget(final WebDavConfig config) {
        final PluginLogger logger = requireContext().logger();
        return new WebDavSyncTarget(config, reason -> {
            if (reason.startsWith("webdav:put-ok")) {
                logger.info("webdav-sync " + reason);
            } else {
                logger.warn("webdav-sync " + reason);
            }
        });
    }

    /** Bounded target-rebuild retry backoff (ms); five attempts, ~15.5s total. */
    static final long[] TARGET_RETRY_BACKOFF_MILLIS = {500L, 1_000L, 2_000L, 4_000L, 8_000L};

    /** Save-triggered temp artifact directory prefix (runtime convention). */
    static final String TEMP_DIR_PREFIX = "turboism-backup-";

    /** AUTO_BACKUP_SYNC scan period. */
    static final long AUTO_BACKUP_SCAN_INTERVAL_MILLIS = 30_000L;

    private volatile int targetRetryAttempts;
    private final java.util.concurrent.atomic.AtomicBoolean targetRetryPending =
        new java.util.concurrent.atomic.AtomicBoolean();

    private void scheduleTargetRetry(final PluginLogger logger) {
        if (!targetRetryPending.compareAndSet(false, true)) {
            return; // a retry chain is already scheduled
        }
        final int attempt = targetRetryAttempts;
        if (attempt >= TARGET_RETRY_BACKOFF_MILLIS.length) {
            targetRetryPending.set(false);
            logger.warn("WEBDAV_TARGET_UNAVAILABLE reason=unavailable");
            return;
        }
        targetRetryAttempts = attempt + 1;
        logger.info("WEBDAV_TARGET_RETRY attempt=" + (attempt + 1)
            + " backoffMs=" + TARGET_RETRY_BACKOFF_MILLIS[attempt]);
        final Thread retry = new Thread(() -> {
            try {
                Thread.sleep(TARGET_RETRY_BACKOFF_MILLIS[attempt]);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                targetRetryPending.set(false);
                return;
            }
            targetRetryPending.set(false);
            if (!enabled || context == null) {
                return; // cancelled by disable()/shutdown()
            }
            refreshTarget();
        }, "turboism-webdav-target-retry");
        retry.setDaemon(true);
        retry.start();
    }

    private void cancelTargetRetry() {
        targetRetryAttempts = 0;
        targetRetryPending.set(false);
    }

    /** URL without credentials: scheme + host + port + path only (never userinfo). */
    private static String sanitizedUrl(final WebDavConfig config) {
        final java.net.URI uri = config.url();
        final StringBuilder url = new StringBuilder();
        if (uri.getScheme() != null) {
            url.append(uri.getScheme()).append("://");
        }
        if (uri.getHost() != null) {
            url.append(uri.getHost());
        }
        if (uri.getPort() > 0) {
            url.append(':').append(uri.getPort());
        }
        if (uri.getPath() != null) {
            url.append(uri.getPath());
        }
        return url.toString();
    }

    /**
     * Save-triggered backup: hands the saved snapshot to the runtime
     * coordinator (host dirty condition bypassed, per-document 2s debounce,
     * host calls on the host thread). Never blocks the save callback and never
     * throws into the hook dispatcher.
     */
    private void backupAfterSave(final ProjectContentSnapshot saved) {
        if (context == null || !enabled
            || triggerMode != WebDavConfig.RemoteTrigger.SAVE_TRIGGERED) {
            return; // AUTO_BACKUP_SYNC: the scanner owns the upload path
        }
        try {
            final PluginContext callbackContext = context;
            callbackContext.backup().backupAfterSave(saved).whenComplete((event, failure) -> {
                if (!enabled || context != callbackContext) {
                    return;
                }
                if (failure != null) {
                    final Throwable cause = failure instanceof java.util.concurrent.CompletionException ce
                        && ce.getCause() != null ? ce.getCause() : failure;
                    callbackContext.logger().warn(
                        "SAVE_BACKUP_FAILED " + cause.getClass().getSimpleName()
                            + " doc=" + saved.name()
                            + " path=" + saved.filePath().map(Path::toString).orElse("")
                            + " message=" + (cause.getMessage() == null ? "" : cause.getMessage())
                    );
                } else if (event != null) {
                    for (File file : event.newBackupFiles()) {
                        if (isTempBackupFile(file)) {
                            pendingTempFiles.add(file);
                        }
                    }
                    syncCompletedArtifacts(event.newBackupFiles());
                    callbackContext.logger().info(
                        "BACKUP_AFTER_SAVE_OK files=" + event.newBackupFiles().size()
                    );
                }
            });
        } catch (RuntimeException | Error failure) {
            final PluginContext activeContext = context;
            if (activeContext != null) {
                activeContext.logger().warn(
                    "SAVE_BACKUP_UNAVAILABLE " + failure.getClass().getSimpleName()
                );
            }
        }
    }

    private void registerMenuAndAction() {
        final Registration action = requireContext().actions().register(
            OPEN_SETTINGS_ACTION_ID,
            new ActionRegistry.Action() {
                @Override
                public String id() {
                    return OPEN_SETTINGS_ACTION_ID;
                }

                @Override
                public String label() {
                    return MENU_LABEL;
                }

                @Override
                public Consumer<ActionRegistry.ActionContext> handler() {
                    return ignored -> WebDavSettingsDialog.open(
                        requireContext(), binding, BackupPlugin.this::applySavedConfig
                    );
                }
            }
        );
        final Registration menu = requireContext().menus().contribute(new MenuRegistry.MenuContribution() {
            @Override
            public String menuPath() {
                return MENU_ROOT + "/" + MENU_LABEL;
            }

            @Override
            public String actionId() {
                return OPEN_SETTINGS_ACTION_ID;
            }

            @Override
            public int order() {
                return MENU_ORDER;
            }
        });
        actionRegistration = action;
        menuRegistration = menu;
    }

    private void closeRegistrations() {
        if (actionRegistration != null) {
            actionRegistration.close();
            actionRegistration = null;
        }
        if (menuRegistration != null) {
            menuRegistration.close();
            menuRegistration = null;
        }
    }

    @SubscribeEvent
    public void onBackupCompleted(final BackupCompletedEvent event) {
        if (triggerMode == WebDavConfig.RemoteTrigger.SAVE_TRIGGERED) {
            requireContext().logger().info(
                "BACKUP_COMPLETED artifacts=" + event.artifacts().size()
            );
        }
    }

    private void syncCompletedArtifacts(final List<File> files) {
        WebDavSyncTarget active = target;
        if (active == null && lastSavedConfig != null) {
            synchronized (this) {
                if (target == null) {
                    target = buildTarget(lastSavedConfig);
                    requireContext().logger().info("WEBDAV_TARGET_LAZY_REBUILT url="
                        + sanitizedUrl(lastSavedConfig)
                        + " remotePath=" + lastSavedConfig.remotePath()
                        + " enabled=" + lastSavedConfig.enabled());
                }
                active = target;
            }
        }
        if (active == null) {
            requireContext().logger().info("WEBDAV_SYNC_SKIPPED reason=target-unavailable");
            cleanupTempFiles(files);
            return;
        }
        try {
            for (File file : files) {
                requireContext().logger().info("WEBDAV_SYNC_UPLOAD file=" + file.getName());
            }
            active.sync(files);
            requireContext().logger().info("WEBDAV_SYNC_COMPLETED files=" + files.size());
        } catch (RuntimeException | Error failure) {
            requireContext().logger().warn(
                "WEBDAV_SYNC_FAILED " + failure.getClass().getSimpleName()
            );
        } finally {
            cleanupTempFiles(files);
        }
    }

    /** True when the artifact comes from the save-triggered temp flow. */
    private static boolean isTempBackupFile(final File file) {
        return file != null && file.getParentFile() != null
            && file.getParentFile().getName().startsWith(TEMP_DIR_PREFIX);
    }

    private void cleanupTempFiles(final List<File> files) {
        for (File file : files) {
            if (isTempBackupFile(file)) {
                pendingTempFiles.remove(file);
                deleteTempFile(file);
            }
        }
    }

    private void cleanupPendingTempFiles() {
        for (File file : List.copyOf(pendingTempFiles)) {
            pendingTempFiles.remove(file);
            deleteTempFile(file);
        }
    }

    private void deleteTempFile(final File file) {
        try {
            Files.deleteIfExists(file.toPath());
            final Path dir = file.getParentFile().toPath();
            try (var entries = Files.list(dir)) {
                if (entries.findAny().isEmpty()) {
                    Files.deleteIfExists(dir);
                }
            }
            requireContext().logger().info("WEBDAV_TEMP_CLEANUP file=" + file.getName());
        } catch (IOException failure) {
            requireContext().logger().warn("WEBDAV_TEMP_CLEANUP_FAILED file=" + file.getName());
        }
    }

    /** Applies the trigger mode from the active config and (re)starts/stopping the scanner. */
    private void syncTriggerMode(final WebDavConfig config) {
        triggerMode = config.remoteTrigger();
        requireContext().logger().info("WEBDAV_TRIGGER_MODE mode=" + triggerMode.name());
        if (triggerMode == WebDavConfig.RemoteTrigger.AUTO_BACKUP_SYNC) {
            startScanner();
        } else {
            stopScanner();
        }
    }

    private void startScanner() {
        stopScanner();
        scannedArtifacts.clear();
        final Thread scanner = new Thread(this::scanLoop, "turboism-webdav-auto-backup-scanner");
        scannerThread = scanner;
        scanner.setDaemon(true);
        scanner.start();
    }

    private void stopScanner() {
        final Thread scanner = scannerThread;
        scannerThread = null;
        if (scanner != null) {
            scanner.interrupt();
        }
    }

    private void scanLoop() {
        while (scannerThread == Thread.currentThread()) {
            try {
                scanOnce();
            } catch (RuntimeException | Error failure) {
                requireContext().logger().warn(
                    "WEBDAV_AUTO_SCAN_FAILED " + failure.getClass().getSimpleName()
                );
            }
            try {
                Thread.sleep(AUTO_BACKUP_SCAN_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Uploads new {@code _backup*.cmo3} host artifacts (dedup by name+size); never deletes them. */
    void scanOnce() {
        final WebDavSyncTarget active = target;
        if (active == null || context == null) {
            return;
        }
        final EditorAutoBackupSettings settings = requireContext().backup().settings();
        final String backupDirPath = settings.backupDir();
        if (backupDirPath == null) {
            return;
        }
        final Path backupDir = Path.of(backupDirPath);
        if (!Files.isDirectory(backupDir)) {
            return;
        }
        try (var stream = Files.list(backupDir)) {
            final List<File> fresh = stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().contains("_backup"))
                .filter(path -> path.getFileName().toString().endsWith(".cmo3"))
                .filter(path -> {
                    try {
                        return Files.size(path) > 0
                            && scannedArtifacts.add(path.getFileName() + ":" + Files.size(path));
                    } catch (IOException failure) {
                        return false;
                    }
                })
                .map(Path::toFile)
                .sorted(java.util.Comparator.comparing(File::getName))
                .toList();
            for (File file : fresh) {
                requireContext().logger().info("WEBDAV_SYNC_UPLOAD file=" + file.getName());
            }
            if (!fresh.isEmpty()) {
                active.sync(fresh);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("webdav auto-backup scan failed", failure);
        }
    }

    boolean isEnabled() {
        return enabled;
    }

    private PluginContext requireContext() {
        if (context == null) {
            throw new IllegalStateException("backup plugin must be initialized");
        }
        return context;
    }
}
