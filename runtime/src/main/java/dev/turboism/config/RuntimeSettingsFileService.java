package dev.turboism.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.sdk.runtime.RuntimeSettings;
import dev.turboism.sdk.runtime.RuntimeSettingsService;
import dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator;

import java.nio.file.Path;
import java.util.Objects;

/** Canonical global runtime settings backed only by {@code <turboism.home>/config.json}. */
public final class RuntimeSettingsFileService implements RuntimeSettingsService {
    private final RuntimeConfigRepository config;
    private final RuntimeDockMaintenanceCoordinator dockMaintenance;

    public RuntimeSettingsFileService(
        final Path turboismHome,
        final RuntimeDockMaintenanceCoordinator dockMaintenance
    ) {
        this(new RuntimeConfigRepository(turboismHome, ignored -> { }), dockMaintenance);
    }

    public RuntimeSettingsFileService(
        final RuntimeConfigRepository config,
        final RuntimeDockMaintenanceCoordinator dockMaintenance
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.dockMaintenance = Objects.requireNonNull(dockMaintenance, "dockMaintenance");
    }

    @Override
    public RuntimeSettings read() {
        final JsonNode root = config.read();
        final JsonNode startup = root.path("hooks").path("startup");
        return new RuntimeSettings(
            root.path("safeMode").asBoolean(false),
            root.path("logLevel").asText("INFO"),
            startup.path("skipUpdateCheck").asBoolean(false),
            startup.path("skipSplash").asBoolean(false),
            startup.path("skipInformation").asBoolean(false)
        );
    }

    @Override
    public RuntimeSettings save(final RuntimeSettings settings) {
        final RuntimeSettings requested = Objects.requireNonNull(settings, "settings");
        config.update(root -> {
            root.put("safeMode", requested.safeMode());
            root.put("logLevel", requested.logLevel());
            final ObjectNode startup = root.withObject("hooks").withObject("startup");
            startup.put("skipUpdateCheck", requested.skipStartupUpdateCheck());
            startup.put("skipSplash", requested.skipStartupSplash());
            startup.put("skipInformation", requested.skipStartupInformation());
            return root;
        });
        return requested;
    }

    @Override
    public DockCleanupResult cleanEmptyDocks() {
        dockMaintenance.cleanEmptyDocks();
        return new DockCleanupResult("Empty dock cleanup completed.");
    }
}
