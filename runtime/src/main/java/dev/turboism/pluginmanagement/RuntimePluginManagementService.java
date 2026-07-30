package dev.turboism.pluginmanagement;

import dev.turboism.config.RuntimeConfigRepository;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.plugin.core.CorePluginManagement;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Runtime-owned desired-state manager; package mutation is applied only before next discovery. */
public final class RuntimePluginManagementService implements CorePluginManagement {
    private final Path pluginsDirectory;
    private final Supplier<Optional<Path>> packageChooser;
    private final Supplier<List<PluginInfo>> runtimePlugins;
    private final RuntimeConfigRepository config;
    private final PendingPluginOperations pending;

    public RuntimePluginManagementService(final Path home, final Supplier<List<PluginInfo>> runtimePlugins) {
        this(home, RuntimePluginManagementService::choosePluginPackage, runtimePlugins);
    }

    public RuntimePluginManagementService(
        final Path home,
        final Supplier<Optional<Path>> packageChooser,
        final Supplier<List<PluginInfo>> runtimePlugins
    ) {
        final Path normalized = home.toAbsolutePath().normalize();
        pluginsDirectory = normalized.resolve("plugins");
        this.packageChooser = packageChooser;
        this.runtimePlugins = runtimePlugins;
        config = new RuntimeConfigRepository(normalized, ignored -> { });
        pending = new PendingPluginOperations(normalized);
    }

    @Override
    public synchronized List<PluginInfo> plugins() {
        final Map<String, PluginInfo> catalog = new HashMap<>();
        runtimePlugins.get().forEach(plugin -> catalog.put(plugin.id(), plugin));
        installed().forEach(plugin -> catalog.putIfAbsent(plugin.id(), plugin));
        catalog.put(CORE_PLUGIN_ID, corePlugin());
        final Set<String> disabled = config.disabledPlugins();
        for (PendingPluginOperations.Operation operation : pending.operations()) {
            final PluginInfo existing = catalog.get(operation.pluginId());
            final String pendingLabel = operation.type().equals("INSTALL") ? "INSTALL" : "UNINSTALL";
            if (existing != null) catalog.put(existing.id(), withDesired(existing, disabled, pendingLabel));
            if (existing == null && operation.type().equals("INSTALL")) {
                catalog.put(operation.pluginId(), new PluginInfo(
                    operation.pluginId(), operation.pluginId(), operation.version(), "Pending plugin installation",
                    "NOT_INSTALLED", "ENABLED", false, Optional.of("INSTALL")
                ));
            }
        }
        return catalog.values().stream()
            .map(plugin -> withDesired(plugin, disabled, plugin.pendingOperation().orElse(null)))
            .sorted(Comparator.comparing(PluginInfo::core).reversed().thenComparing(PluginInfo::id))
            .toList();
    }

    @Override
    public synchronized OperationResult install() {
        final Optional<Path> selected = packageChooser.get();
        if (selected.isEmpty()) return OperationResult.rejected("PLUGIN_INSTALL_CANCELLED", "Plugin installation was cancelled.");
        final PendingPluginOperations.StagedInstall result = pending.stage(selected.orElseThrow());
        return result.accepted()
            ? OperationResult.accepted(result.code(), "Plugin installation is pending; restart Cubism to apply it.")
            : OperationResult.rejected(result.code(), "Plugin package was rejected safely.");
    }

    @Override
    public synchronized OperationResult uninstall(final String pluginId) {
        if (CORE_PLUGIN_ID.equals(pluginId)) {
            return OperationResult.rejected("PLUGIN_CORE_PROTECTED", "The Turboism core plugin cannot be uninstalled.");
        }
        if (installedArchive(pluginId).isEmpty()) {
            return OperationResult.rejected("PLUGIN_NOT_INSTALLED", "Plugin is not installed.");
        }
        return pending.stageUninstall(pluginId)
            ? OperationResult.accepted("PLUGIN_UNINSTALL_PENDING", "Plugin uninstall is pending; restart Cubism to apply it.")
            : OperationResult.rejected("PLUGIN_PENDING_WRITE_FAILED", "Plugin uninstall could not be staged.");
    }

