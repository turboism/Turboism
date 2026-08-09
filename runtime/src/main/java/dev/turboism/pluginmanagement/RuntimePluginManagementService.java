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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Runtime-owned desired-state manager; package mutation is applied only before next discovery. */
public final class RuntimePluginManagementService implements CorePluginManagement {
    @FunctionalInterface
    public interface MetadataLocaleProvider {
        Locale get();
    }
    private final Path pluginsDirectory;
    private final Supplier<Optional<Path>> synchronousPackageChooser;
    private final PackageChooser packageChooser;
    private final Supplier<List<PluginInfo>> runtimePlugins;
    private final MetadataLocaleProvider metadataLocale;
    private final RuntimeConfigRepository config;
    private final PendingPluginOperations pending;
    private final ExecutorService installExecutor;
    private final AtomicBoolean installPending = new AtomicBoolean();
    private final AtomicBoolean active = new AtomicBoolean(true);

    public RuntimePluginManagementService(final Path home, final Supplier<List<PluginInfo>> runtimePlugins) {
        this(home, RuntimePluginManagementService::choosePluginPackage, new SwingPackageChooser(), runtimePlugins,
            () -> Locale.getDefault(Locale.Category.DISPLAY));
    }

    public RuntimePluginManagementService(
        final Path home,
        final Supplier<Optional<Path>> packageChooser,
        final Supplier<List<PluginInfo>> runtimePlugins
    ) {
        this(home, packageChooser, completion -> completion.accept(packageChooser.get()), runtimePlugins,
            () -> Locale.getDefault(Locale.Category.DISPLAY));
    }

    public static RuntimePluginManagementService withMetadataLocale(
        final Path home,
        final Supplier<List<PluginInfo>> runtimePlugins,
        final MetadataLocaleProvider metadataLocale
    ) {
        return new RuntimePluginManagementService(
            home, RuntimePluginManagementService::choosePluginPackage,
            new SwingPackageChooser(), runtimePlugins, metadataLocale
        );
    }

    RuntimePluginManagementService(
        final Path home,
        final PackageChooser packageChooser,
        final Supplier<List<PluginInfo>> runtimePlugins
    ) {
        this(home, Optional::empty, packageChooser, runtimePlugins, () -> Locale.getDefault(Locale.Category.DISPLAY));
    }

    private RuntimePluginManagementService(
        final Path home,
        final Supplier<Optional<Path>> synchronousPackageChooser,
        final PackageChooser packageChooser,
        final Supplier<List<PluginInfo>> runtimePlugins,
        final MetadataLocaleProvider metadataLocale
    ) {
        final Path normalized = home.toAbsolutePath().normalize();
        pluginsDirectory = normalized.resolve("plugins");
        this.synchronousPackageChooser = java.util.Objects.requireNonNull(
            synchronousPackageChooser, "synchronousPackageChooser"
        );
        this.packageChooser = java.util.Objects.requireNonNull(packageChooser, "packageChooser");
        this.runtimePlugins = java.util.Objects.requireNonNull(runtimePlugins, "runtimePlugins");
        this.metadataLocale = java.util.Objects.requireNonNull(metadataLocale, "metadataLocale");
        config = new RuntimeConfigRepository(normalized, ignored -> { });
        pending = new PendingPluginOperations(normalized);
        installExecutor = Executors.newSingleThreadExecutor(new InstallThreadFactory());
    }

    @Override
    public synchronized List<PluginInfo> plugins() {
        final Map<String, PluginInfo> catalog = new HashMap<>();
        runtimePlugins.get().forEach(plugin -> catalog.put(plugin.id(), plugin));
        installed().forEach(plugin -> catalog.putIfAbsent(plugin.id(), plugin));
        final PluginInfo loadedCore = catalog.get(CORE_PLUGIN_ID);
        catalog.put(CORE_PLUGIN_ID, loadedCore == null ? corePlugin() : withCoreFlag(loadedCore));
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
        return stage(synchronousPackageChooser.get());
    }

    @Override
    public void requestInstall(final Consumer<OperationResult> completion) {
        final Consumer<OperationResult> requested = java.util.Objects.requireNonNull(completion, "completion");
        if (!active.get() || !installPending.compareAndSet(false, true)) {
            requested.accept(OperationResult.rejected(
                "PLUGIN_INSTALL_BUSY", "Another plugin installation is already in progress."
            ));
            return;
        }
        final AtomicBoolean settled = new AtomicBoolean();
        final Consumer<OperationResult> terminal = result -> {
            if (settled.compareAndSet(false, true)) requested.accept(result);
        };
        try {
            packageChooser.choose(selection -> selected(selection, terminal));
        } catch (RuntimeException failure) {
            installPending.set(false);
            terminal.accept(cancelledInstall());
        }
    }

    private void selected(
        final Optional<Path> selection,
        final Consumer<OperationResult> completion
    ) {
        if (!active.get()) {
            installPending.set(false);
            completion.accept(cancelledInstall());
            return;
        }
        final Optional<Path> selected = selection == null ? Optional.empty() : selection;
        if (selected.isEmpty()) {
            installPending.set(false);
            completion.accept(cancelledInstall());
            return;
        }
        try {
            installExecutor.execute(() -> {
                OperationResult result;
                try {
                    result = stage(selected);
                } catch (RuntimeException failure) {
                    result = OperationResult.rejected(
                        "PLUGIN_INSTALL_FAILED", "Plugin package was rejected safely."
                    );
                } finally {
                    installPending.set(false);
                }
                if (active.get()) completion.accept(result);
            });
        } catch (RejectedExecutionException closed) {
            installPending.set(false);
        }
    }

