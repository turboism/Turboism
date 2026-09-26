package dev.turboism.preview;

import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.service.read.M12ReadSnapshotSource;
import dev.turboism.adapter.host.RuntimeHostAdapterAccess;
import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.config.RuntimeTypedPluginConfigRegistry;
import dev.turboism.core.event.PublicEventContractCatalog;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.exportsettings.RuntimeExportSettingsAuthority;
import dev.turboism.exportsettings.RuntimeExportSettingsContributionRegistry;
import dev.turboism.core.plugin.context.CorePluginContext;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.failure.RuntimeFailureCollector;
import dev.turboism.failure.RuntimeFailureSink;
import dev.turboism.hostread.ProjectWorkspaceHostReadSource;
import dev.turboism.hostread.RuntimeAsyncHostReadService;
import dev.turboism.hostread.SharedAsyncHostReadLane;
import dev.turboism.i18n.CubismHostLocale;
import dev.turboism.i18n.RuntimePluginLocalization;
import dev.turboism.home.PluginHomePaths;
import dev.turboism.home.TurboismHomeLayout;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.performance.RuntimePerformanceEventPublisher;
import dev.turboism.performance.RuntimePerformanceProbeService;
import dev.turboism.runtime.log.CubismLogServiceHost;
import dev.turboism.storage.RuntimePluginStorage;
import dev.turboism.task.RuntimePluginTaskScheduler;
import dev.turboism.ui.RuntimeUiScheduler;
import dev.turboism.ui.UiHostStateSource;
import dev.turboism.userfile.RuntimeUserFileAccessService;
import dev.turboism.userfile.UserFileGrantSource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Creates the shared service graph for one preview plugin scope. */
final class PreviewPluginServicesFactory implements AutoCloseable {

    private final Path home;
    private final RuntimeScheduler scheduler;
    private final RuntimeEventBroker eventBroker;
    private final PublicEventContractCatalog eventContracts;
    private final RuntimeHostAdapterAccess hostAccess;
    private final SharedAsyncHostReadLane hostReadLane;
    private final PreviewLog log;
    private final RuntimeFailureCollector failureCollector;
    private final Locale effectiveLocale;
    private final RuntimePerformanceProbeService performanceProbe;
    private final RuntimePerformanceEventPublisher performanceEvents;
    private final dev.turboism.adapter.cubism.HostSnapshotSource sessionSnapshotSource;
    private final dev.turboism.adapter.cubism.SelectionObservationPublisher selectionObserver;
    private dev.turboism.cleanup.RetryableCleanup cleanup;
    private final dev.turboism.mcp.McpConnectionRegistry mcpConnections =
        new dev.turboism.mcp.McpConnectionRegistry();
    /**
     * Host-level export-settings policy, bound by the runtime before any plugin is loaded.
     *
     * <p>A plugin's contribution registry is only reachable from the native dialog through this
     * authority. When it is absent the registry stays plugin-private, which is the correct
     * fail-closed state: no hook is installed and the host keeps its native dialog.</p>
     */
    private volatile RuntimeExportSettingsAuthority exportSettingsAuthority;

    PreviewPluginServicesFactory(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final SharedAsyncHostReadLane hostReadLane,
        final PreviewLog log,
        final RuntimeFailureCollector failureCollector
    ) {
        this(
            home, scheduler, hostAccess, hostReadLane, log, failureCollector,
            hostAccess.parameterLifecycle(), hostAccess.partLifecycle(),
            hostAccess.editorObjectLifecycle(), CubismHostLocale.resolve()
        );
    }

    PreviewPluginServicesFactory(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final SharedAsyncHostReadLane hostReadLane,
        final PreviewLog log,
        final RuntimeFailureCollector failureCollector,
        final Locale effectiveLocale
    ) {
        this(
            home, scheduler, hostAccess, hostReadLane, log, failureCollector,
            hostAccess.parameterLifecycle(), hostAccess.partLifecycle(),
            hostAccess.editorObjectLifecycle(), effectiveLocale
        );
    }

