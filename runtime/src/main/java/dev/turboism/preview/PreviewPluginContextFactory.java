package dev.turboism.preview;

import dev.turboism.adapter.host.RuntimeHostAdapterAccess;
import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.core.plugin.context.CorePluginContext;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.failure.RuntimeFailureCollector;
import dev.turboism.graal.GraalHostConfiguration;
import dev.turboism.graal.GraalHostManager;
import dev.turboism.hostread.SharedAsyncHostReadLane;
import dev.turboism.i18n.CubismHostLocale;
import dev.turboism.i18n.RuntimePluginLocalization;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Assembles the preview-only PluginContext services owned by one plugin scope. */
final class PreviewPluginContextFactory implements AutoCloseable {

    private final RuntimeHostAdapterAccess hostAccess;
    private final Path home;
    private final PreviewPluginServicesFactory servicesFactory;
    private final PreviewLog log;
    private final GraalHostConfiguration graalConfiguration;
    private final GraalHostManager graalHost;
    private final dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory;

    PreviewPluginContextFactory(
            final Path home,
            final RuntimeScheduler scheduler,
            final RuntimeHostAdapterAccess hostAccess,
            final SharedAsyncHostReadLane hostReadLane,
            final PreviewLog log,
            final RuntimeFailureCollector failureCollector,
            final dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory) {
        this(
                home,
                scheduler,
                hostAccess,
                hostReadLane,
                log,
                failureCollector,
                fileChooserHistory,
                CubismHostLocale.resolve());
    }

    PreviewPluginContextFactory(
            final Path home,
            final RuntimeScheduler scheduler,
            final RuntimeHostAdapterAccess hostAccess,
            final SharedAsyncHostReadLane hostReadLane,
            final PreviewLog log,
            final RuntimeFailureCollector failureCollector,
            final dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory,
            final Locale effectiveLocale) {
        this(
                home,
                scheduler,
                hostAccess,
                hostReadLane,
                log,
                failureCollector,
                fileChooserHistory,
                hostAccess.parameterLifecycle(),
                hostAccess.partLifecycle(),
                hostAccess.editorObjectLifecycle(),
                effectiveLocale);
    }

    PreviewPluginContextFactory(
            final Path home,
            final RuntimeScheduler scheduler,
            final RuntimeHostAdapterAccess hostAccess,
            final SharedAsyncHostReadLane hostReadLane,
            final PreviewLog log,
            final RuntimeFailureCollector failureCollector,
            final dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory,
            final dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator parameterLifecycle,
            final dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator partLifecycle,
            final dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator editorObjectLifecycle,
            final Locale effectiveLocale) {
        this.hostAccess = Objects.requireNonNull(hostAccess, "hostAccess");
        this.home = Objects.requireNonNull(home, "home");
        this.log = Objects.requireNonNull(log, "log");
        this.fileChooserHistory = Objects.requireNonNull(fileChooserHistory, "fileChooserHistory");
        this.graalConfiguration = GraalHostConfiguration.resolve(home);
        this.graalHost = new GraalHostManager(graalConfiguration, diagnostic -> log.warn("graal", diagnostic));
        this.servicesFactory = new PreviewPluginServicesFactory(
                home,
                scheduler,
                hostAccess,
                hostReadLane,
                log,
                failureCollector,
                Objects.requireNonNull(parameterLifecycle, "parameterLifecycle"),
                Objects.requireNonNull(partLifecycle, "partLifecycle"),
                Objects.requireNonNull(editorObjectLifecycle, "editorObjectLifecycle"),
                Objects.requireNonNull(effectiveLocale, "effectiveLocale"));
    }

    dev.turboism.core.event.RuntimeEventBroker eventBroker() {
        return servicesFactory.eventBroker();
    }

    void preflightEventContracts(final PluginDescriptor descriptor) {
        servicesFactory.preflightEventContracts(descriptor);
    }

    void preflightEventContracts(
            final PluginDescriptor descriptor,
            final dev.turboism.core.event.PublicEventContractCatalog.ContractLease lease) {
        servicesFactory.preflightEventContracts(descriptor, lease);
    }

    dev.turboism.core.event.PublicEventContractCatalog.ContractLease acquireEventContracts(
            final PluginDescriptor descriptor, final java.nio.file.Path pluginJar) {
        return servicesFactory.acquireEventContracts(descriptor, pluginJar);
    }

    Object hostAccessIdentity() {
        return hostAccess;
    }

    GraalHostConfiguration graalConfiguration() {
        return graalConfiguration;
    }

