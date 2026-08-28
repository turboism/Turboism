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
import java.util.Objects;
import java.util.Locale;

/** Assembles the preview-only PluginContext services owned by one plugin scope. */
final class PreviewPluginContextFactory implements AutoCloseable {

    private final RuntimeHostAdapterAccess hostAccess;
    private final Path home;
    private final PreviewPluginServicesFactory servicesFactory;
    private final PreviewLog log;
    private final GraalHostManager graalHost;
    private final dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory;

    PreviewPluginContextFactory(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final SharedAsyncHostReadLane hostReadLane,
        final PreviewLog log,
        final RuntimeFailureCollector failureCollector,
        final dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory
    ) {
        this(
            home, scheduler, hostAccess, hostReadLane, log, failureCollector,
            fileChooserHistory, CubismHostLocale.resolve()
        );
    }

    PreviewPluginContextFactory(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final SharedAsyncHostReadLane hostReadLane,
        final PreviewLog log,
        final RuntimeFailureCollector failureCollector,
        final dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory,
        final Locale effectiveLocale
    ) {
        this(
            home, scheduler, hostAccess, hostReadLane, log, failureCollector,
            fileChooserHistory, hostAccess.parameterLifecycle(), hostAccess.partLifecycle(),
            hostAccess.editorObjectLifecycle(), effectiveLocale
        );
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
        final Locale effectiveLocale
    ) {
        this.hostAccess = Objects.requireNonNull(hostAccess, "hostAccess");
        this.home = Objects.requireNonNull(home, "home");
        this.log = Objects.requireNonNull(log, "log");
        this.fileChooserHistory = Objects.requireNonNull(fileChooserHistory, "fileChooserHistory");
        this.graalHost = new GraalHostManager(
            GraalHostConfiguration.resolve(home),
            diagnostic -> log.warn("graal", diagnostic)
        );
        this.servicesFactory = new PreviewPluginServicesFactory(
            home, scheduler, hostAccess, hostReadLane, log, failureCollector,
            Objects.requireNonNull(parameterLifecycle, "parameterLifecycle"),
            Objects.requireNonNull(partLifecycle, "partLifecycle"),
            Objects.requireNonNull(editorObjectLifecycle, "editorObjectLifecycle"),
            Objects.requireNonNull(effectiveLocale, "effectiveLocale")
        );
    }

    dev.turboism.core.event.RuntimeEventBroker eventBroker() {
        return servicesFactory.eventBroker();
    }

    void preflightEventContracts(final PluginDescriptor descriptor) {
        servicesFactory.preflightEventContracts(descriptor);
    }

    Object hostAccessIdentity() {
        return hostAccess;
    }

    PluginContextBundle create(
        final PluginDescriptor descriptor,
        final ClassLoader pluginClassLoader,
        final DisposableScope scope
    ) throws IOException {
        final PluginDescriptor requestedDescriptor = Objects.requireNonNull(descriptor, "descriptor");
        final ClassLoader requestedClassLoader = Objects.requireNonNull(
            pluginClassLoader,
            "pluginClassLoader"
        );
        final DisposableScope requestedScope = Objects.requireNonNull(scope, "scope");
        requestedScope.register(hostAccess.editorUiPluginResources().register(
            requestedDescriptor.id(), requestedClassLoader
        ));
        final dev.turboism.core.event.RuntimeEventBroker.Owner eventOwner =
            servicesFactory.admitEventOwner(requestedDescriptor);
        try {
            requestedScope.register(() -> {
                eventOwner.beginClosing();
                if (!eventOwner.awaitQuiescence(java.time.Duration.ofSeconds(5))) {
                    throw new IllegalStateException(
                        "Plugin event owner did not quiesce during scope disposal: "
                            + eventOwner.key()
                    );
                }
                eventOwner.close();
            });
            final PreviewPluginServices services = servicesFactory.create(
                requestedDescriptor,
                requestedClassLoader,
                requestedScope,
                eventOwner
            );
            final CorePluginContext context = new CorePluginContext(
                services.dependencies().withConfig(services.typedConfig()), hostAccess,
                services.localization(), services.taskScheduler(), services.pluginStorage(),
                services.userFiles(), services.hostReads(), null,
                fileChooserHistory
            );
            context.installScriptService(new dev.turboism.script.RuntimeScriptService(
                home,
                context,
                requestedScope,
                graalHost,
                diagnostic -> log.warn(requestedDescriptor.id(), diagnostic)
            ));
            context.installMcpConnectionService(services.mcpConnections());
            return new PluginContextBundle(
                context, services.localization(), services.cleanupEvidence(), eventOwner
            );
        } catch (IOException | RuntimeException | Error failure) {
            eventOwner.beginClosing();
            if (eventOwner.awaitQuiescence(java.time.Duration.ZERO)) {
                eventOwner.close();
            }
            throw failure;
        }
    }

    @Override
    public void close() {
        try {
            servicesFactory.close();
        } finally {
            graalHost.close();
        }
    }
}

record PluginContextBundle(
    CorePluginContext context,
    RuntimePluginLocalization localization,
    CleanupEvidenceCollector cleanupEvidence,
    dev.turboism.core.event.RuntimeEventBroker.Owner eventOwner
) {
    PluginContextBundle {
        context = Objects.requireNonNull(context, "context");
        localization = Objects.requireNonNull(localization, "localization");
        cleanupEvidence = Objects.requireNonNull(cleanupEvidence, "cleanupEvidence");
        eventOwner = Objects.requireNonNull(eventOwner, "eventOwner");
    }
}