    PreviewPluginServicesFactory(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final SharedAsyncHostReadLane hostReadLane,
        final PreviewLog log,
        final RuntimeFailureCollector failureCollector,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator partLifecycle,
        final dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final Locale effectiveLocale
    ) {
        this.home = home;
        this.scheduler = scheduler;
        this.eventContracts = new PublicEventContractCatalog(eventContractCacheDir(home));
        this.eventBroker = new RuntimeEventBroker(
            scheduler,
            64,
            diagnostic -> log.warn(
                diagnostic.owner().pluginId(),
                "Event delivery " + diagnostic.code()
                    + (diagnostic.eventType().isBlank()
                        ? ""
                        : " event=" + diagnostic.eventType())
            ),
            failure -> failureCollector.record(
                dev.turboism.failure.RuntimeFailureDomain.EVENT,
                new dev.turboism.failure.RuntimeFailure(
                    "EVENT_SUBSCRIBER_FAILED",
                    "ERROR",
                    "event-delivery",
                    failure.owner().pluginId(),
                    failure.operationId(),
                    null,
                    "Event subscriber failed: event=" + failure.eventType()
                        + " exception=" + failure.exceptionType()
                        + " advised=" + failure.advised(),
                    null,
                    1L
                )
            ),
            eventContracts
        );
        Objects.requireNonNull(parameterLifecycle, "parameterLifecycle")
            .attachEventBroker(eventBroker);
        Objects.requireNonNull(partLifecycle, "partLifecycle")
            .attachEventBroker(eventBroker);
        Objects.requireNonNull(editorObjectLifecycle, "editorObjectLifecycle")
            .attachEventBroker(eventBroker);
        Objects.requireNonNull(hostAccess, "hostAccess").appearanceCoordinator()
            .attachEventBroker(eventBroker);
        if (hostAccess.sceneTable() instanceof dev.turboism.ui.table.RuntimeSceneTableService sceneTable) {
            sceneTable.attachEventBroker(eventBroker);
        }
        if (hostAccess.cubismLog() instanceof CubismLogServiceHost cubismLog) {
            cubismLog.attachEventBroker(eventBroker, scheduler);
        }
        this.performanceProbe = new RuntimePerformanceProbeService(
            "runtime.performance",
            dev.turboism.permissions.PermissionChecker.allowAll(),
            Clock.systemUTC()
        );
        eventBroker.observationBaseline(
            dev.turboism.sdk.performance.PerformanceProbeService.class
        ).set(performanceProbe);
        this.performanceEvents = new RuntimePerformanceEventPublisher(
            performanceProbe,
            eventBroker,
            scheduler
        );
        this.hostAccess = hostAccess;
        this.hostReadLane = hostReadLane;
        this.log = log;
        this.failureCollector = failureCollector;
        this.effectiveLocale = Objects.requireNonNull(effectiveLocale, "effectiveLocale");
        // One session snapshot source shared by every plugin query facade and the
        // selection observer: a single invalidation-token domain lets the shared
        // observation baseline order query results against sampler results.
        this.sessionSnapshotSource = dev.turboism.adapter.host.HostSessionSnapshotSource
            .forSession(hostAccess.adapters().projectWorkspace());
        // Session-scoped selection observation on the bounded host-read lane.
        this.selectionObserver = new dev.turboism.adapter.cubism.SelectionObservationPublisher(
            sessionSnapshotSource,
            hostReadLane,
            scheduler,
            eventBroker,
            eventBroker.observationBaseline(
                dev.turboism.adapter.cubism.SelectionObservation.class
            )
        );
        this.selectionObserver.signalDemand();
    }

    private static Path eventContractCacheDir(final Path home) {
        try {
            return TurboismHomeLayout.create(home)
                .runtimeCacheDir()
                .resolve("event-contracts");
        } catch (IOException failure) {
            throw new IllegalStateException(
                "cannot resolve the runtime cache directory under " + home,
                failure
            );
        }
    }

    RuntimeEventBroker.Owner admitEventOwner(final PluginDescriptor descriptor) {
        return eventBroker.admit(descriptor);
    }

    void preflightEventContracts(final PluginDescriptor descriptor) {
        eventBroker.preflight(descriptor);
    }

