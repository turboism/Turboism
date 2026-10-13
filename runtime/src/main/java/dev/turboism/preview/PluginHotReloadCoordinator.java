package dev.turboism.preview;

import dev.turboism.core.descriptor.CorePluginDescriptor;
import dev.turboism.sdk.plugin.PluginDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Coordinates verified runtime hot-load, hot-unload, and hot-reload generation cycles.
 *
 * <p>Enforces fail-closed safety gating before lifecycle manipulation: only plugins declaring
 * {@code lifecycle.hotReload == true} without contributing {@code META-INF/turboism/hooks} or agent
 * transforms are admitted. On load failure during a reload attempt, rolls back to the previous
 * generation intact.</p>
 */
public final class PluginHotReloadCoordinator {

    private static final String CORE_ID = dev.turboism.internal.core.CorePluginManagement.CORE_PLUGIN_ID;
    private static final Set<String> UNSAFE_CAPABILITIES =
            Set.of("bytecode-transform", "agent-hook", "host-transform", "native-hook", "redefine-classes");

    private final PreviewPluginDiscovery discovery;
    private final PreviewPluginLoader loader;
    private final PreviewPluginShutdown shutdown;
    private final List<LocalPluginRuntime.LoadedPlugin> loaded;
    private final PreviewLog log;

