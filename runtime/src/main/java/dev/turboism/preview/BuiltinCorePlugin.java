package dev.turboism.preview;

import java.net.URLClassLoader;
import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.core.event.GeneratedSubscriberCatalogLoader;
import dev.turboism.core.event.EventSubscriptionPermissionCatalog;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.core.plugin.PluginRuntime;
import dev.turboism.plugin.core.CorePluginServices;
import dev.turboism.plugin.core.MainToolbarPlugin;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Runtime-owned built-in core admission; external discovery cannot construct this path. */
final class BuiltinCorePlugin {
    private static final String DESCRIPTOR = "META-INF/turboism/core-plugin.json";

    private BuiltinCorePlugin() { }

    static LocalPluginRuntime.LoadedPlugin load(
        final PreviewPluginContextFactory contexts,
        final CorePluginServices services,
        final PreviewLog log
    ) throws Exception {
        final ClassLoader loader = MainToolbarPlugin.class.getClassLoader();
        final URLClassLoader resources = resourceLoader(loader);
        DisposableScope scope = null;
        PluginContextBundle context = null;
        TurboismPlugin plugin = null;
        boolean enabled = false;
        try {
            final PluginDescriptor descriptor;
            try (InputStream input = descriptorStream(loader)) {
                if (input == null) throw new IllegalStateException("built-in core descriptor is missing");
                descriptor = new PluginDescriptorParser().parse(input);
            }
            if (!dev.turboism.plugin.core.CorePluginManagement.CORE_PLUGIN_ID.equals(descriptor.id())) {
                throw new IllegalStateException("built-in core descriptor identity mismatch");
            }
            log.info(
                descriptor.id(),
                "Plugin lifecycle: built-in load started version=" + descriptor.version()
            );
            final PluginRuntime runtime = new PluginRuntime(descriptor.id(), descriptor);
            runtime.transitionTo(PluginLifecycleState.RESOLVED);
            runtime.transitionTo(PluginLifecycleState.CLASSLOADER_CREATED);
            scope = new DisposableScope();
            context = contexts.create(descriptor, resources, scope);
            plugin = CorePluginServices.instantiate(services, MainToolbarPlugin::new);
            runtime.setEntrypoints(List.of(plugin));
            final var eventSubscribers = new GeneratedSubscriberCatalogLoader().inspect(
                List.of(plugin),
                loader
            );
            if (!eventSubscribers.isEmpty()) {
                requireEventSubscribePermission(descriptor);
                EventSubscriptionPermissionCatalog.requireDeclared(
                    descriptor,
                    eventSubscribers
                );
            }
            context.eventOwner().registerAnnotated(eventSubscribers);
            runtime.transitionTo(PluginLifecycleState.CONSTRUCTED);
            context.eventOwner().beginInitializing();
            plugin.init(context.context());
            runtime.transitionTo(PluginLifecycleState.LOADED);
            log.info(descriptor.id(), "Plugin lifecycle: initialized entrypoints=1");
            context.eventOwner().beginEnabling();
            log.info(descriptor.id(), "Plugin lifecycle: enable started");
            plugin.enable();
            enabled = true;
            runtime.transitionTo(PluginLifecycleState.ENABLED);
            context.eventOwner().activate();
            log.info(descriptor.id(), "Plugin lifecycle: enable succeeded entrypoints=1");
            final URL source = coreSource(loader);
            final Path artifact = Path.of(source.toURI()).toAbsolutePath().normalize();
            log.info(
                descriptor.id(),
                "Plugin lifecycle: built-in load succeeded version=" + descriptor.version()
            );
            return new LocalPluginRuntime.LoadedPlugin(
                artifact, runtime, List.of(plugin), scope, resources,
                context.localization(), context.cleanupEvidence(), context.eventOwner()
            );
        } catch (Throwable failure) {
            final boolean eventQuiesced = closeEventOwner(context, log);
            cleanupPlugin(plugin, enabled, log);
            final boolean scopeClosed = closeScope(scope, log);
            if (eventQuiesced && scopeClosed) {
                closeResources(resources, log);
            } else {
                log.error(
                    dev.turboism.plugin.core.CorePluginManagement.CORE_PLUGIN_ID,
                    "Built-in core classloader retained because cleanup did not quiesce",
                    new IllegalStateException("Built-in core cleanup is incomplete")
                );
            }
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Built-in core load failed", failure);
        }
    }