    void preflightEventContracts(
        final PluginDescriptor descriptor,
        final PublicEventContractCatalog.ContractLease lease
    ) {
        eventBroker.preflight(descriptor, lease);
    }

    PublicEventContractCatalog.ContractLease acquireEventContracts(
        final PluginDescriptor descriptor,
        final Path pluginJar
    ) {
        return eventContracts.acquire(descriptor, pluginJar);
    }

    PublicEventContractCatalog eventContracts() {
        return eventContracts;
    }

    RuntimeEventBroker.Owner admitEventOwner(final String pluginId) {
        return eventBroker.admit(pluginId);
    }

    RuntimeEventBroker eventBroker() {
        return eventBroker;
    }

    @Override
    public synchronized void close() {
        if (cleanup == null) {
            cleanup = new dev.turboism.cleanup.RetryableCleanup(
                "Shared plugin service cleanup failed",
                selectionObserver::close,
                performanceEvents::close,
                performanceProbe::close,
                mcpConnections::close,
                () -> eventBroker.observationBaseline(
                    dev.turboism.sdk.performance.PerformanceProbeService.class
                ).compareAndSet(performanceProbe, null),
                // Retire contract admission; bindings still leased by retained generations stay
                // usable until their last release, which remains legal after close().
                eventContracts::close
            );
        }
        cleanup.close();
    }

    PreviewPluginServices create(
        final PluginDescriptor descriptor,
        final ClassLoader classLoader,
        final DisposableScope scope,
        final RuntimeEventBroker.Owner eventOwner
    ) throws IOException {
        final RuntimeUiScheduler uiScheduler = new RuntimeUiScheduler(scheduler, descriptor.id());
        scope.register(uiScheduler);
        final PluginHomePaths paths = TurboismHomeLayout.create(home).plugin(descriptor.id());
        final CleanupEvidenceCollector evidence = new CleanupEvidenceCollector();
        final RuntimePluginTaskScheduler tasks = tasks(descriptor, scope, evidence);
        final RuntimeExportSettingsContributionRegistry exportSettings =
            new RuntimeExportSettingsContributionRegistry(
                descriptor.id(), Objects.requireNonNull(eventOwner, "eventOwner").key().generation()
            );
        scope.register(exportSettings);
        final Set<String> permissions = permissionIds(descriptor);
        final CorePluginContext.Dependencies dependencies = dependencies(
            descriptor, paths, uiScheduler, scope, eventOwner, classLoader
        );
        final RuntimePluginLocalization pluginLocalization = localization(descriptor, classLoader);
        final PreviewPluginServices services = new PreviewPluginServices(
            dependencies, pluginLocalization, tasks,
            storage(descriptor, paths, permissions, tasks, scope, evidence),
            typedConfig(
                dependencies, descriptor, pluginDisplayName(descriptor.name(), pluginLocalization),
                paths, permissions, tasks, scope, evidence
            ),
            userFiles(descriptor, permissions, tasks, scope, evidence),
            hostReads(descriptor, permissions, tasks, scope),
            new dev.turboism.mcp.RuntimeMcpConnectionService(
                descriptor.id(),
                (permissionId, operation) -> {
                    if (!permissions.contains(permissionId)) {
                        throw new dev.turboism.sdk.permission.CubismPermissionException(
                            "Missing required permission " + permissionId + " for " + operation
                        );
                    }
                },
                mcpConnections
            ),
            exportSettings,
            evidence
        );
        // The registry binding is removed before the guard below runs, so a dialog that is still
        // open when the plugin unloads is marked stale instead of reading a closing registry.
        bindExportSettings(descriptor, eventOwner, exportSettings, pluginLocalization, scope);
        // Register last: DisposableScope closes in reverse order, so this guard runs before the
        // registry and every other plugin resource. A failed guard makes shutdown retain the
        // classloader instead of claiming a clean unload while a callback is still running.
        scope.register(exportSettings.scopeCloseGuard());
        return services;
    }

