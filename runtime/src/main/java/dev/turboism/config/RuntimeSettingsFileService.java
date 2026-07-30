package dev.turboism.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.core.schema.runtimeconfig.RuntimeConfigValidator;
import dev.turboism.sdk.runtime.RuntimeSettings;
import dev.turboism.sdk.runtime.RuntimeSettingsService;
import dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Canonical global runtime settings backed only by {@code <turboism.home>/config.json}. */
public final class RuntimeSettingsFileService implements RuntimeSettingsService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path configPath;
    private final RuntimeDockMaintenanceCoordinator dockMaintenance;

    public RuntimeSettingsFileService(
        final Path turboismHome,
        final RuntimeDockMaintenanceCoordinator dockMaintenance
    ) {
        final Path home = Objects.requireNonNull(turboismHome, "turboismHome").toAbsolutePath().normalize();
        this.configPath = home.resolve("config.json").normalize();
        this.dockMaintenance = Objects.requireNonNull(dockMaintenance, "dockMaintenance");
    }

    @Override
    public synchronized RuntimeSettings read() {
        if (!Files.isRegularFile(configPath)) {
            return defaults();
        }
        try {
            final JsonNode root = JSON.readTree(Files.readAllBytes(configPath));
            if (!new RuntimeConfigValidator().validate(root, configPath.toString()).isEmpty()) {
                throw new IllegalStateException("canonical runtime config is invalid");
            }
            final JsonNode startup = root.path("hooks").path("startup");
            return new RuntimeSettings(
                root.path("safeMode").asBoolean(false),
                root.path("logLevel").asText("INFO"),
                startup.path("skipUpdateCheck").asBoolean(false),
                startup.path("skipSplash").asBoolean(false),
                startup.path("skipInformation").asBoolean(false)
            );
        } catch (IOException failure) {
            throw new IllegalStateException("canonical runtime config is unreadable", failure);
        }
    }

    @Override
    public synchronized RuntimeSettings save(final RuntimeSettings settings) {
        final RuntimeSettings requested = Objects.requireNonNull(settings, "settings");
        final ObjectNode root = existingOrDefault();
        root.put("safeMode", requested.safeMode());
        root.put("logLevel", requested.logLevel());
        final ObjectNode hooks = root.withObject("hooks");
        final ObjectNode startup = hooks.withObject("startup");
        startup.put("skipUpdateCheck", requested.skipStartupUpdateCheck());
        startup.put("skipSplash", requested.skipStartupSplash());
        startup.put("skipInformation", requested.skipStartupInformation());
        if (!new RuntimeConfigValidator().validate(root, configPath.toString()).isEmpty()) {
            throw new IllegalStateException("refusing to write invalid canonical runtime config");
        }
        final Path temporary = configPath.resolveSibling(configPath.getFileName() + ".tmp");
        try {
            Files.createDirectories(configPath.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);
            try {
                Files.move(temporary, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                failure.addSuppressed(ignored);
            }
            throw new IllegalStateException("canonical runtime config could not be saved", failure);
        }
        return requested;
    }

    @Override
    public DockCleanupResult cleanEmptyDocks() {
        dockMaintenance.cleanEmptyDocks();
        return new DockCleanupResult("Empty dock cleanup completed.");
    }

    private ObjectNode existingOrDefault() {
        if (!Files.isRegularFile(configPath)) {
            final ObjectNode root = JSON.createObjectNode();
            root.put("format", "turboism.runtime.config");
            root.put("schemaVersion", 1);
            root.put("worktreeId", "turboism-runtime");
            root.putArray("pluginDirs").add("plugins");
            root.put("logLevel", "INFO");
            root.put("safeMode", false);
            root.putObject("hooks").putArray("disabledIds");
            ((ObjectNode) root.get("hooks")).putArray("denylistedClasses");
            ((ObjectNode) root.get("hooks")).putObject("startup");
            return root;
        }
        try {
            final JsonNode root = JSON.readTree(Files.readAllBytes(configPath));
            if (!(root instanceof ObjectNode object)) {
                throw new IllegalStateException("canonical runtime config root must be an object");
            }
            return object.deepCopy();
        } catch (IOException failure) {
            throw new IllegalStateException("canonical runtime config is unreadable", failure);
        }
    }

    private static RuntimeSettings defaults() {
        return new RuntimeSettings(false, "INFO", false, false, false);
    }
}
