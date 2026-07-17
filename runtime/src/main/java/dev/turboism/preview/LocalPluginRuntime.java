package dev.turboism.preview;

import dev.turboism.adapter.cubism.service.read.M12ReadSnapshotSource;
import dev.turboism.adapter.host.RuntimeHostAdapterAccess;
import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.core.dependency.DependencyResolver;
import dev.turboism.core.descriptor.DescriptorParseException;
import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.core.plugin.PluginRuntime;
import dev.turboism.config.RuntimeTypedPluginConfigRegistry;
import dev.turboism.core.plugin.context.CorePluginContext;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.version.PluginVersion;
import dev.turboism.core.version.VersionRange;
import dev.turboism.i18n.RuntimePluginLocalization;
import dev.turboism.hostread.ProjectWorkspaceHostReadSource;
import dev.turboism.hostread.RuntimeAsyncHostReadService;
import dev.turboism.hostread.SharedAsyncHostReadLane;
import dev.turboism.storage.RuntimePluginStorage;
import dev.turboism.task.RuntimePluginTaskScheduler;
import dev.turboism.userfile.RuntimeUserFileAccessService;
import dev.turboism.userfile.UserFileGrantSource;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.storage.StorageRoot;
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
    private final SharedAsyncHostReadLane hostReadLane;
    private final PreviewLog log;
    private final PluginCloseHook pluginCloseHook;
    private final List<LoadedPlugin> loaded = new ArrayList<>();
    private List<LoadedPluginSummary> closedSummaries = List.of();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public LocalPluginRuntime(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final PreviewLog log
    ) {
        this(home, scheduler, hostAccess, log, (pluginId, phase) -> { });
    }

    LocalPluginRuntime(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final PreviewLog log,
        final PluginCloseHook pluginCloseHook
    ) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.pluginDirectory = this.home.resolve("plugins");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.hostAccess = Objects.requireNonNull(hostAccess, "hostAccess");
        this.hostReadLane = new SharedAsyncHostReadLane(32);
        this.log = Objects.requireNonNull(log, "log");
        this.pluginCloseHook = Objects.requireNonNull(pluginCloseHook, "pluginCloseHook");
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
            final PreviewPluginPaths pluginPaths = PreviewPluginPaths.create(home, descriptor.id());
            final CorePluginContext.Dependencies baseDependencies = new CorePluginContext.Dependencies(
                descriptor,
                new PreviewPluginLogger(log, descriptor.id()),
                pluginPaths,
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
            final RuntimePluginLocalization localization = RuntimePluginLocalization.create(
                descriptor.id(),
                classLoader,
                System.getProperty("turboism.locale"),
                Locale.getDefault(Locale.Category.DISPLAY),
                Locale.getDefault(Locale.Category.DISPLAY),
                diagnostic -> log.warn(
                    descriptor.id(),
                    diagnostic.code() + ": " + diagnostic.message()
                )
            );
            final CleanupEvidenceCollector cleanupEvidence = new CleanupEvidenceCollector();
            final RuntimePluginTaskScheduler taskScheduler = new RuntimePluginTaskScheduler(
                descriptor.id(),
                scheduler,
                scope,
                cleanupEvidence
            );
            final Set<String> permissionIds = descriptor.permissions().stream()
                .map(permission -> permission.id())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
            final RuntimePluginStorage pluginStorage = new RuntimePluginStorage(
                descriptor.id(),
                Map.of(
                    StorageRoot.DATA, pluginPaths.dataDir(),
                    StorageRoot.STATE, pluginPaths.stateDir(),
                    StorageRoot.CACHE, pluginPaths.cacheDir()
                ),
                permissionIds,
                taskScheduler,
                scope,
                cleanupEvidence
            );
            final RuntimeTypedPluginConfigRegistry typedConfig =
                new RuntimeTypedPluginConfigRegistry(
                    baseDependencies.config(),
                    descriptor.id(),
                    pluginPaths.dataDir().resolve("typed-config"),
                    permissionIds,
                    taskScheduler,
                    scope,
                    cleanupEvidence
                );
            final RuntimeUserFileAccessService userFiles =
                new RuntimeUserFileAccessService(
                    descriptor.id(),
                    permissionIds,
                    UserFileGrantSource.unavailable(),
                    taskScheduler,
                    scope,
                    cleanupEvidence
                );
            final RuntimeAsyncHostReadService hostReads = new RuntimeAsyncHostReadService(
                descriptor.id(),
                permissionIds,
                ProjectWorkspaceHostReadSource.from(hostAccess.adapters().projectWorkspace()),
                hostReadLane,
                taskScheduler,
                scope
            );
            final CorePluginContext.Dependencies dependencies =
                baseDependencies.withConfig(typedConfig);
            final CorePluginContext context = new CorePluginContext(
                dependencies,
                hostAccess,
                localization,
                taskScheduler,
                pluginStorage,
                userFiles,
                hostReads
            );
            runtime.setContext(context);
            log.debug(
                descriptor.id(),
                "Localization active locale=" + localization.locale().toLanguageTag()
            );

            plugin.init(context);
            runtime.transitionTo(PluginLifecycleState.LOADED);
            try {
                plugin.enable();
            } catch (Exception enableFailure) {
                runtime.transitionTo(PluginLifecycleState.ENABLE_FAILED);
                throw enableFailure;
            }
            runtime.transitionTo(PluginLifecycleState.ENABLED);

            final LoadedPlugin loadedPlugin = new LoadedPlugin(
                candidate.jar(),
                runtime,
                plugin,
                scope,
                classLoader,
                localization,
                cleanupEvidence
            );
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
        boolean scopeClosed = scope == null;
        if (scope != null) {
            try {
                scope.close();
                scopeClosed = true;
            } catch (Exception exception) {
                log.error(pluginId, "Plugin scope cleanup after load failure failed", exception);
            }
        }
        if (classLoader != null && scopeClosed) {
            try {
                classLoader.close();
            } catch (IOException exception) {
                log.error(pluginId, "Plugin classloader cleanup after load failure failed", exception);
            }
        } else if (classLoader != null) {
            log.error(
                pluginId,
                "Plugin classloader retained after load failure because cleanup did not quiesce",
                new IllegalStateException("Plugin scope cleanup is incomplete")
            );
        }
    }

    private static String safeMessage(final Throwable failure) {
        final String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getName() : message;
    }

    public synchronized List<LoadedPluginSummary> reportSummaries() {
        if (closed.get()) {
            return closedSummaries;
        }
        return loaded.stream()
            .map(LoadedPlugin::summary)
            .sorted(Comparator.comparing(LoadedPluginSummary::id))
            .toList();
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        final List<LoadedPluginSummary> summaries = new ArrayList<>();
        try {
            for (int index = loaded.size() - 1; index >= 0; index--) {
                try {
                    summaries.add(closeLoadedPlugin(loaded.get(index)));
                } catch (Throwable failure) {
                    final LoadedPlugin failedPlugin = loaded.get(index);
                    try {
                        summaries.add(closeLoadedPluginFailure(failedPlugin));
                    } catch (Throwable fallbackFailure) {
                        summaries.add(closeLoadedPluginFailureWithoutRuntimeMutation(failedPlugin));
                    }
                    tryLogStableShutdownFailure(
                        safePluginId(failedPlugin),
                        "PLUGIN_CLOSE_STAGE_FAILED"
                    );
                }
            }
        } finally {
            try {
                hostReadLane.close();
            } catch (Throwable failure) {
                tryLogStableShutdownFailure("runtime", "HOST_READ_LANE_CLOSE_FAILED");
            } finally {
                closedSummaries = summaries.stream()
                    .sorted(Comparator.comparing(LoadedPluginSummary::id))
                    .toList();
                loaded.clear();
            }
        }
    }

    private LoadedPluginSummary closeLoadedPlugin(final LoadedPlugin loadedPlugin) throws Throwable {
        final PluginRuntime runtime = loadedPlugin.runtime();
        final String id = runtime.id();
        pluginCloseHook.run(id, "close");
        final List<PluginSummaryFailure> failures = new ArrayList<>();
        String disableState = runtime.state() == PluginLifecycleState.ENABLED
            ? "NOT_STARTED"
            : "NOT_REQUIRED";
        if (runtime.state() == PluginLifecycleState.ENABLED) {
            try {
                loadedPlugin.plugin().disable();
                runtime.transitionTo(PluginLifecycleState.DISABLED);
                disableState = "SUCCEEDED";
            } catch (Throwable exception) {
                runtime.transitionTo(PluginLifecycleState.DISABLE_FAILED);
                disableState = "FAILED";
                failures.add(new PluginSummaryFailure(
                    "PLUGIN_DISABLE_FAILED",
                    "disable",
                    "Plugin disable failed safely."
                ));
                logStableShutdownFailure(id, "PLUGIN_DISABLE_FAILED");
            }
        }

        String shutdownState;
        try {
            loadedPlugin.plugin().shutdown();
            runtime.transitionTo(PluginLifecycleState.SHUTDOWN);
            shutdownState = "SUCCEEDED";
        } catch (Throwable exception) {
            runtime.transitionTo(PluginLifecycleState.SHUTDOWN_FAILED);
            shutdownState = "FAILED";
            failures.add(new PluginSummaryFailure(
                "PLUGIN_SHUTDOWN_FAILED",
                "shutdown",
                "Plugin shutdown failed safely."
            ));
            logStableShutdownFailure(id, "PLUGIN_SHUTDOWN_FAILED");
        }

        boolean scopeClosed = false;
        String scopeCleanupState;
        try {
            loadedPlugin.scope().close();
            scopeClosed = true;
            scopeCleanupState = "SUCCEEDED";
        } catch (Throwable exception) {
            runtime.transitionTo(PluginLifecycleState.SHUTDOWN_FAILED);
            scopeCleanupState = "FAILED";
            failures.add(new PluginSummaryFailure(
                "PLUGIN_SCOPE_CLEANUP_FAILED",
                "scope-cleanup",
                "Plugin scope cleanup failed safely."
            ));
            logStableShutdownFailure(id, "PLUGIN_SCOPE_CLEANUP_FAILED");
        }

        boolean classLoaderClosed = false;
        String classloaderCleanupState;
        if (scopeClosed) {
            try {
                loadedPlugin.classLoader().close();
                classLoaderClosed = true;
                classloaderCleanupState = "SUCCEEDED";
            } catch (Throwable exception) {
                runtime.transitionTo(PluginLifecycleState.SHUTDOWN_FAILED);
                classloaderCleanupState = "FAILED";
                failures.add(new PluginSummaryFailure(
                    "PLUGIN_CLASSLOADER_CLOSE_FAILED",
                    "classloader-cleanup",
                    "Plugin classloader cleanup failed safely."
                ));
                logStableShutdownFailure(id, "PLUGIN_CLASSLOADER_CLOSE_FAILED");
            }
        } else {
            classloaderCleanupState = "NOT_STARTED";
            failures.add(new PluginSummaryFailure(
                "PLUGIN_CLASSLOADER_RETAINED",
                "classloader-cleanup",
                "Plugin classloader was retained because cleanup did not quiesce."
            ));
            logStableShutdownFailure(id, "PLUGIN_CLASSLOADER_RETAINED");
        }

        final String unloadState;
        if (runtime.state() == PluginLifecycleState.SHUTDOWN && classLoaderClosed) {
            runtime.transitionTo(PluginLifecycleState.UNLOADED);
            unloadState = "SUCCEEDED";
        } else {
            unloadState = "FAILED";
        }
        log.info(id, "Plugin unloaded with state " + runtime.state());
        return loadedPlugin.summary(
            disableState,
            shutdownState,
            unloadState,
            scopeCleanupState,
            classloaderCleanupState,
            failures
        );
    }

    private LoadedPluginSummary closeLoadedPluginFailure(final LoadedPlugin loadedPlugin)
        throws Throwable {
        pluginCloseHook.run(safePluginId(loadedPlugin), "fallback-summary");
        loadedPlugin.runtime().transitionTo(PluginLifecycleState.SHUTDOWN_FAILED);
        return closeLoadedPluginFailureWithoutRuntimeMutation(loadedPlugin);
    }

    private LoadedPluginSummary closeLoadedPluginFailureWithoutRuntimeMutation(
        final LoadedPlugin loadedPlugin
    ) {
        return loadedPlugin.summary(
            "NOT_STARTED",
            "NOT_STARTED",
            "NOT_STARTED",
            "NOT_STARTED",
            "NOT_STARTED",
            List.of(new PluginSummaryFailure(
                "PLUGIN_CLOSE_STAGE_FAILED",
                "close",
                "Plugin close stage failed safely."
            ))
        );
    }

    private void logStableShutdownFailure(final String component, final String code) {
        log.error(
            component,
            "Runtime shutdown stage failed safely: " + code,
            new IllegalStateException(code)
        );
    }

    private void tryLogStableShutdownFailure(final String component, final String code) {
        try {
            pluginCloseHook.run(component, "fallback-log");
            logStableShutdownFailure(component, code);
        } catch (Throwable ignored) {
        }
    }

    private static String safePluginId(final LoadedPlugin loadedPlugin) {
        try {
            return loadedPlugin.runtime().id();
        } catch (Throwable ignored) {
            return "plugin";
        }
    }

    @FunctionalInterface
    interface PluginCloseHook {
        void run(String pluginId, String phase) throws Throwable;
    }

    private record Candidate(Path jar, PluginDescriptor descriptor) {
    }

    private record LoadedPlugin(
        Path jar,
        PluginRuntime runtime,
        TurboismPlugin plugin,
        DisposableScope scope,
        URLClassLoader classLoader,
        RuntimePluginLocalization localization,
        CleanupEvidenceCollector cleanupEvidence
    ) {
        LoadedPluginSummary summary() {
            final String disable = runtime.state() == PluginLifecycleState.ENABLED
                ? "NOT_STARTED"
                : "NOT_REQUIRED";
            return summary(
                disable,
                "NOT_STARTED",
                "NOT_STARTED",
                "NOT_STARTED",
                "NOT_STARTED",
                List.of()
            );
        }

        LoadedPluginSummary summary(
            final String disableState,
            final String shutdownState,
            final String unloadState,
            final String scopeCleanupState,
            final String classloaderCleanupState,
            final List<PluginSummaryFailure> failures
        ) {
            return new LoadedPluginSummary(
                runtime.id(),
                runtime.descriptor().name(),
                runtime.descriptor().version(),
                runtime.state(),
                jar,
                runtime.descriptor().capabilities(),
                runtime.descriptor().permissions().stream()
                    .map(PluginDescriptor.PermissionRef::id)
                    .toList(),
                localization.reportSnapshot(),
                disableState,
                shutdownState,
                unloadState,
                scopeCleanupState,
                classloaderCleanupState,
                failures,
                cleanupEvidence.snapshot()
            );
        }
    }

    public record PluginSummaryFailure(
        String code,
        String phase,
        String message
    ) {
    }

    public record LoadedPluginSummary(
        String id,
        String name,
        String version,
        PluginLifecycleState state,
        Path jar,
        List<String> capabilities,
        List<String> permissionIds,
        RuntimePluginLocalization.ReportSnapshot localization,
        String disableState,
        String shutdownState,
        String unloadState,
        String scopeCleanupState,
        String classloaderCleanupState,
        List<PluginSummaryFailure> failures,
        CleanupEvidenceCollector.Snapshot cleanupEvidence
    ) {
        public LoadedPluginSummary {
            capabilities = List.copyOf(capabilities);
            permissionIds = List.copyOf(permissionIds);
            failures = List.copyOf(failures);
            cleanupEvidence = Objects.requireNonNull(cleanupEvidence, "cleanupEvidence");
        }
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