    /**
     * Publishes one plugin's contribution registry to the host-level authority.
     *
     * <p>The binding is registered before the close guard, so teardown runs guard → unbind →
     * registry close: an in-flight native callback is drained first, then any dialog that is still
     * open is marked stale, and only then does the registry stop accepting reads.</p>
     *
     * <p>With no bound authority the registry stays plugin-private, which is the fail-closed state:
     * no host hook is installed and the native dialog is untouched.</p>
     */
    private void bindExportSettings(
        final PluginDescriptor descriptor,
        final RuntimeEventBroker.Owner eventOwner,
        final RuntimeExportSettingsContributionRegistry exportSettings,
        final RuntimePluginLocalization pluginLocalization,
        final DisposableScope scope
    ) {
        final RuntimeExportSettingsAuthority authority = exportSettingsAuthority;
        if (authority == null) {
            return;
        }
        final Registration binding = authority.register(
            descriptor.id(),
            eventOwner.key().generation(),
            exportSettings,
            key -> {
                final String text = pluginLocalization.text(key);
                return text == null || text.isBlank() ? key : text;
            }
        );
        scope.register(binding::close);
    }

    /**
     * Binds the host-level export-settings authority.
     *
     * <p>Must be called before any plugin is created; plugins loaded earlier stay plugin-private
     * and are never reachable from the native dialog.</p>
     *
     * @param authority host-level export-settings policy
     * @throws IllegalStateException if an authority is already bound
     */
    void bindExportSettingsAuthority(final RuntimeExportSettingsAuthority authority) {
        final RuntimeExportSettingsAuthority requested =
            Objects.requireNonNull(authority, "authority");
        if (exportSettingsAuthority != null) {
            throw new IllegalStateException("export settings authority is already bound");
        }
        exportSettingsAuthority = requested;
    }

    private CorePluginContext.Dependencies dependencies(
        final PluginDescriptor descriptor,
        final PluginHomePaths paths,
        final RuntimeUiScheduler uiScheduler,
        final DisposableScope scope,
        final RuntimeEventBroker.Owner eventOwner,
        final ClassLoader classLoader
    ) {
        return new CorePluginContext.Dependencies(
            descriptor, new PreviewPluginLogger(log, descriptor.id()), paths, uiScheduler, scheduler,
            new PreviewDiagnosticReport(), scope,
            sessionSnapshotSource,
            M12ReadSnapshotSource.EMPTY, new PreviewUiHostStateSource(paths),
            event -> log.debug(descriptor.id(), event.toString()), Clock.systemUTC(), failureCollector,
            eventBroker, Objects.requireNonNull(eventOwner, "eventOwner").key(),
            Objects.requireNonNull(classLoader, "classLoader")
        );
    }

    private RuntimePluginLocalization localization(
        final PluginDescriptor descriptor,
        final ClassLoader classLoader
    ) {
        return RuntimePluginLocalization.createResolved(
            descriptor.id(), classLoader, descriptor.i18n(), effectiveLocale,
            diagnostic -> log.warn(descriptor.id(), diagnostic.code() + ": " + diagnostic.message())
        );
    }

    private RuntimePluginTaskScheduler tasks(
        final PluginDescriptor descriptor,
        final DisposableScope scope,
        final CleanupEvidenceCollector evidence
    ) {
        return new RuntimePluginTaskScheduler(
            descriptor.id(), scheduler, scope, evidence, failureCollector
        );
    }

    private static Set<String> permissionIds(final PluginDescriptor descriptor) {
        return descriptor.permissions().stream()
            .map(permission -> permission.id())
            .collect(Collectors.toUnmodifiableSet());
    }

    private RuntimePluginStorage storage(
        final PluginDescriptor descriptor,
        final PluginHomePaths paths,
        final Set<String> permissions,
        final RuntimePluginTaskScheduler tasks,
        final DisposableScope scope,
        final CleanupEvidenceCollector evidence
    ) throws IOException {
        return new RuntimePluginStorage(
            descriptor.id(), storageRoots(paths), permissions, tasks, scope, evidence, failureCollector
        );
    }

    private static Map<StorageRoot, Path> storageRoots(final PluginHomePaths paths) {
        return Map.of(
            StorageRoot.DATA, paths.dataDir(), StorageRoot.STATE, paths.stateDir(),
            StorageRoot.CACHE, paths.cacheDir()
        );
    }

