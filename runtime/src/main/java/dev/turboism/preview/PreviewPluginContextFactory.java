package dev.turboism.preview;

import dev.turboism.adapter.host.RuntimeHostAdapterAccess;
import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.core.plugin.context.CorePluginContext;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.failure.RuntimeFailureCollector;
import dev.turboism.hostread.SharedAsyncHostReadLane;
import dev.turboism.i18n.RuntimePluginLocalization;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Assembles the preview-only PluginContext services owned by one plugin scope. */
final class PreviewPluginContextFactory {

    private final RuntimeHostAdapterAccess hostAccess;
    private final PreviewPluginServicesFactory servicesFactory;

    PreviewPluginContextFactory(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final SharedAsyncHostReadLane hostReadLane,
        final PreviewLog log,
        final RuntimeFailureCollector failureCollector
    ) {
        this.hostAccess = Objects.requireNonNull(hostAccess, "hostAccess");
        this.servicesFactory = new PreviewPluginServicesFactory(
            home, scheduler, hostAccess, hostReadLane, log, failureCollector
        );
    }

    PluginContextBundle create(
        final PluginDescriptor descriptor,
        final ClassLoader pluginClassLoader,
        final DisposableScope scope
    ) throws IOException {
        final PreviewPluginServices services = servicesFactory.create(
            Objects.requireNonNull(descriptor, "descriptor"),
            Objects.requireNonNull(pluginClassLoader, "pluginClassLoader"),
            Objects.requireNonNull(scope, "scope")
        );
        final CorePluginContext context = new CorePluginContext(
            services.dependencies().withConfig(services.typedConfig()), hostAccess,
            services.localization(), services.taskScheduler(), services.pluginStorage(),
            services.userFiles(), services.hostReads()
        );
        return new PluginContextBundle(context, services.localization(), services.cleanupEvidence());
    }
}

record PluginContextBundle(
    CorePluginContext context,
    RuntimePluginLocalization localization,
    CleanupEvidenceCollector cleanupEvidence
) {
    PluginContextBundle {
        context = Objects.requireNonNull(context, "context");
        localization = Objects.requireNonNull(localization, "localization");
        cleanupEvidence = Objects.requireNonNull(cleanupEvidence, "cleanupEvidence");
    }
}