    private static void requireEventSubscribePermission(final PluginDescriptor descriptor) {
        final boolean allowed = descriptor.permissions().stream().anyMatch(permission ->
            dev.turboism.sdk.permission.PermissionIds.TURBOISM_EVENT_SUBSCRIBE.equals(
                permission.id()
            )
        );
        if (!allowed) {
            throw new IllegalArgumentException(
                "@SubscribeEvent requires "
                    + dev.turboism.sdk.permission.PermissionIds.TURBOISM_EVENT_SUBSCRIBE
                    + ": " + descriptor.id()
            );
        }
    }

    private static boolean closeEventOwner(
        final PluginContextBundle context,
        final PreviewLog log
    ) {
        if (context == null) {
            return true;
        }
        try {
            context.eventOwner().beginClosing();
            if (!context.eventOwner().awaitQuiescence(Duration.ofSeconds(5))) {
                return false;
            }
            context.eventOwner().close();
            return true;
        } catch (Throwable failure) {
            log.error(
                dev.turboism.plugin.core.CorePluginManagement.CORE_PLUGIN_ID,
                "Built-in core event cleanup failed safely",
                failure
            );
            return false;
        }
    }

    private static void cleanupPlugin(
        final TurboismPlugin plugin,
        final boolean enabled,
        final PreviewLog log
    ) {
        if (plugin == null) {
            return;
        }
        if (enabled) {
            try {
                plugin.disable();
            } catch (Throwable failure) {
                log.error(
                    dev.turboism.plugin.core.CorePluginManagement.CORE_PLUGIN_ID,
                    "Built-in core enable rollback failed safely",
                    failure
                );
            }
        }
        try {
            plugin.shutdown();
        } catch (Throwable failure) {
            log.error(
                dev.turboism.plugin.core.CorePluginManagement.CORE_PLUGIN_ID,
                "Built-in core shutdown rollback failed safely",
                failure
            );
        }
    }

    private static boolean closeScope(
        final DisposableScope scope,
        final PreviewLog log
    ) {
        if (scope == null) {
            return true;
        }
        try {
            scope.close();
            return true;
        } catch (Throwable failure) {
            log.error(
                dev.turboism.plugin.core.CorePluginManagement.CORE_PLUGIN_ID,
                "Built-in core scope cleanup failed safely",
                failure
            );
            return false;
        }
    }

    private static void closeResources(
        final URLClassLoader resources,
        final PreviewLog log
    ) {
        try {
            resources.close();
        } catch (Throwable failure) {
            log.error(
                dev.turboism.plugin.core.CorePluginManagement.CORE_PLUGIN_ID,
                "Built-in core classloader cleanup failed safely",
                failure
            );
        }
    }

    static URLClassLoader resourceLoader(final ClassLoader loader) {
        return new URLClassLoader(new URL[]{coreSource(loader)}, loader);
    }

    /**
     * The agent jar that carries the built-in core. With {@code Boot-Class-Path}
     * the core classes may be bootstrap-loaded, in which case the protection
     * domain has no CodeSource; fall back to the system classpath jar that
     * still carries the descriptor (the agent jar is appended to the system
     * classpath by {@code -javaagent}).
     */
    private static URL coreSource(final ClassLoader loader) {
        final java.security.CodeSource codeSource =
            MainToolbarPlugin.class.getProtectionDomain().getCodeSource();
        if (codeSource != null && codeSource.getLocation() != null) return codeSource.getLocation();
        final URL descriptorResource = loader != null
            ? loader.getResource(DESCRIPTOR)
            : ClassLoader.getSystemResource(DESCRIPTOR);
        if (descriptorResource == null) throw new IllegalStateException("built-in core descriptor is missing");
        if ("jar".equals(descriptorResource.getProtocol())) {
            try {
                return jarSourceUrl(descriptorResource);
            } catch (java.net.MalformedURLException impossible) {
                throw new IllegalStateException("built-in core source is invalid", impossible);
            }
        }
        return descriptorResource;
    }

    /**
     * {@code jar:file:/.../turboism-agent.jar!/entry} -> {@code file:/.../turboism-agent.jar}.
     */
    static URL jarSourceUrl(final URL descriptorResource) throws java.net.MalformedURLException {
        final String spec = descriptorResource.toExternalForm();
        final int separator = spec.indexOf("!/");
        if (separator <= 0) return descriptorResource;
        return java.net.URI.create(spec.substring(4, separator)).toURL();
    }

    private static InputStream descriptorStream(final ClassLoader loader) {
        final URL resource = loader != null
            ? loader.getResource(DESCRIPTOR)
            : ClassLoader.getSystemResource(DESCRIPTOR);
        if (resource == null) return null;
        try {
            return resource.openStream();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("built-in core descriptor is unreadable", failure);
        }
    }
}
