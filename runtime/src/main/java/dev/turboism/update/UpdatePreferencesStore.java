package dev.turboism.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.internal.core.CoreUpdateService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/** Atomic persistence for the independent Turboism automatic-update preference. */
public final class UpdatePreferencesStore {
    public static final int SCHEMA_VERSION = 1;
    public static final String FILE_NAME = "update-preferences.json";

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

    /** Reads the preference, defaulting safely to enabled when invalid or absent. */
    public CoreUpdateService.Preferences read() {
        return new CoreUpdateService.Preferences(readAll().automaticChecksEnabled());
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

    /** Atomically saves the independent automatic-check preference while preserving the plugin auto-update preference. */
    public CoreUpdateService.PreferenceSaveResult save(final CoreUpdateService.Preferences preferences) {
        Objects.requireNonNull(preferences, "preferences");
        synchronized (lock) {
            final StoredPreferences current = readAllLocked(false);
            return saveAllLocked(new StoredPreferences(
                    preferences.automaticChecksEnabled(), current.automaticPluginUpdatesEnabled()));
        }
    }

    /** Atomically saves the official-plugin automatic update preference while preserving the framework preference. */
    public CoreUpdateService.PreferenceSaveResult saveAutomaticPluginUpdatesEnabled(final boolean enabled) {
        synchronized (lock) {
            final StoredPreferences current = readAllLocked(false);
            return saveAllLocked(new StoredPreferences(current.automaticChecksEnabled(), enabled));
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
            final JsonNode schema = root.get("schemaVersion");
            final JsonNode enabled = root.get("automaticChecksEnabled");
            final JsonNode pluginUpdates = root.get("automaticPluginUpdatesEnabled");
            if (schema == null
                    || !schema.isIntegralNumber()
                    || !schema.canConvertToInt()
                    || schema.intValue() != SCHEMA_VERSION
                    || enabled == null
                    || !enabled.isBoolean()
                    || (pluginUpdates != null && !pluginUpdates.isBoolean())) {
                throw new IOException("preference schema invalid");
            }
            final boolean pluginUpdatesEnabled = pluginUpdates == null || pluginUpdates.booleanValue();
            return new StoredPreferences(enabled.booleanValue(), pluginUpdatesEnabled);
        } catch (RuntimeException | IOException failure) {
            if (reportCorruption) {
                UpdateFileSupport.report(diagnostic, "UPDATE_PREFERENCES_CORRUPT");
            }
            return StoredPreferences.DEFAULT;
        }
    }

    private CoreUpdateService.PreferenceSaveResult saveAllLocked(final StoredPreferences preferences) {
        final ObjectNode root = UpdateFileSupport.JSON.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("automaticChecksEnabled", preferences.automaticChecksEnabled());
        root.put("automaticPluginUpdatesEnabled", preferences.automaticPluginUpdatesEnabled());
        try {
            UpdateFileSupport.writeAtomic(home, path, root);
            return CoreUpdateService.PreferenceSaveResult.success();
        } catch (RuntimeException | IOException failure) {
            UpdateFileSupport.report(diagnostic, "UPDATE_PREFERENCES_WRITE_FAILED");
            return CoreUpdateService.PreferenceSaveResult.failed(
                    "Automatic update preference could not be saved; check the Turboism home permissions.");
        }
    }

    /** Persisted update preferences for both framework update checks and official-plugin updates. */
    public record StoredPreferences(boolean automaticChecksEnabled, boolean automaticPluginUpdatesEnabled) {
        /** Default preferences: both automatic framework checks and automatic plugin updates enabled. */
        public static final StoredPreferences DEFAULT = new StoredPreferences(true, true);
    }
}