    static String pluginDisplayName(
        final String fallback,
        final PluginLocalization localization
    ) {
        final String defaultName = Objects.requireNonNull(fallback, "fallback");
        final PluginLocalization resolved = Objects.requireNonNull(localization, "localization");
        if (!resolved.contains("plugin.name")) return defaultName;
        final String localized = resolved.text("plugin.name");
        return localized == null || localized.isBlank() ? defaultName : localized;
    }

    private RuntimeTypedPluginConfigRegistry typedConfig(
        final CorePluginContext.Dependencies dependencies,
        final PluginDescriptor descriptor,
        final String pluginName,
        final PluginHomePaths paths,
        final Set<String> permissions,
        final RuntimePluginTaskScheduler tasks,
        final DisposableScope scope,
        final CleanupEvidenceCollector evidence
    ) {
        return new RuntimeTypedPluginConfigRegistry(
            dependencies.config(), descriptor.id(), paths.configDir(), permissions,
            tasks, scope, evidence, failureCollector, pluginName,
            dev.turboism.ui.settings.ProcessSettingsContributions.forHost(hostAccess)
        );
    }
    static UserFileGrantSource newUserFileGrantSource(
        final String pluginId,
        final RuntimeFailureSink failureSink,
        final CleanupEvidenceCollector cleanupEvidence
    ) {
        // The preview runtime has no host window that could carry a Swing chooser,
        // so user-file requests must report RUNTIME_UNAVAILABLE deterministically —
        // on headful machines too (an always-available Swing source would either
        // open a real chooser with nowhere to parent it or hang the plugin).
        return UserFileGrantSource.unavailable();
    }

    private RuntimeUserFileAccessService userFiles(
        final PluginDescriptor descriptor,
        final Set<String> permissions,
        final RuntimePluginTaskScheduler tasks,
        final DisposableScope scope,
        final CleanupEvidenceCollector evidence
    ) {
        return new RuntimeUserFileAccessService(
            descriptor.id(),
            permissions,
            newUserFileGrantSource(descriptor.id(), failureCollector, evidence),
            tasks,
            scope,
            evidence,
            failureCollector
        );
    }

    private RuntimeAsyncHostReadService hostReads(
        final PluginDescriptor descriptor,
        final Set<String> permissions,
        final RuntimePluginTaskScheduler tasks,
        final DisposableScope scope
    ) {
        return new RuntimeAsyncHostReadService(
            descriptor.id(), permissions,
            ProjectWorkspaceHostReadSource.from(hostAccess.adapters().projectWorkspace()),
            hostReadLane, tasks, scope
        );
    }
}

record PreviewPluginServices(
    CorePluginContext.Dependencies dependencies,
    RuntimePluginLocalization localization,
    RuntimePluginTaskScheduler taskScheduler,
    RuntimePluginStorage pluginStorage,
    RuntimeTypedPluginConfigRegistry typedConfig,
    RuntimeUserFileAccessService userFiles,
    RuntimeAsyncHostReadService hostReads,
    dev.turboism.sdk.mcp.McpConnectionService mcpConnections,
    RuntimeExportSettingsContributionRegistry exportSettings,
    CleanupEvidenceCollector cleanupEvidence
) {
}

/**
 * Preview UiHostStateSource that can open the plugin storage directory in the
 * host file manager and detects the active color mode from the UIManager.
 */
final class PreviewUiHostStateSource implements UiHostStateSource {

    private final PluginHomePaths paths;

    PreviewUiHostStateSource(final PluginHomePaths paths) {
        this.paths = paths;
    }

    @Override
    public void openDirectory(final dev.turboism.sdk.storage.StoragePath directory) {
        final java.nio.file.Path base = paths.dataDir();
        final java.nio.file.Path resolved = base.resolve(directory.relativePath())
            .normalize();
        if (!resolved.startsWith(base)) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(resolved);
        } catch (java.io.IOException ignored) {
            return;
        }
        if (java.awt.Desktop.isDesktopSupported()) {
            try {
                java.awt.Desktop.getDesktop().open(resolved.toFile());
            } catch (java.io.IOException ignored) {
                // Opening a directory is best-effort on the validation host.
            }
        }
    }
}
