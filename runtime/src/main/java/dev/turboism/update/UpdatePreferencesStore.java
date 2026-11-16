package dev.turboism.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.core.FrameworkBuildInfo;
import dev.turboism.internal.core.CoreUpdateService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Atomic persistence for the independent Turboism automatic-update preference. */
public final class UpdatePreferencesStore {
    public static final int SCHEMA_VERSION = 1;
    public static final String FILE_NAME = "update-preferences.json";
    private static final String FIELD_SCHEMA_VERSION = "schemaVersion";
    private static final String FIELD_AUTOMATIC_CHECKS_ENABLED = "automaticChecksEnabled";
    private static final String FIELD_AUTOMATIC_PLUGIN_UPDATES_ENABLED = "automaticPluginUpdatesEnabled";
    private static final String FIELD_UPDATE_CHANNEL = "updateChannel";
    private static final String DIAGNOSTIC_CORRUPT = "UPDATE_PREFERENCES_CORRUPT";
    private static final String DIAGNOSTIC_WRITE_FAILED = "UPDATE_PREFERENCES_WRITE_FAILED";

    private final Path home;
    private final Path path;
    private final Object lock;
    private final Consumer<String> diagnostic;

    public UpdatePreferencesStore(final Path home) {
        this(home, ignored -> {});
    }