    @Override
    public synchronized OperationResult setEnabled(final String pluginId, final boolean enabled) {
        if (CORE_PLUGIN_ID.equals(pluginId)) {
            return OperationResult.rejected("PLUGIN_CORE_PROTECTED", "The Turboism core plugin cannot be disabled.");
        }
        if (installedArchive(pluginId).isEmpty()) {
            return OperationResult.rejected("PLUGIN_NOT_INSTALLED", "Plugin is not installed.");
        }
        try {
            config.setPluginEnabled(pluginId, enabled);
            return OperationResult.accepted(
                enabled ? "PLUGIN_ENABLE_PENDING" : "PLUGIN_DISABLE_PENDING",
                (enabled ? "Enable" : "Disable") + " is pending; restart Cubism to apply it."
            );
        } catch (RuntimeException failure) {
            return OperationResult.rejected("PLUGIN_CONFIG_REJECTED", "Plugin state was not changed.");
        }
    }

    public static PendingPluginOperations.ApplyResult applyPending(final Path home) {
        return new PendingPluginOperations(home).apply();
    }

    private List<PluginInfo> installed() {
        if (!Files.isDirectory(pluginsDirectory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        final List<PluginInfo> result = new ArrayList<>();
        try (var files = Files.list(pluginsDirectory)) {
            for (Path path : files.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                .filter(candidate -> candidate.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                final Optional<PluginArchiveMetadata> metadata = PluginArchiveMetadata.read(path);
                if (metadata.isPresent()) {
                    final var value = metadata.orElseThrow();
                    if (CORE_PLUGIN_ID.equals(value.id())) continue;
                    result.add(new PluginInfo(value.id(), value.name(), value.version(), value.description(),
                        PluginLifecycleState.DISCOVERED.name(), "ENABLED", false, Optional.empty()));
                }
            }
        } catch (Exception ignored) { return List.of(); }
        return result;
    }

    private Optional<Path> installedArchive(final String pluginId) {
        return installedPaths(pluginId).stream().findFirst();
    }

    private List<Path> installedPaths(final String pluginId) {
        if (!Files.isDirectory(pluginsDirectory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var files = Files.list(pluginsDirectory)) {
            return files.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                .filter(candidate -> candidate.getFileName().toString().endsWith(".jar"))
                .filter(candidate -> PluginArchiveMetadata.read(candidate).map(PluginArchiveMetadata::id)
                    .filter(pluginId::equals).isPresent())
                .sorted().toList();
        } catch (Exception ignored) { return List.of(); }
    }

    private static PluginInfo withDesired(
        final PluginInfo plugin,
        final Set<String> disabled,
        final String pendingOperation
    ) {
        final String desired = plugin.core() || !disabled.contains(plugin.id()) ? "ENABLED" : "DISABLED";
        return new PluginInfo(plugin.id(), plugin.name(), plugin.version(), plugin.description(),
            plugin.effectiveState(), desired, plugin.core(), Optional.ofNullable(pendingOperation));
    }

    private static PluginInfo corePlugin() {
        return new PluginInfo(CORE_PLUGIN_ID, "Turboism Core", "0.1.0",
            "Built-in menu, toolbar, settings, tab, and plugin management.",
            PluginLifecycleState.ENABLED.name(), "ENABLED", true, Optional.empty());
    }

    private static Optional<Path> choosePluginPackage() {
        @SuppressWarnings("unchecked") final Optional<Path>[] selected = new Optional[]{Optional.empty()};
        final Runnable choose = () -> {
            final JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Install Turboism plugin");
            chooser.setFileFilter(new FileNameExtensionFilter("Turboism plugin package (*.tplugin)", "tplugin"));
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                selected[0] = Optional.of(chooser.getSelectedFile().toPath());
            }
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) choose.run(); else SwingUtilities.invokeAndWait(choose);
            return selected[0];
        } catch (Exception failure) { return Optional.empty(); }
    }
}
