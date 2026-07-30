package dev.turboism.preview;

import dev.turboism.core.dependency.DependencyResolver;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.adapter.cubism.lifecycle.ParameterHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.PartHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectHookRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Coordinates discovery, dependency resolution, and isolated plugin loading. */
final class PreviewPluginLoadCoordinator {

    private final Path pluginDirectory;
    private final Path home;
    private final PreviewPluginDiscovery discovery;
    private final PreviewPluginLoader loader;
    private final PreviewLog log;

    PreviewPluginLoadCoordinator(
        final Path home,
        final Path pluginDirectory,
        final PreviewPluginContextFactory contextFactory,
        final PreviewLog log,
        final List<LocalPluginRuntime.LoadedPlugin> loaded,
        final ParameterHookRegistry parameterHookRegistry,
        final PartHookRegistry partHookRegistry,
        final EditorObjectHookRegistry editorObjectHookRegistry
    ) {
        this.pluginDirectory = pluginDirectory;
        this.home = home.toAbsolutePath().normalize();
        this.discovery = new PreviewPluginDiscovery(pluginDirectory, log);
        this.loader = new PreviewPluginLoader(
            contextFactory, log, loaded, parameterHookRegistry, partHookRegistry,
            editorObjectHookRegistry
        );
        this.log = log;
    }

    LocalPluginRuntime.LoadReport loadAll() {
        final List<LocalPluginRuntime.PluginFailure> failures = new ArrayList<>();
        final Map<String, PreviewPluginCandidate> candidates = discovery.discover(failures);
        final Set<String> configuredDisabled;
        try {
            configuredDisabled = new dev.turboism.config.RuntimeConfigRepository(
                home, code -> log.warn("plugin-loader", code)
            ).disabledPlugins();
        } catch (RuntimeException invalidConfig) {
            candidates.clear();
            failures.add(new LocalPluginRuntime.PluginFailure(
                "<config>", home.resolve("config.json"), "RUNTIME_CONFIG_INVALID",
                "Plugin discovery failed closed because canonical runtime config is invalid."
            ));
            return new LocalPluginRuntime.LoadReport(List.of(), List.copyOf(failures), List.of());
        }
        configuredDisabled.forEach(candidates::remove);
        if (candidates.isEmpty()) {
            log.warn("plugin-loader", "No valid plugin JARs found in " + pluginDirectory);
            return new LocalPluginRuntime.LoadReport(List.of(), List.copyOf(failures), List.of());
        }
        return loadResolved(candidates, failures);
    }

    private LocalPluginRuntime.LoadReport loadResolved(
        final Map<String, PreviewPluginCandidate> candidates,
        final List<LocalPluginRuntime.PluginFailure> failures
    ) {
        final DependencyResolver.ResolutionResult resolution = new DependencyResolver().resolve(
            candidates.values().stream().map(PreviewPluginCandidate::descriptor).toList()
        );
        final Set<String> disabled = new LinkedHashSet<>(resolution.disabledIds());
        recordDisabled(disabled, candidates, failures);
        final List<LocalPluginRuntime.LoadedPluginSummary> summaries = new ArrayList<>();
        final Set<String> runtimeFailed = new LinkedHashSet<>();
        for (DependencyResolver.ResolvedPlugin resolved : resolution.loadOrder()) {
            loadResolvedPlugin(resolved, candidates, disabled, runtimeFailed, failures, summaries);
        }
        log.info("plugin-loader", "Plugin load complete: loaded=" + summaries.size() + ", failed=" + failures.size());
        return new LocalPluginRuntime.LoadReport(
            List.copyOf(summaries), List.copyOf(failures), List.copyOf(resolution.cycles())
        );
    }

    private void recordDisabled(
        final Set<String> disabled,
        final Map<String, PreviewPluginCandidate> candidates,
        final List<LocalPluginRuntime.PluginFailure> failures
    ) {
        for (String disabledId : disabled) {
            final PreviewPluginCandidate candidate = candidates.get(disabledId);
            failures.add(new LocalPluginRuntime.PluginFailure(
                disabledId, candidate == null ? pluginDirectory : candidate.jar(),
                "DEPENDENCY_FAILED", "Required plugin dependency is missing, incompatible, or cyclic."
            ));
        }
    }

    private void loadResolvedPlugin(
        final DependencyResolver.ResolvedPlugin resolved,
        final Map<String, PreviewPluginCandidate> candidates,
        final Set<String> disabled,
        final Set<String> runtimeFailed,
        final List<LocalPluginRuntime.PluginFailure> failures,
        final List<LocalPluginRuntime.LoadedPluginSummary> summaries
    ) {
        final PreviewPluginCandidate candidate = candidates.get(resolved.id());
        if (candidate == null || disabled.contains(resolved.id())) {
            return;
        }
        final List<String> failedDependencies = failedDependencies(candidate.descriptor(), runtimeFailed);
        if (!failedDependencies.isEmpty()) {
            runtimeFailed.add(resolved.id());
            failures.add(new LocalPluginRuntime.PluginFailure(
                resolved.id(), candidate.jar(), "DEPENDENCY_LOAD_FAILED",
                "Required dependency failed to load: " + String.join(", ", failedDependencies)
            ));
            return;
        }
        final LocalPluginRuntime.LoadedPluginSummary summary = loader.load(candidate, failures);
        if (summary == null) {
            runtimeFailed.add(resolved.id());
        } else {
            summaries.add(summary);
        }
    }

    private static List<String> failedDependencies(
        final PluginDescriptor descriptor,
        final Set<String> runtimeFailed
    ) {
        return descriptor.dependencies().stream()
            .filter(dependency -> "required".equals(dependency.type()))
            .map(PluginDescriptor.DependencyRef::id)
            .filter(runtimeFailed::contains)
            .toList();
    }
}