    private synchronized OperationResult stage(final Optional<Path> selected) {
        if (selected.isEmpty()) {
            return OperationResult.rejected(
                "PLUGIN_INSTALL_CANCELLED", "Plugin installation was cancelled."
            );
        }
        final PendingPluginOperations.StagedInstall result = pending.stage(selected.orElseThrow());
        return result.accepted()
            ? OperationResult.accepted(result.code(), "Plugin installation is pending; restart Cubism to apply it.")
            : OperationResult.rejected(result.code(), "Plugin package was rejected safely.");
    }

    private static OperationResult cancelledInstall() {
        return OperationResult.rejected(
            "PLUGIN_INSTALL_CANCELLED", "Plugin installation was cancelled."
        );
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

    @Override
    public void close() {
        if (!active.compareAndSet(true, false)) return;
        packageChooser.close();
        installExecutor.shutdownNow();
        try {
            if (!installExecutor.awaitTermination(5L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Plugin installation did not quiesce before core scope close");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while closing plugin installation", interrupted);
        } finally {
            installPending.set(false);
        }
    }

    private List<PluginInfo> installed() {
        if (!Files.isDirectory(pluginsDirectory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        final List<PluginInfo> result = new ArrayList<>();
        try (var files = Files.list(pluginsDirectory)) {
            for (Path path : files.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                .filter(candidate -> candidate.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                final Optional<PluginArchiveMetadata> metadata = PluginArchiveMetadata.read(
                    path, metadataLocale.get(), ignored -> { }
                );
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

    private static PluginInfo withCoreFlag(final PluginInfo plugin) {
        return new PluginInfo(
            plugin.id(), plugin.name(), plugin.version(), plugin.description(),
            plugin.effectiveState(), plugin.desiredState(), true, plugin.pendingOperation()
        );
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


    interface PackageChooser extends AutoCloseable {
        void choose(Consumer<Optional<Path>> completion);
        @Override default void close() { }
    }

    static final class SwingPackageChooser implements PackageChooser {
        private final Object lifecycleLock = new Object();
        private final Supplier<JFileChooser> chooserFactory;
        private final Runnable afterInitialActiveCheck;
        private final Runnable afterCloseDeactivated;
        private boolean active = true;
        private Consumer<Optional<Path>> pending;
        private JFileChooser visible;

        SwingPackageChooser() {
            this(JFileChooser::new, () -> { }, () -> { });
        }

        SwingPackageChooser(
            final Supplier<JFileChooser> chooserFactory,
            final Runnable afterInitialActiveCheck,
            final Runnable afterCloseDeactivated
        ) {
            this.chooserFactory = java.util.Objects.requireNonNull(chooserFactory, "chooserFactory");
            this.afterInitialActiveCheck = java.util.Objects.requireNonNull(
                afterInitialActiveCheck, "afterInitialActiveCheck"
            );
            this.afterCloseDeactivated = java.util.Objects.requireNonNull(
                afterCloseDeactivated, "afterCloseDeactivated"
            );
        }

        @Override
        public void choose(final Consumer<Optional<Path>> completion) {
            final Consumer<Optional<Path>> requested = java.util.Objects.requireNonNull(completion, "completion");
            synchronized (lifecycleLock) {
                if (!active) {
                    requested.accept(Optional.empty());
                    return;
                }
                pending = requested;
            }
            final Runnable choose = () -> {
                synchronized (lifecycleLock) {
                    if (!active) return;
                }
                afterInitialActiveCheck.run();
                final JFileChooser chooser;
                synchronized (lifecycleLock) {
                    if (!active) return;
                    chooser = chooserFactory.get();
                    visible = chooser;
                }
                chooser.setDialogTitle("Install Turboism plugin");
                chooser.setFileFilter(new FileNameExtensionFilter(
                    "Turboism plugin package (*.tplugin)", "tplugin"
                ));
                final Optional<Path> selected = chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION
                    ? Optional.of(chooser.getSelectedFile().toPath())
                    : Optional.empty();
                final Consumer<Optional<Path>> terminal;
                synchronized (lifecycleLock) {
                    visible = null;
                    terminal = pending;
                    pending = null;
                }
                if (terminal != null) terminal.accept(selected);
            };
            if (SwingUtilities.isEventDispatchThread()) choose.run();
            else SwingUtilities.invokeLater(choose);
        }

        @Override
        public void close() {
            final Consumer<Optional<Path>> terminal;
            synchronized (lifecycleLock) {
                if (!active) return;
                active = false;
                terminal = pending;
                pending = null;
            }
            afterCloseDeactivated.run();
            runOnEdtAndWait(() -> {
                final JFileChooser chooser;
                synchronized (lifecycleLock) {
                    chooser = visible;
                }
                if (chooser != null) chooser.cancelSelection();
            });
            if (terminal != null) terminal.accept(Optional.empty());
        }

        private static void runOnEdtAndWait(final Runnable action) {
            if (SwingUtilities.isEventDispatchThread()) {
                action.run();
                return;
            }
            try {
                SwingUtilities.invokeAndWait(action);
            } catch (Exception failure) {
                throw new IllegalStateException("Could not close plugin package chooser on the EDT", failure);
            }
        }
    }

    private static final class InstallThreadFactory implements ThreadFactory {
        @Override public Thread newThread(final Runnable work) {
            final Thread thread = new Thread(work, "turboism-plugin-install");
            thread.setDaemon(true);
            return thread;
        }
    }
}