    PluginContextBundle create(
            final PluginDescriptor descriptor, final ClassLoader pluginClassLoader, final DisposableScope scope)
            throws IOException {
        final PluginDescriptor requestedDescriptor = Objects.requireNonNull(descriptor, "descriptor");
        final ClassLoader requestedClassLoader = Objects.requireNonNull(pluginClassLoader, "pluginClassLoader");
        final DisposableScope requestedScope = Objects.requireNonNull(scope, "scope");
        final dev.turboism.core.event.RuntimeEventBroker.Owner eventOwner =
                servicesFactory.admitEventOwner(requestedDescriptor);
        try {
            requestedScope.register(hostAccess
                    .editorUiPluginResources()
                    .register(requestedDescriptor.id(), eventOwner.key().generation(), requestedClassLoader));
            if (eventOwner.key().generation() != 0L) {
                requestedScope.register(
                        hostAccess.editorUiPluginResources().register(requestedDescriptor.id(), requestedClassLoader));
            }
            requestedScope.register(() -> {
                eventOwner.beginClosing();
                if (!eventOwner.awaitQuiescence(java.time.Duration.ofSeconds(5))) {
                    throw new IllegalStateException(
                            "Plugin event owner did not quiesce during scope disposal: " + eventOwner.key());
                }
                eventOwner.close();
            });
            final PreviewPluginServices services =
                    servicesFactory.create(requestedDescriptor, requestedClassLoader, requestedScope, eventOwner);
            final dev.turboism.core.plugin.context.PluginContextEnvironment environment =
                    dev.turboism.core.plugin.context.PluginContextEnvironment.builder(hostAccess)
                            .localization(services.localization())
                            .taskScheduler(services.taskScheduler())
                            .pluginStorage(services.pluginStorage())
                            .userFiles(services.userFiles())
                            .hostReads(services.hostReads())
                            .fileChooserHistory(fileChooserHistory)
                            .exportSettings(services.exportSettings())
                            .mcpConnectionService(services.mcpConnections())
                            .build();
            final CorePluginContext context =
                    new CorePluginContext(services.dependencies().withConfig(services.typedConfig()), environment);
            if (requestedDescriptor
                    .capabilities()
                    .contains(dev.turboism.adapter.cubism.mesh.RuntimeMeshToolRegistry.REQUIRED_CAPABILITY)) {
                final var meshTools = new dev.turboism.adapter.cubism.mesh.RuntimeMeshToolRegistry(
                        requestedDescriptor.id(),
                        eventOwner.key().generation(),
                        dev.turboism.permissions.PermissionChecker.from(
                                new dev.turboism.permissions.CubismPermissionGate(
                                        requestedDescriptor.id(),
                                                services.dependencies().permissions(),
                                        services.dependencies().cubismAuditSink(),
                                                services.dependencies().clock())),
                        true,
                        hostAccess.meshToolCoordinator(),
                        hostAccess.editorUiContributions());
                try {
                    context.installMeshTools(meshTools);
                } catch (RuntimeException | Error failure) {
                    meshTools.close();
                    throw failure;
                }
            }
            context.installScriptService(new dev.turboism.script.RuntimeScriptService(
                    home,
                    context,
                    requestedScope,
                    graalHost,
                    diagnostic -> log.warn(requestedDescriptor.id(), diagnostic)));
            return new PluginContextBundle(context, services.localization(), services.cleanupEvidence(), eventOwner);
        } catch (IOException | RuntimeException | Error failure) {
            eventOwner.beginClosing();
            if (eventOwner.awaitQuiescence(java.time.Duration.ZERO)) {
                eventOwner.close();
            }
            throw failure;
        }
    }

    private dev.turboism.cleanup.RetryableCleanup cleanup;

    @Override
    public synchronized void close() {
        if (cleanup == null) {
            cleanup = new dev.turboism.cleanup.RetryableCleanup(
                    "Plugin context service cleanup failed", servicesFactory::close, graalHost::close);
        }
        cleanup.close();
    }

    /** Forwards the host-level export-settings authority to the per-plugin services factory. */
    void bindExportSettingsAuthority(final dev.turboism.exportsettings.RuntimeExportSettingsAuthority authority) {
        servicesFactory.bindExportSettingsAuthority(authority);
    }
}

record PluginContextBundle(
        CorePluginContext context,
        RuntimePluginLocalization localization,
        CleanupEvidenceCollector cleanupEvidence,
        dev.turboism.core.event.RuntimeEventBroker.Owner eventOwner) {
    PluginContextBundle {
        context = Objects.requireNonNull(context, "context");
        localization = Objects.requireNonNull(localization, "localization");
        cleanupEvidence = Objects.requireNonNull(cleanupEvidence, "cleanupEvidence");
        eventOwner = Objects.requireNonNull(eventOwner, "eventOwner");
    }
}
