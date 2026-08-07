package dev.turboism.plugin.backup;

import dev.turboism.plugin.backup.b1.application.ConfigBindingResult;
import dev.turboism.plugin.backup.b1.application.WebDavSettingsBinding;
import dev.turboism.plugin.backup.webdav.WebDavConfig;
import dev.turboism.plugin.backup.webdav.WebDavSyncTarget;
import dev.turboism.sdk.cubism.backup.BackupCompletedEvent;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * WebDAV auto-backup sync plugin: subscribes to {@link BackupCompletedEvent}
 * and uploads the new backup artifacts through the JDK-only
 * {@link WebDavSyncTarget}. Configuration comes from {@code backup/webdav.cfg};
 * credentials are never written to logs.
 */
public final class BackupPlugin implements TurboismPlugin {

    private final WebDavSettingsBinding binding = new WebDavSettingsBinding();
    private PluginContext context;
    private volatile WebDavSyncTarget target;
    private volatile Registration eventRegistration;
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
        enabled = true;
    }

    @Override
    public void disable() {
        binding.disable();
        target = null;
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
            final WebDavSyncTarget rebuilt = new WebDavSyncTarget(config, reason ->
                context.logger().warn("webdav-sync " + reason)
            );
            target = rebuilt;
        });
    }

    private void onBackupCompleted(final BackupCompletedEvent event) {
        final WebDavSyncTarget active = target;
        if (active == null) {
            return;
        }
        try {
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
