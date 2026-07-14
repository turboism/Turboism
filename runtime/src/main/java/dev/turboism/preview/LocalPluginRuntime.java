package dev.turboism.preview;

import dev.turboism.adapter.cubism.service.read.M12ReadSnapshotSource;
import dev.turboism.adapter.host.RuntimeHostAdapterAccess;
import dev.turboism.core.dependency.DependencyResolver;
import dev.turboism.core.descriptor.DescriptorParseException;
import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.core.plugin.PluginRuntime;
import dev.turboism.core.plugin.context.CorePluginContext;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.version.PluginVersion;
import dev.turboism.core.version.VersionRange;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.ui.RuntimeUiScheduler;
import dev.turboism.ui.UiHostStateSource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Minimal real plugin loading and lifecycle path for Turboism 0.1. */
public final class LocalPluginRuntime implements AutoCloseable {

    private static final String DESCRIPTOR_PATH = "META-INF/turboism/plugin.json";
    private static final PluginVersion TURBOISM_API_VERSION = PluginVersion.parse("0.1.0");

    private final Path home;
    private final Path pluginDirectory;
    private final RuntimeScheduler scheduler;
    private final RuntimeHostAdapterAccess hostAccess;
    private final PreviewLog log;
    private final List<LoadedPlugin> loaded = new ArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public LocalPluginRuntime(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final PreviewLog log
    ) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.pluginDirectory = this.home.resolve("plugins");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.hostAccess = Objects.requireNonNull(hostAccess, "hostAccess");
        this.log = Objects.requireNonNull(log, "log");
    }

    public synchronized LoadReport loadAll() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("LocalPluginRuntime has already started");
        }
        if (closed.get()) {
            throw new IllegalStateException("LocalPluginRuntime is closed");
        }

        final List<PluginFailure> failures = new ArrayList<>();
        final Map<String, Candidate> candidates = discover(failures);
        if (candidates.isEmpty()) {
            log.warn("plugin-loader", "No valid plugin JARs found in " + pluginDirectory);
            return new LoadReport(List.of(), List.copyOf(failures), List.of());
        }

        final DependencyResolver.ResolutionResult resolution = new DependencyResolver().resolve(
            candidates.values().stream().map(Candidate::descriptor).toList()
        );
        final Set<String> disabled = new LinkedHashSet<>(resolution.disabledIds());
        for (String disabledId : disabled) {
            final Candidate candidate = candidates.get(disabledId);
            failures.add(new PluginFailure(
                disabledId,
                candidate == null ? pluginDirectory : candidate.jar(),
                "DEPENDENCY_FAILED",
                "Required plugin dependency is missing, incompatible, or cyclic."
            ));
        }

        final List<LoadedPluginSummary> summaries = new ArrayList<>();
        final Set<String> runtimeFailed = new LinkedHashSet<>();
        for (DependencyResolver.ResolvedPlugin resolved : resolution.loadOrder()) {
            final Candidate candidate = candidates.get(resolved.id());
            if (candidate == null || disabled.contains(resolved.id())) {
                continue;
            }
            final List<String> failedDependencies = candidate.descriptor().dependencies().stream()
                .filter(dependency -> "required".equals(dependency.type()))
                .map(PluginDescriptor.DependencyRef::id)
                .filter(runtimeFailed::contains)
                .toList();
            if (!failedDependencies.isEmpty()) {
                runtimeFailed.add(resolved.id());
                failures.add(new PluginFailure(
                    resolved.id(),
                    candidate.jar(),
                    "DEPENDENCY_LOAD_FAILED",
                    "Required dependency failed to load: " + String.join(", ", failedDependencies)
                ));
                continue;
            }
            final LoadedPluginSummary summary = load(candidate, failures);
            if (summary != null) {
                summaries.add(summary);
            } else {
                runtimeFailed.add(resolved.id());
            }
        }

        log.info(
            "plugin-loader",
            "Plugin load complete: loaded=" + summaries.size() + ", failed=" + failures.size()
        );
        return new LoadReport(
            List.copyOf(summaries),
            List.copyOf(failures),
            List.copyOf(resolution.cycles())
        );
    }

    public synchronized List<LoadedPluginSummary> loadedPlugins() {
        return loaded.stream().map(LoadedPlugin::summary).toList();
    }

    private Map<String, Candidate> discover(final List<PluginFailure> failures) {
        final Map<String, Candidate> candidates = new LinkedHashMap<>();
        try {
            Files.createDirectories(pluginDirectory);
            final List<Path> jars;
            try (var entries = Files.list(pluginDirectory)) {
                jars = entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            }
            for (Path jar : jars) {
                final Candidate candidate = readCandidate(jar, failures);
                if (candidate == null) {
                    continue;
                }
                final Candidate previous = candidates.putIfAbsent(candidate.descriptor().id(), candidate);
                if (previous != null) {
                    failures.add(new PluginFailure(
                        candidate.descriptor().id(),
                        jar,
                        "DUPLICATE_PLUGIN_ID",
                        "Plugin ID already provided by " + previous.jar().getFileName()
                    ));
                }
            }
        } catch (IOException exception) {
            failures.add(new PluginFailure(
                "<discovery>",
                pluginDirectory,
                "PLUGIN_DIRECTORY_FAILED",
                exception.getMessage()
            ));
            log.error("plugin-loader", "Plugin discovery failed", exception);
        }
        return candidates;
    }

    private Candidate readCandidate(final Path jar, final List<PluginFailure> failures) {
        try (JarFile archive = new JarFile(jar.toFile())) {
            final JarEntry descriptorEntry = archive.getJarEntry(DESCRIPTOR_PATH);
            if (descriptorEntry == null || descriptorEntry.isDirectory()) {
                failures.add(new PluginFailure(
                    "<unknown>", jar, "PLUGIN_DESCRIPTOR_MISSING", DESCRIPTOR_PATH + " is required"
                ));
                return null;
            }
            final PluginDescriptor descriptor;
            try (InputStream source = archive.getInputStream(descriptorEntry)) {
                descriptor = new PluginDescriptorParser().parse(source);
            }
            if (!supportsCurrentApi(descriptor)) {
                failures.add(new PluginFailure(
                    descriptor.id(),
                    jar,
                    "TURBOISM_API_INCOMPATIBLE",
                    "Plugin requires Turboism API " + descriptor.turboismApi() + ", runtime is 0.1.0"
                ));
                return null;
            }
            return new Candidate(jar.toAbsolutePath().normalize(), descriptor);
        } catch (DescriptorParseException exception) {
            failures.add(new PluginFailure(
                "<invalid>", jar, exception.code(), exception.getMessage()
            ));
            return null;
        } catch (IOException | RuntimeException exception) {
            failures.add(new PluginFailure(
                "<invalid>", jar, "PLUGIN_JAR_READ_FAILED", exception.getMessage()
            ));
            return null;
        }
    }

    private static boolean supportsCurrentApi(final PluginDescriptor descriptor) {
        try {
            return VersionRange.parse(descriptor.turboismApi()).contains(TURBOISM_API_VERSION);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private LoadedPluginSummary load(
        final Candidate candidate,
        final List<PluginFailure> failures
    ) {
        final PluginDescriptor descriptor = candidate.descriptor();
        final PluginRuntime runtime = new PluginRuntime(descriptor.id(), descriptor);
        runtime.transitionTo(PluginLifecycleState.RESOLVED);
        URLClassLoader classLoader = null;
        DisposableScope scope = null;
        TurboismPlugin plugin = null;
        try {
            final URL pluginUrl = candidate.jar().toUri().toURL();
            classLoader = new URLClassLoader(new URL[]{pluginUrl}, TurboismPlugin.class.getClassLoader());
            runtime.transitionTo(PluginLifecycleState.CLASSLOADER_CREATED);

            final String entrypoint = descriptor.entrypoints().get("plugin");
            final Class<?> entrypointType = Class.forName(entrypoint, true, classLoader);
            if (entrypointType.getClassLoader() != classLoader) {
                throw new IllegalArgumentException("Plugin entrypoint must be defined by its own plugin JAR");
            }
            if (!TurboismPlugin.class.isAssignableFrom(entrypointType)) {
                throw new IllegalArgumentException("Plugin entrypoint does not implement TurboismPlugin");
            }
            final Constructor<?> constructor = entrypointType.getDeclaredConstructor();
            if (!Modifier.isPublic(entrypointType.getModifiers()) || !Modifier.isPublic(constructor.getModifiers())) {
                throw new IllegalArgumentException("Plugin entrypoint and no-arg constructor must be public");
            }
            plugin = (TurboismPlugin) constructor.newInstance();
            runtime.setInstance(plugin);
            runtime.transitionTo(PluginLifecycleState.CONSTRUCTED);

            scope = new DisposableScope();
            final RuntimeUiScheduler uiScheduler = new RuntimeUiScheduler(scheduler, descriptor.id());
            scope.register(uiScheduler);
            final CorePluginContext.Dependencies dependencies = new CorePluginContext.Dependencies(
                descriptor,
                new PreviewPluginLogger(log, descriptor.id()),
                PreviewPluginPaths.create(home, descriptor.id()),
                uiScheduler,
                scheduler,
                new PreviewDiagnosticReport(),
                scope,
                EmptyHostSnapshotSource.INSTANCE,
                M12ReadSnapshotSource.EMPTY,
                UiHostStateSource.DEFAULT,
                event -> log.debug(descriptor.id(), event.toString()),
                Clock.systemUTC()
            );
            final CorePluginContext context = new CorePluginContext(dependencies, hostAccess);
            runtime.setContext(context);

            plugin.init(context);
            runtime.transitionTo(PluginLifecycleState.LOADED);
            try {
                plugin.enable();
            } catch (Exception enableFailure) {
                runtime.transitionTo(PluginLifecycleState.ENABLE_FAILED);
                throw enableFailure;
            }
            runtime.transitionTo(PluginLifecycleState.ENABLED);

            final LoadedPlugin loadedPlugin = new LoadedPlugin(candidate.jar(), runtime, plugin, scope, classLoader);
            loaded.add(loadedPlugin);
            log.info(descriptor.id(), "Loaded plugin " + descriptor.name() + " " + descriptor.version());
            return loadedPlugin.summary();
        } catch (Throwable failure) {
            if (runtime.state() != PluginLifecycleState.ENABLE_FAILED) {
                runtime.transitionTo(classLoader == null
                    ? PluginLifecycleState.CLASSLOADER_FAILED
                    : PluginLifecycleState.LOAD_FAILED);
            }
            failures.add(new PluginFailure(
                descriptor.id(), candidate.jar(), runtime.state().name(), safeMessage(failure)
            ));
            log.error(descriptor.id(), "Plugin load failed", failure);
            cleanupFailed(plugin, scope, classLoader, descriptor.id());
            return null;
        }
    }

    private void cleanupFailed(
        final TurboismPlugin plugin,
        final DisposableScope scope,
        final URLClassLoader classLoader,
        final String pluginId
    ) {
        if (plugin != null) {
            try {
                plugin.shutdown();
            } catch (Exception exception) {
                log.error(pluginId, "Plugin cleanup after load failure failed", exception);
            }
        }
        if (scope != null) {
            try {
                scope.close();
            } catch (Exception exception) {
                log.error(pluginId, "Plugin scope cleanup after load failure failed", exception);
            }
        }
        if (classLoader != null) {
            try {
                classLoader.close();
            } catch (IOException exception) {
                log.error(pluginId, "Plugin classloader cleanup after load failure failed", exception);
            }
        }
    }

    private static String safeMessage(final Throwable failure) {
        final String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getName() : message;
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (int index = loaded.size() - 1; index >= 0; index--) {
            closeLoadedPlugin(loaded.get(index));
        }
        loaded.clear();
    }

    private void closeLoadedPlugin(final LoadedPlugin loadedPlugin) {
        final PluginRuntime runtime = loadedPlugin.runtime();
        final String id = runtime.id();
        try {
            if (runtime.state() == PluginLifecycleState.ENABLED) {
                loadedPlugin.plugin().disable();
                runtime.transitionTo(PluginLifecycleState.DISABLED);
            }
        } catch (Exception exception) {
            runtime.transitionTo(PluginLifecycleState.DISABLE_FAILED);
            log.error(id, "Plugin disable failed", exception);
        }
        try {
            loadedPlugin.plugin().shutdown();
            runtime.transitionTo(PluginLifecycleState.SHUTDOWN);
        } catch (Exception exception) {
            runtime.transitionTo(PluginLifecycleState.SHUTDOWN_FAILED);
            log.error(id, "Plugin shutdown failed", exception);
        }
        try {
            loadedPlugin.scope().close();
        } catch (Exception exception) {
            log.error(id, "Plugin disposable scope cleanup failed", exception);
        }
        try {
            loadedPlugin.classLoader().close();
        } catch (IOException exception) {
            log.error(id, "Plugin classloader close failed", exception);
        }
        if (runtime.state() == PluginLifecycleState.SHUTDOWN) {
            runtime.transitionTo(PluginLifecycleState.UNLOADED);
        }
        log.info(id, "Plugin unloaded with state " + runtime.state());
    }

    private record Candidate(Path jar, PluginDescriptor descriptor) {
    }

    private record LoadedPlugin(
        Path jar,
        PluginRuntime runtime,
        TurboismPlugin plugin,
        DisposableScope scope,
        URLClassLoader classLoader
    ) {
        LoadedPluginSummary summary() {
            return new LoadedPluginSummary(
                runtime.id(),
                runtime.descriptor().name(),
                runtime.descriptor().version(),
                runtime.state(),
                jar
            );
        }
    }

    public record LoadedPluginSummary(
        String id,
        String name,
        String version,
        PluginLifecycleState state,
        Path jar
    ) {
    }

    public record PluginFailure(String pluginId, Path jar, String code, String message) {
    }

    public record LoadReport(
        List<LoadedPluginSummary> loaded,
        List<PluginFailure> failures,
        List<String> dependencyCycles
    ) {
        public LoadReport {
            loaded = List.copyOf(loaded);
            failures = List.copyOf(failures);
            dependencyCycles = List.copyOf(dependencyCycles);
        }
    }
}