    public UpdatePreferencesStore(final Path home, final Consumer<String> diagnostic) {
        this.home = UpdateFileSupport.normalizeHome(home);
        this.path = this.home.resolve(FILE_NAME).normalize();
        if (!path.startsWith(this.home)) throw new IllegalArgumentException("preference path escaped home");
        this.lock = UpdateFileSupport.lockFor(path);
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    /** Returns the isolated preference file path. */
    public Path path() {
        return path;
    }

    /** Reads the preference, defaulting safely to enabled and stable when invalid or absent. */
    public CoreUpdateService.Preferences read() {
        return read(CoreUpdateService.CHANNEL_STABLE);
    }

    /**
     * Reads preferences and resolves the active update channel using {@code fallbackChannel} when unset.
     *
     * @param fallbackChannel installed channel wire name used when no preference is stored
     * @return resolved immutable preferences
     */
    public CoreUpdateService.Preferences read(final String fallbackChannel) {
        final StoredPreferences stored = readAll();
        final String channel = stored.updateChannel().orElseGet(() -> defaultSelectableChannel(fallbackChannel));
        return new CoreUpdateService.Preferences(stored.automaticChecksEnabled(), channel);
    }

    /**
     * Resolves the effective selectable update channel for an installed build channel.
     *
     * @param installedChannel installed framework channel
     * @return {@link CoreUpdateService#CHANNEL_STABLE} or {@link CoreUpdateService#CHANNEL_BETA}
     */
    public String effectiveChannel(final FrameworkBuildInfo.Channel installedChannel) {
        Objects.requireNonNull(installedChannel, "installedChannel");
        return readAll()
                .updateChannel()
                .orElseGet(() -> defaultSelectableChannel(InstalledBuild.channelFor(installedChannel)));
    }

    /**
     * Resolves the effective framework channel enum for plugin compatibility checks.
     *
     * @param installedChannel installed framework channel
     * @return {@link FrameworkBuildInfo.Channel#STABLE} or {@link FrameworkBuildInfo.Channel#BETA}
     */
    public FrameworkBuildInfo.Channel effectiveFrameworkChannel(final FrameworkBuildInfo.Channel installedChannel) {
        return toFrameworkChannel(effectiveChannel(installedChannel));
    }

    /**
     * Reads the effective framework channel enum directly from {@code home}.
     *
     * @param home Turboism home directory
     * @param installedChannel installed framework channel
     * @return {@link FrameworkBuildInfo.Channel#STABLE} or {@link FrameworkBuildInfo.Channel#BETA}
     */
    public static FrameworkBuildInfo.Channel effectiveFrameworkChannel(
            final Path home, final FrameworkBuildInfo.Channel installedChannel) {
        return new UpdatePreferencesStore(home).effectiveFrameworkChannel(installedChannel);
    }

    /**
     * Maps any installed build channel wire name to a selectable update channel.
     *
     * @param installedChannel installed channel wire name
     * @return {@link CoreUpdateService#CHANNEL_BETA} for beta/nightly builds, otherwise stable
     */
    public static String defaultSelectableChannel(final String installedChannel) {
        return UpdateDiscovery.CHANNEL_BETA.equals(installedChannel)
                        || UpdateDiscovery.CHANNEL_NIGHTLY.equals(installedChannel)
                ? CoreUpdateService.CHANNEL_BETA
                : CoreUpdateService.CHANNEL_STABLE;
    }

    /**
     * Maps a selectable channel wire name to {@link FrameworkBuildInfo.Channel}.
     *
     * @param channel selectable wire channel name
     * @return matching {@link FrameworkBuildInfo.Channel}
     */
    public static FrameworkBuildInfo.Channel toFrameworkChannel(final String channel) {
        if (CoreUpdateService.CHANNEL_BETA.equals(channel)) {
            return FrameworkBuildInfo.Channel.BETA;
        }
        if (CoreUpdateService.CHANNEL_STABLE.equals(channel)) {
            return FrameworkBuildInfo.Channel.STABLE;
        }
        throw new IllegalArgumentException("unsupported selectable update channel: " + channel);
    }

    /** Reads both framework and official-plugin automatic update preferences, defaulting safely to enabled. */
    public StoredPreferences readAll() {
        synchronized (lock) {
            return readAllLocked(true);
        }
    }

    /** Reads whether automatic official-plugin updates are enabled, defaulting safely to {@code true}. */
    public boolean automaticPluginUpdatesEnabled() {
        return readAll().automaticPluginUpdatesEnabled();
    }

    /** Atomically saves framework update preferences while preserving the plugin auto-update preference. */
    public CoreUpdateService.PreferenceSaveResult save(final CoreUpdateService.Preferences preferences) {
        Objects.requireNonNull(preferences, "preferences");
        synchronized (lock) {
            final StoredPreferences current = readAllLocked(false);
            final Optional<String> nextChannel =
                    preferences.selectedChannel().isPresent() ? preferences.selectedChannel() : current.updateChannel();
            return saveAllLocked(new StoredPreferences(
                    preferences.automaticChecksEnabled(), current.automaticPluginUpdatesEnabled(), nextChannel));
        }
    }

    /** Atomically saves the official-plugin automatic update preference while preserving framework preferences. */
    public CoreUpdateService.PreferenceSaveResult saveAutomaticPluginUpdatesEnabled(final boolean enabled) {
        synchronized (lock) {
            final StoredPreferences current = readAllLocked(false);
            return saveAllLocked(new StoredPreferences(
                    current.automaticChecksEnabled(), enabled, current.updateChannel()));
        }
    }

    /** Atomically saves both framework and official-plugin automatic update preferences. */
    public CoreUpdateService.PreferenceSaveResult saveAll(final StoredPreferences preferences) {
        Objects.requireNonNull(preferences, "preferences");
        synchronized (lock) {
            return saveAllLocked(preferences);
        }
    }

    private StoredPreferences readAllLocked(final boolean reportCorruption) {
        try {
            final ObjectNode root = UpdateFileSupport.readObject(home, path);
            if (root == null) return StoredPreferences.DEFAULT;
            return parse(root);
        } catch (RuntimeException | IOException failure) {
            if (reportCorruption) {
                UpdateFileSupport.report(diagnostic, DIAGNOSTIC_CORRUPT);
            }
            return StoredPreferences.DEFAULT;
        }
    }

    /** Decodes one document; throws unless it is the current schema this build understands. */
    private static StoredPreferences parse(final ObjectNode root) throws IOException {
        final JsonNode schema = root.get(FIELD_SCHEMA_VERSION);
        final JsonNode enabled = root.get(FIELD_AUTOMATIC_CHECKS_ENABLED);
        final JsonNode pluginUpdates = root.get(FIELD_AUTOMATIC_PLUGIN_UPDATES_ENABLED);
        final JsonNode channel = root.get(FIELD_UPDATE_CHANNEL);
        if (schema == null
                || !schema.isIntegralNumber()
                || !schema.canConvertToInt()
                || schema.intValue() != SCHEMA_VERSION
                || enabled == null
                || !enabled.isBoolean()
                || (pluginUpdates != null && !pluginUpdates.isBoolean())
                || (channel != null
                        && (!channel.isTextual()
                                || !CoreUpdateService.isSelectableChannel(channel.textValue())))) {
            throw new IOException("preference schema invalid");
        }
        final boolean pluginUpdatesEnabled = pluginUpdates == null || pluginUpdates.booleanValue();
        final Optional<String> updateChannel = channel == null ? Optional.empty() : Optional.of(channel.textValue());
        return new StoredPreferences(enabled.booleanValue(), pluginUpdatesEnabled, updateChannel);
    }

    /**
     * Keeps one image of a preference document this build can no longer read before the new
     * preference replaces it: a future build's file must not be silently returned to defaults.
     */
    private void preserveUnreadableState() {
        try {
            final ObjectNode root = UpdateFileSupport.readObject(home, path);
            if (root == null) return;
            parse(root);
        } catch (IOException | RuntimeException unreadable) {
            UpdateFileSupport.preserveUnreadable(home, path);
        }
    }

    private CoreUpdateService.PreferenceSaveResult saveAllLocked(final StoredPreferences preferences) {
        final ObjectNode root = UpdateFileSupport.JSON.createObjectNode();
        root.put(FIELD_SCHEMA_VERSION, SCHEMA_VERSION);
        root.put(FIELD_AUTOMATIC_CHECKS_ENABLED, preferences.automaticChecksEnabled());
        root.put(FIELD_AUTOMATIC_PLUGIN_UPDATES_ENABLED, preferences.automaticPluginUpdatesEnabled());
        preferences.updateChannel().ifPresent(value -> root.put(FIELD_UPDATE_CHANNEL, value));
        try {
            preserveUnreadableState();
            UpdateFileSupport.writeAtomic(home, path, root);
            return CoreUpdateService.PreferenceSaveResult.success();
        } catch (RuntimeException | IOException failure) {
            UpdateFileSupport.report(diagnostic, DIAGNOSTIC_WRITE_FAILED);
            return CoreUpdateService.PreferenceSaveResult.failed(
                    "Automatic update preference could not be saved; check the Turboism home permissions.");
        }
    }

    /** Persisted update preferences for framework checks, official-plugin updates, and target channel. */
    public record StoredPreferences(
            boolean automaticChecksEnabled, boolean automaticPluginUpdatesEnabled, Optional<String> updateChannel) {
        /** Default preferences: automatic checks and plugin updates enabled, channel unset. */
        public static final StoredPreferences DEFAULT = new StoredPreferences(true, true, Optional.empty());

        /** Validates optional stored channel value. */
        public StoredPreferences {
            updateChannel = Objects.requireNonNull(updateChannel, "updateChannel");
            if (updateChannel.isPresent()
                    && !CoreUpdateService.isSelectableChannel(updateChannel.get())) {
                throw new IllegalArgumentException("unsupported update channel: " + updateChannel.get());
            }
        }

        /** Creates preferences without an explicit channel override. */
        public StoredPreferences(final boolean automaticChecksEnabled, final boolean automaticPluginUpdatesEnabled) {
            this(automaticChecksEnabled, automaticPluginUpdatesEnabled, Optional.empty());
        }
    }
}