    public PluginHotReloadCoordinator(
            final PreviewPluginDiscovery discovery,
            final PreviewPluginLoader loader,
            final PreviewPluginShutdown shutdown,
            final List<LocalPluginRuntime.LoadedPlugin> loaded,
            final PreviewLog log) {
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.shutdown = Objects.requireNonNull(shutdown, "shutdown");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Probes whether the candidate JAR is proven safe for hot lifecycle operations.
     *
     * @param jarPath path to the plugin JAR
     * @return {@code true} only when all safety assertions hold
     */
    public boolean isHotReloadSafe(final Path jarPath) {
        if (jarPath == null || !Files.isRegularFile(jarPath, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        final List<LocalPluginRuntime.PluginFailure> failures = new ArrayList<>();
        final PreviewPluginCandidate candidate = discovery.readCandidate(jarPath, failures);
        if (candidate == null) {
            return false;
        }
        return isHotReloadSafe(candidate.descriptor(), jarPath);
    }

    /**
     * Checks descriptor and JAR structural safety rules.
     *
     * @param descriptor parsed plugin descriptor
     * @param jarPath path to the plugin JAR
     * @return {@code true} if descriptor and JAR satisfy fail-closed hot-reload criteria
     */
    public boolean isHotReloadSafe(final PluginDescriptor descriptor, final Path jarPath) {
        if (descriptor == null || CORE_ID.equals(descriptor.id())) {
            return false;
        }
        if (!(descriptor instanceof CorePluginDescriptor cpd && cpd.hotReload())) {
            return false;
        }
        for (String capability : descriptor.capabilities()) {
            if (UNSAFE_CAPABILITIES.contains(capability)) {
                return false;
            }
        }
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            if (jar.getJarEntry("META-INF/turboism/hooks") != null) {
                return false;
            }
            final var manifest = jar.getManifest();
            if (manifest != null) {
                final var attrs = manifest.getMainAttributes();
                if (attrs.getValue("Premain-Class") != null || attrs.getValue("Agent-Class") != null) {
                    return false;
                }
            }
        } catch (IOException unreadable) {
            return false;
        }
        return true;
    }

    /**
     * Hot-loads a newly installed or enabled plugin at runtime.
     *
     * @param jarPath path to the plugin JAR
     * @return the hot-load outcome
     */
    public synchronized HotReloadResult hotLoad(final Path jarPath) {
        if (!isHotReloadSafe(jarPath)) {
            return HotReloadResult.rejected("HOT_RELOAD_UNSAFE", "Plugin is not admitted for hot loading.");
        }
        final List<LocalPluginRuntime.PluginFailure> failures = new ArrayList<>();
        final PreviewPluginCandidate candidate = discovery.readCandidate(jarPath, failures);
        if (candidate == null) {
            return HotReloadResult.failed("CANDIDATE_READ_FAILED", "Failed to read plugin candidate.");
        }
        final String pluginId = candidate.descriptor().id();
        if (isLoaded(pluginId)) {
            return HotReloadResult.rejected("PLUGIN_ALREADY_LOADED", "Plugin is already loaded in this session.");
        }
        if (!dependenciesSatisfied(candidate.descriptor())) {
            return HotReloadResult.rejected("DEPENDENCIES_UNSATISFIED", "Required dependencies are not active.");
        }
        final LocalPluginRuntime.LoadedPluginSummary summary = loader.load(candidate, failures);
        if (summary == null) {
            return HotReloadResult.failed("LOAD_FAILED", "Plugin load execution failed.");
        }
        log.info(
                pluginId,
                "Plugin lifecycle: hot load succeeded version="
                        + candidate.descriptor().version());
        return HotReloadResult.success();
    }

    /**
     * Hot-unloads an active plugin from the runtime, closing its classloader and registrations.
     *
     * @param pluginId unique plugin identifier
     * @return the hot-unload outcome
     */
    public synchronized HotReloadResult hotUnload(final String pluginId) {
        if (CORE_ID.equals(pluginId)) {
            return HotReloadResult.rejected("CORE_PROTECTED", "Turboism core cannot be unloaded.");
        }
        final LocalPluginRuntime.LoadedPlugin live = findLoaded(pluginId).orElse(null);
        if (live == null) {
            return HotReloadResult.success();
        }
        if (hasActiveDependents(pluginId)) {
            return HotReloadResult.rejected("DEPENDENTS_ACTIVE", "Active plugins depend on this plugin.");
        }
        shutdown.fence(List.of(live));
        loaded.remove(live);
        final LocalPluginRuntime.LoadedPluginSummary summary = shutdown.unloadOne(live);
        if (!"SUCCEEDED".equals(summary.unloadState())) {
            return HotReloadResult.failed("UNLOAD_FAILED", "Plugin unload failed: " + summary.unloadState());
        }
        log.info(pluginId, "Plugin lifecycle: hot unload succeeded");
        return HotReloadResult.success();
    }

    /**
     * Unloads the existing generation, loads the new generation, and rolls back on failure.
     *
     * @param pluginId unique plugin identifier
     * @param newJarPath path to the replacement JAR
     * @param rollbackJarPath path to the existing JAR used for rollback if the replacement fails
     * @return the reload outcome
     */
    public synchronized HotReloadResult hotReload(
            final String pluginId, final Path newJarPath, final Path rollbackJarPath) {
        if (!isHotReloadSafe(newJarPath)) {
            return HotReloadResult.rejected("HOT_RELOAD_UNSAFE", "Replacement JAR is not admitted for hot reload.");
        }
        final LocalPluginRuntime.LoadedPlugin live = findLoaded(pluginId).orElse(null);
        if (live != null && !isHotReloadSafe(live.runtime().descriptor(), live.jar())) {
            return HotReloadResult.rejected("ACTIVE_GENERATION_UNSAFE", "Active generation is not hot-reload safe.");
        }
        if (hasActiveDependents(pluginId)) {
            return HotReloadResult.rejected("DEPENDENTS_ACTIVE", "Active plugins depend on this plugin.");
        }
        if (live != null) {
            shutdown.fence(List.of(live));
            loaded.remove(live);
            final LocalPluginRuntime.LoadedPluginSummary unloadSummary = shutdown.unloadOne(live);
            if (!"SUCCEEDED".equals(unloadSummary.unloadState())) {
                return HotReloadResult.failed("UNLOAD_FAILED", "Failed to cleanly unload previous generation.");
            }
        }
        final List<LocalPluginRuntime.PluginFailure> failures = new ArrayList<>();
        final PreviewPluginCandidate candidate = discovery.readCandidate(newJarPath, failures);
        if (candidate != null && dependenciesSatisfied(candidate.descriptor())) {
            final LocalPluginRuntime.LoadedPluginSummary summary = loader.load(candidate, failures);
            if (summary != null) {
                log.info(
                        pluginId,
                        "Plugin lifecycle: hot reload succeeded version="
                                + candidate.descriptor().version());
                return HotReloadResult.success();
            }
        }
        // Failure: rollback to previous generation if available
        if (rollbackJarPath != null && Files.isRegularFile(rollbackJarPath, LinkOption.NOFOLLOW_LINKS)) {
            final List<LocalPluginRuntime.PluginFailure> rollbackFailures = new ArrayList<>();
            final PreviewPluginCandidate rollbackCandidate = discovery.readCandidate(rollbackJarPath, rollbackFailures);
            if (rollbackCandidate != null) {
                loader.load(rollbackCandidate, rollbackFailures);
            }
            log.warn(pluginId, "Plugin lifecycle: hot reload failed; rolled back to previous generation");
            return HotReloadResult.rolledBack(
                    "HOT_RELOAD_FAILED", "New generation failed to start; rolled back to previous generation.");
        }
        return HotReloadResult.failed(
                "HOT_RELOAD_FAILED", "New generation failed to start and rollback was unavailable.");
    }

    public boolean isLoaded(final String pluginId) {
        return findLoaded(pluginId).isPresent();
    }

    public Optional<LocalPluginRuntime.LoadedPlugin> findLoaded(final String pluginId) {
        return loaded.stream()
                .filter(entry -> entry.runtime().id().equals(pluginId))
                .findFirst();
    }

    private boolean dependenciesSatisfied(final PluginDescriptor descriptor) {
        for (PluginDescriptor.DependencyRef ref : descriptor.dependencies()) {
            if ("required".equals(ref.type()) && !isLoaded(ref.id())) {
                return false;
            }
        }
        return true;
    }

    private boolean hasActiveDependents(final String targetPluginId) {
        for (LocalPluginRuntime.LoadedPlugin live : loaded) {
            if (live.runtime().id().equals(targetPluginId)) continue;
            for (PluginDescriptor.DependencyRef ref :
                    live.runtime().descriptor().dependencies()) {
                if ("required".equals(ref.type()) && ref.id().equals(targetPluginId)) {
                    return true;
                }
            }
        }
        return false;
    }

    public record HotReloadResult(boolean succeeded, boolean rolledBack, String code, String message) {
        public static HotReloadResult success() {
            return new HotReloadResult(true, false, "APPLIED", "Applied immediately.");
        }

        public static HotReloadResult failed(final String code, final String message) {
            return new HotReloadResult(false, false, code, message);
        }

        public static HotReloadResult rolledBack(final String code, final String message) {
            return new HotReloadResult(false, true, code, message);
        }

        public static HotReloadResult rejected(final String code, final String message) {
            return new HotReloadResult(false, false, code, message);
        }
    }
}
