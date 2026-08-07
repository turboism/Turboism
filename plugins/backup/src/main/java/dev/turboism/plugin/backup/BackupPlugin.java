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
import dev.turboism.sdk.cubism.hook.AnimationFileHooks;
import dev.turboism.sdk.cubism.hook.ModelFileHooks;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
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
    private volatile Registration eventRegistration;
    private volatile Registration actionRegistration;
    private volatile Registration menuRegistration;
    private boolean enabled;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        binding.init(context.config()).whenComplete((result, failure) -> {
            if (result == ConfigBindingResult.APPLIED) {
                context.logger().info("WebDAV backup sync binding initialized");
            } else {
                context.logger().warn("WebDAV backup sync binding unavailable: "
                    + (failure == null ? result : failure.getClass().getSimpleName()));
            }
        });
        eventRegistration = context.eventBus().subscribe(BackupCompletedEvent.class, this::onBackupCompleted);
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
        binding.disable();
        target = null;
        closeRegistrations();
        enabled = false;
    }

    @Override
    public void shutdown() {
        disable();
        if (eventRegistration != null) {
            eventRegistration.close();
            eventRegistration = null;
        }
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

    /** Rebuilds the sync target from the bound config (fail closed when unavailable). */
    void refreshTarget() {
        if (context == null) {
            return;
        }
        final CompletionStage<WebDavConfig> read = binding.read();
        read.whenComplete((config, failure) -> {
            if (config == null) {
                target = null;
                return;
            }
            final WebDavSyncTarget rebuilt = new WebDavSyncTarget(config, reason -> {
                if (reason.startsWith("webdav:put-ok")) {
                    context.logger().info("webdav-sync " + reason);
                } else {
                    context.logger().warn("webdav-sync " + reason);
                }
            });
            target = rebuilt;
        });
    }

    /**
     * Save-triggered backup: hands the saved snapshot to the runtime
     * coordinator (host dirty condition bypassed, per-document 2s debounce,
     * host calls on the host thread). Never blocks the save callback and never
     * throws into the hook dispatcher.
     */
    private void backupAfterSave(final ProjectContentSnapshot saved) {
        if (context == null || !enabled) {
            return;
        }
        try {
            context.backup().backupAfterSave(saved).whenComplete((event, failure) -> {
                if (failure != null) {
                    final Throwable cause = failure instanceof java.util.concurrent.CompletionException ce
                        && ce.getCause() != null ? ce.getCause() : failure;
                    requireContext().logger().warn(
                        "SAVE_BACKUP_FAILED " + cause.getClass().getSimpleName()
                            + " doc=" + saved.name()
                            + " path=" + saved.filePath().map(Path::toString).orElse("")
                            + " message=" + (cause.getMessage() == null ? "" : cause.getMessage())
                    );
                }
            });
        } catch (RuntimeException | Error failure) {
            requireContext().logger().warn(
                "SAVE_BACKUP_UNAVAILABLE " + failure.getClass().getSimpleName()
            );
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
                        requireContext(), binding, BackupPlugin.this::refreshTarget
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

    private void onBackupCompleted(final BackupCompletedEvent event) {
        final WebDavSyncTarget active = target;
        if (active == null) {
            return;
        }
        try {
            for (File file : event.newBackupFiles()) {
                requireContext().logger().info("WEBDAV_SYNC_UPLOAD file=" + file.getName());
            }
            active.sync(event.newBackupFiles());
            requireContext().logger().info(
                "WEBDAV_SYNC_COMPLETED files=" + event.newBackupFiles().size()
            );
        } catch (RuntimeException | Error failure) {
            // The failure is isolated here: the backup result itself stays intact.
            requireContext().logger().warn(
                "WEBDAV_SYNC_FAILED " + failure.getClass().getSimpleName()
            );
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
