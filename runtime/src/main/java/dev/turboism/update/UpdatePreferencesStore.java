package dev.turboism.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.plugin.core.CoreUpdateService;

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
        this(home, ignored -> { });
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
        synchronized (lock) {
            try {
                final ObjectNode root = UpdateFileSupport.readObject(home, path);
                if (root == null) return new CoreUpdateService.Preferences(true);
                final JsonNode schema = root.get("schemaVersion");
                final JsonNode enabled = root.get("automaticChecksEnabled");
                if (schema == null || !schema.isIntegralNumber() || !schema.canConvertToInt()
                    || schema.intValue() != SCHEMA_VERSION || enabled == null || !enabled.isBoolean()) {
                    throw new IOException("preference schema invalid");
                }
                return new CoreUpdateService.Preferences(enabled.booleanValue());
            } catch (RuntimeException | IOException failure) {
                UpdateFileSupport.report(diagnostic, "UPDATE_PREFERENCES_CORRUPT");
                return new CoreUpdateService.Preferences(true);
            }
        }
    }

/** Atomically saves the independent automatic-check preference. */
    public CoreUpdateService.PreferenceSaveResult save(final CoreUpdateService.Preferences preferences) {
        Objects.requireNonNull(preferences, "preferences");
        synchronized (lock) {
            final ObjectNode root = UpdateFileSupport.JSON.createObjectNode();
            root.put("schemaVersion", SCHEMA_VERSION);
            root.put("automaticChecksEnabled", preferences.automaticChecksEnabled());
            try {
                UpdateFileSupport.writeAtomic(home, path, root);
                return CoreUpdateService.PreferenceSaveResult.success();
            } catch (RuntimeException | IOException failure) {
                UpdateFileSupport.report(diagnostic, "UPDATE_PREFERENCES_WRITE_FAILED");
                return CoreUpdateService.PreferenceSaveResult.failed(
                    "Automatic update preference could not be saved; check the Turboism home permissions."
                );
            }
        }
    }
}
