package dev.turboism.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.sdk.runtime.RuntimeSettings;
import dev.turboism.sdk.runtime.RuntimeSettingsService;
import dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Canonical global runtime settings backed only by {@code <turboism.home>/config.json}. */
public final class RuntimeSettingsFileService implements RuntimeSettingsService {
    private final RuntimeConfigRepository config;
    private final RuntimeDockMaintenanceCoordinator dockMaintenance;
    private final Consumer<String> logLevelChanged;
    private final IntConsumer logStorageLimitChanged;
    /** Immutable fallback baseline; initialized once by the first successful read/save. */
    private RuntimeSettings baseline;
    /** Last successful read/save; preserved across rejected/invalid reloads. */
    private RuntimeSettings active;

    public RuntimeSettingsFileService(
        final Path turboismHome,
        final RuntimeDockMaintenanceCoordinator dockMaintenance
    ) {
        this(turboismHome, dockMaintenance, ignored -> {});
    }

    public RuntimeSettingsFileService(
        final Path turboismHome,
        final RuntimeDockMaintenanceCoordinator dockMaintenance,
        final Consumer<String> logLevelChanged
    ) {
        this(new RuntimeConfigRepository(turboismHome, ignored -> { }), dockMaintenance, logLevelChanged);
    }

    public RuntimeSettingsFileService(
        final Path turboismHome,
        final RuntimeDockMaintenanceCoordinator dockMaintenance,
        final Consumer<String> logLevelChanged,
        final IntConsumer logStorageLimitChanged
    ) {
        this(
            new RuntimeConfigRepository(turboismHome, ignored -> { }),
            dockMaintenance,
            logLevelChanged,
            logStorageLimitChanged
        );
    }

    public RuntimeSettingsFileService(
        final RuntimeConfigRepository config,
        final RuntimeDockMaintenanceCoordinator dockMaintenance
    ) {
        this(config, dockMaintenance, ignored -> {}, ignored -> {});
    }

    RuntimeSettingsFileService(
        final RuntimeConfigRepository config,
        final RuntimeDockMaintenanceCoordinator dockMaintenance,
        final Consumer<String> logLevelChanged
    ) {
        this(config, dockMaintenance, logLevelChanged, ignored -> {});
    }

    RuntimeSettingsFileService(
        final RuntimeConfigRepository config,
        final RuntimeDockMaintenanceCoordinator dockMaintenance,
        final Consumer<String> logLevelChanged,
        final IntConsumer logStorageLimitChanged
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.dockMaintenance = Objects.requireNonNull(dockMaintenance, "dockMaintenance");
        this.logLevelChanged = Objects.requireNonNull(logLevelChanged, "logLevelChanged");
        this.logStorageLimitChanged = Objects.requireNonNull(
            logStorageLimitChanged,
            "logStorageLimitChanged"
        );
    }

    @Override
    public RuntimeSettings read() {
        final RuntimeSettings loaded;
        try {
            loaded = readFromConfig();
        } catch (RuntimeException failure) {
            // A rejected/invalid reload after an active value exists preserves that active
            // value and changes neither active nor baseline.
            if (active != null) return active;
            throw failure;
        }
        initializeBaseline(loaded);
        active = loaded;
        return active;
    }

    private RuntimeSettings readFromConfig() {
        final JsonNode root = config.read();
        final JsonNode startup = root.path("hooks").path("startup");
        return new RuntimeSettings(
            root.path("safeMode").asBoolean(false),
            root.path("logLevel").asText("INFO"),
            root.path("maxLogStorageMiB").asInt(RuntimeSettings.DEFAULT_MAX_LOG_STORAGE_MIB),
            startup.path("skipUpdateCheck").asBoolean(false),
            startup.path("skipSplash").asBoolean(false),
            startup.path("skipInformation").asBoolean(false),
            startup.path("separateExportSaveDirectory").asBoolean(false),
            root.path("locale").asText(RuntimeSettings.DEFAULT_LOCALE)
        );
    }

    @Override
    public RuntimeSettings save(final RuntimeSettings settings) {
        final RuntimeSettings requested = Objects.requireNonNull(settings, "settings");
        // A rejected save throws and changes neither active nor baseline.
        config.update(root -> {
            root.put("safeMode", requested.safeMode());
            root.put("logLevel", requested.logLevel());
            root.put("maxLogStorageMiB", requested.maxLogStorageMiB());
            root.put("locale", requested.locale());
            final ObjectNode startup = root.withObject("hooks").withObject("startup");
            startup.put("skipUpdateCheck", requested.skipStartupUpdateCheck());
            startup.put("skipSplash", requested.skipStartupSplash());
            startup.put("skipInformation", requested.skipStartupInformation());
            startup.put("separateExportSaveDirectory", requested.separateExportSaveDirectory());
            return root;
        });
        initializeBaseline(requested);
        active = requested;
        logLevelChanged.accept(requested.logLevel());
        logStorageLimitChanged.accept(requested.maxLogStorageMiB());
        return requested;
    }

    /** The first successful read/save initializes the immutable fallback baseline once. */
    private void initializeBaseline(final RuntimeSettings value) {
        if (baseline == null) baseline = value;
    }

    /** Minimal package-private test seam: the immutable fallback baseline. */
    RuntimeSettings baselineForTest() {
        return baseline;
    }

    /** Minimal package-private test seam: the last successful active settings. */
    RuntimeSettings activeForTest() {
        return active;
    }


    @Override
    public DockCleanupResult cleanEmptyDocks() {
        dockMaintenance.cleanEmptyDocks();
        return new DockCleanupResult("Empty dock cleanup completed.");
    }
}
