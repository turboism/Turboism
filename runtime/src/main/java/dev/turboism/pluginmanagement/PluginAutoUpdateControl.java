package dev.turboism.pluginmanagement;

import dev.turboism.internal.core.CoreUpdateService;
import dev.turboism.sdk.plugin.Registration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Runtime-internal control surface for official first-party plugin automatic updates,
 * consumed by {@code CoreShell}, {@code CoreWindows}, and {@code CoreUpdateSettingsContribution}.
 */
public interface PluginAutoUpdateControl extends AutoCloseable {

    /** Action ID for triggering an on-demand plugin update check. */
    String MANUAL_CHECK_ACTION_ID = "turboism.core.plugins.update.check";

    /** Lifecycle status of the official-plugin auto-updater. */
    enum Status {
        /** No check has completed yet. */
        IDLE,
        /** A catalog check or artifact download is in progress. */
        CHECKING,
        /** One or more official plugin updates have been staged for the next restart. */
        UPDATES_STAGED,
        /** All installed official plugins are up to date for the current environment. */
        UP_TO_DATE,
        /** Safe mode is active or the catalog check could not be completed. */
        UNAVAILABLE,
        /** Automatic official-plugin updates are turned off by user preference. */
        DISABLED,
        /** The service has been closed. */
        CLOSED
    }

    /**
     * Immutable snapshot of the official-plugin auto-updater state.
     *
     * @param status current lifecycle status
     * @param stagedUpdates map of pluginId to staged version
     * @param userInitiated whether this snapshot completed an explicit manual check
     * @param reminder whether a new staged-update canvas hint should be shown
     */
    record Snapshot(Status status, Map<String, String> stagedUpdates, boolean userInitiated, boolean reminder) {
        public Snapshot {
            status = Objects.requireNonNull(status, "status");
            stagedUpdates = Map.copyOf(Objects.requireNonNull(stagedUpdates, "stagedUpdates"));
            if (reminder && status != Status.UPDATES_STAGED) {
                throw new IllegalArgumentException("reminder requires UPDATES_STAGED");
            }
        }

        /** Returns an idle snapshot with no staged updates. */
        public static Snapshot idle() {
            return new Snapshot(Status.IDLE, Map.of(), false, false);
        }
    }

    /** Returns whether a live plugin auto-update implementation is wired. */
    boolean autoUpdateAvailable();

    /** Returns the current immutable plugin auto-update snapshot. */
    Snapshot pluginUpdateSnapshot();

    /** Returns whether automatic official-plugin updates are enabled in preferences. */
    boolean automaticPluginUpdatesEnabled();

    /** Persists the automatic official-plugin updates preference and applies it immediately. */
    CoreUpdateService.PreferenceSaveResult saveAutomaticPluginUpdatesEnabled(boolean enabled);

    /** Starts the delayed background auto-update schedule. */
    void startPluginAutoUpdater();

    /** Starts or joins an explicit user-requested check for official plugin updates. */
    CompletionStage<Snapshot> checkForPluginUpdates();

    /** Subscribes a listener to plugin auto-update snapshot changes. */
    Registration subscribePluginUpdates(Consumer<Snapshot> listener);

    @Override
    default void close() {}

    /** Returns an always-unavailable control implementation for headless or stub tests. */
    static PluginAutoUpdateControl unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Always-unavailable sentinel implementation. */
    final class Unavailable implements PluginAutoUpdateControl {
        private static final Unavailable INSTANCE = new Unavailable();
        private static final Snapshot SNAPSHOT = new Snapshot(Status.UNAVAILABLE, Map.of(), false, false);

        private Unavailable() {}

        @Override
        public boolean autoUpdateAvailable() {
            return false;
        }

        @Override
        public Snapshot pluginUpdateSnapshot() {
            return SNAPSHOT;
        }

        @Override
        public boolean automaticPluginUpdatesEnabled() {
            return true;
        }

        @Override
        public CoreUpdateService.PreferenceSaveResult saveAutomaticPluginUpdatesEnabled(final boolean enabled) {
            return CoreUpdateService.PreferenceSaveResult.failed("Automatic plugin updates are unavailable.");
        }

        @Override
        public void startPluginAutoUpdater() {}

        @Override
        public CompletionStage<Snapshot> checkForPluginUpdates() {
            return CompletableFuture.completedFuture(SNAPSHOT);
        }

        @Override
        public Registration subscribePluginUpdates(final Consumer<Snapshot> listener) {
            Objects.requireNonNull(listener, "listener");
            return () -> {};
        }
    }
}
