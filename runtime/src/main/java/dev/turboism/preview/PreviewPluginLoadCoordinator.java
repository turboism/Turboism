package dev.turboism.preview;

import dev.turboism.core.dependency.DependencyResolver;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.ParameterHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.PartHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.ProjectLifecycleHookRegistry;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private final PreviewPluginShutdown shutdown;
    private final List<LocalPluginRuntime.LoadedPlugin> loaded;
    private final PreviewLog log;

    PreviewPluginLoadCoordinator(
        final Path home,
        final Path pluginDirectory,
        final PreviewPluginContextFactory contextFactory,
        final PreviewLog log,
        final List<LocalPluginRuntime.LoadedPlugin> loaded,
        final PreviewPluginShutdown shutdown,
        final ParameterHookRegistry parameterHookRegistry,
        final PartHookRegistry partHookRegistry,
        final EditorObjectHookRegistry editorObjectHookRegistry,
        final ProjectLifecycleHookRegistry projectLifecycleHookRegistry
    ) {
        this.pluginDirectory = pluginDirectory;
        this.home = home.toAbsolutePath().normalize();
        this.discovery = new PreviewPluginDiscovery(pluginDirectory, log);
        this.loader = new PreviewPluginLoader(
            contextFactory, log, loaded, parameterHookRegistry, partHookRegistry,
            editorObjectHookRegistry, projectLifecycleHookRegistry
        );
        this.shutdown = java.util.Objects.requireNonNull(shutdown, "shutdown");
        this.loaded = loaded;
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
        for (String disabledId : configuredDisabled) {
            if (candidates.remove(disabledId) != null) {
                log.info(disabledId, "Plugin lifecycle: load skipped because plugin is configured disabled");
            }
        }
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
        recordDisabled(disabled, resolution.disabledReasons(), candidates, failures);
        final Map<String, List<String>> requiredDependents = requiredDependents(resolution);
        final List<LocalPluginRuntime.LoadedPluginSummary> summaries = new ArrayList<>();
        final Set<String> runtimeFailed = new LinkedHashSet<>();
        for (DependencyResolver.ResolvedPlugin resolved : resolution.loadOrder()) {
            loadResolvedPlugin(
                resolved, candidates, disabled, requiredDependents,
                runtimeFailed, failures, summaries
            );
        }
        log.info("plugin-loader", "Plugin load complete: loaded=" + summaries.size() + ", failed=" + failures.size());
        // A bare count is not diagnosable. An exact-host run reported failed=1 with nothing
        // naming the plugin or the reason, which cost several host sessions to narrow down by
        // hand; every failure now says which plugin, which code, and why.
        for (LocalPluginRuntime.PluginFailure failure : failures) {
            log.warn(
                "plugin-loader",
                "Plugin failed: id=" + failure.pluginId()
                    + " code=" + failure.code()
                    + " jar=" + failure.jar()
                    + " reason=" + failure.message()
            );
        }
        return new LocalPluginRuntime.LoadReport(
            List.copyOf(summaries), List.copyOf(failures), List.copyOf(resolution.cycles())
        );
    }

    private void recordDisabled(
        final Set<String> disabled,
        final Map<String, String> disabledReasons,
        final Map<String, PreviewPluginCandidate> candidates,
        final List<LocalPluginRuntime.PluginFailure> failures
    ) {
        for (String disabledId : disabled) {
            final PreviewPluginCandidate candidate = candidates.get(disabledId);
            final String reason = disabledReasons.getOrDefault(
                disabledId, "required dependency missing, incompatible, or cyclic"
            );
            failures.add(new LocalPluginRuntime.PluginFailure(
                disabledId, candidate == null ? pluginDirectory : candidate.jar(),
                "DEPENDENCY_FAILED", "Plugin disabled at dependency resolution: " + reason
            ));
            log.warn(
                disabledId,
                "Plugin lifecycle: load skipped at dependency resolution: " + reason
            );
        }
    }

    /**
     * Maps each resolved plugin ID to the IDs of plugins that declared it as a required
     * dependency. Required references gate on the target actually loading, whatever the declared
     * ordering: a {@code before} or {@code none} declarer can already be live when its target
     * fails, so runtime failures propagate along this map in both load directions.
     */
    private static Map<String, List<String>> requiredDependents(
        final DependencyResolver.ResolutionResult resolution
    ) {
        final Map<String, List<String>> dependents = new LinkedHashMap<>();
        for (DependencyResolver.ResolvedPlugin resolved : resolution.loadOrder()) {
            for (PluginDescriptor.DependencyRef dependency : resolved.descriptor().dependencies()) {
                if ("required".equals(dependency.type())) {
                    dependents.computeIfAbsent(dependency.id(), key -> new ArrayList<>())
                        .add(resolved.id());
                }
            }
        }
        return dependents;
    }

    private void loadResolvedPlugin(
        final DependencyResolver.ResolvedPlugin resolved,
        final Map<String, PreviewPluginCandidate> candidates,
        final Set<String> disabled,
        final Map<String, List<String>> requiredDependents,
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
            failures.add(new LocalPluginRuntime.PluginFailure(
                resolved.id(), candidate.jar(), "DEPENDENCY_LOAD_FAILED",
                "Required dependency failed to load: " + String.join(", ", failedDependencies)
            ));
            log.warn(
                resolved.id(),
                "Plugin lifecycle: load skipped because required dependencies failed: "
                    + String.join(", ", failedDependencies)
            );
            markRuntimeFailed(
                resolved.id(), candidates, requiredDependents, runtimeFailed, failures, summaries
            );
            return;
        }
        final LocalPluginRuntime.LoadedPluginSummary summary = loader.load(candidate, failures);
        if (summary == null) {
            markRuntimeFailed(
                resolved.id(), candidates, requiredDependents, runtimeFailed, failures, summaries
            );
        } else {
            summaries.add(summary);
        }
    }

    /**
     * Marks a plugin as failed at runtime and propagates to every required dependent, whichever
     * load direction the declared ordering used. Dependents still ahead in the load order are
     * skipped later by {@link #failedDependencies}; dependents already live — possible under
     * {@code before} or {@code none} — are unloaded through the normal loaded-generation shutdown
     * path so none stay falsely healthy.
     *
     * <p>The whole transitive dependent closure is fenced first, then unloaded dependents-first:
     * a plugin is torn down before any member it requires, so no live dependent can keep calling
     * into an already-disposed member of the same closure.</p>
     */
    private void markRuntimeFailed(
        final String failedId,
        final Map<String, PreviewPluginCandidate> candidates,
        final Map<String, List<String>> requiredDependents,
        final Set<String> runtimeFailed,
        final List<LocalPluginRuntime.PluginFailure> failures,
        final List<LocalPluginRuntime.LoadedPluginSummary> summaries
    ) {
        if (!runtimeFailed.add(failedId)) {
            return;
        }
        final Set<String> closure = new LinkedHashSet<>();
        final Deque<String> queue = new ArrayDeque<>();
        queue.addLast(failedId);
        while (!queue.isEmpty()) {
            for (String dependentId : requiredDependents.getOrDefault(queue.removeFirst(), List.of())) {
                if (!runtimeFailed.contains(dependentId) && closure.add(dependentId)) {
                    queue.addLast(dependentId);
                }
            }
        }
        runtimeFailed.addAll(closure);
        for (String dependentId : unloadOrder(closure, candidates)) {
            final LocalPluginRuntime.LoadedPlugin live = loaded.stream()
                .filter(plugin -> plugin.runtime().id().equals(dependentId))
                .findFirst()
                .orElse(null);
            if (live == null) {
                continue;
            }
            final List<String> failedDependencies = failedDependencies(
                live.runtime().descriptor(), runtimeFailed
            );
            final LocalPluginRuntime.LoadedPluginSummary unloadSummary = shutdown.unloadOne(live);
            loaded.remove(live);
            summaries.removeIf(summary -> summary.id().equals(dependentId));
            failures.add(new LocalPluginRuntime.PluginFailure(
                dependentId, live.jar(),
                "DEPENDENCY_LOAD_FAILED",
                "Required dependencies failed after this plugin loaded: "
                    + String.join(", ", failedDependencies)
                    + "; plugin unloaded (unload=" + unloadSummary.unloadState() + ")"
            ));
            log.warn(
                dependentId,
                "Plugin lifecycle: unloaded because required dependencies failed after load: "
                    + String.join(", ", failedDependencies)
            );
        }
    }

    /**
     * Orders a fenced dependent closure for teardown: dependents unload before the members they
     * require, so a still-live plugin never observes a dependency already disposed. Ties and any
     * residual required-cycles fall back to the order the plugins originally loaded in.
     */
    private List<String> unloadOrder(
        final Set<String> closure,
        final Map<String, PreviewPluginCandidate> candidates
    ) {
        final Map<String, Integer> loadIndex = new HashMap<>();
        for (int index = 0; index < loaded.size(); index++) {
            loadIndex.put(loaded.get(index).runtime().id(), index);
        }
        final Map<String, Set<String>> requiresWithin = new LinkedHashMap<>();
        final Map<String, Set<String>> requiredBy = new LinkedHashMap<>();
        for (String member : closure) {
            requiresWithin.put(member, new LinkedHashSet<>());
            requiredBy.put(member, new LinkedHashSet<>());
        }
        for (String member : closure) {
            final PreviewPluginCandidate candidate = candidates.get(member);
            if (candidate == null) {
                continue;
            }
            final PluginDescriptor descriptor = candidate.descriptor();
            for (PluginDescriptor.DependencyRef dependency : descriptor.dependencies()) {
                if ("required".equals(dependency.type()) && closure.contains(dependency.id())) {
                    requiresWithin.get(member).add(dependency.id());
                    requiredBy.get(dependency.id()).add(member);
                }
            }
        }
        final List<String> order = new ArrayList<>();
        final Set<String> pending = new LinkedHashSet<>(closure);
        while (true) {
            final String next = pending.stream()
                .filter(member -> requiredBy.get(member).isEmpty())
                .min(java.util.Comparator.comparingInt(member -> loadIndex.getOrDefault(member, -1)))
                .orElse(null);
            if (next == null) {
                // Mutual required references leave no dependency-free member; the residual order
                // is arbitrary but must stay deterministic.
                pending.stream()
                    .sorted(java.util.Comparator.comparingInt(member -> loadIndex.getOrDefault(member, -1)))
                    .forEach(order::add);
                break;
            }
            order.add(next);
            pending.remove(next);
            for (String target : requiresWithin.get(next)) {
                requiredBy.get(target).remove(next);
            }
        }
        return order;
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
