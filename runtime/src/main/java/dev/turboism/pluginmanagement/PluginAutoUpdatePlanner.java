package dev.turboism.pluginmanagement;

import dev.turboism.core.FrameworkBuildInfo;
import dev.turboism.core.plugin.PluginJarContract;
import dev.turboism.core.version.PluginVersion;
import dev.turboism.core.version.VersionRange;
import dev.turboism.internal.core.CorePluginManagement;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogDependency;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogPlugin;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogRelease;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic update planner for installed first-party official plugins.
 *
 * <p>Evaluates channel policy, yanked-release filtering, exact host Cubism version gating
 * (failing closed when unknown and {@code requiresCubism == true}), {@code turboismApi}
 * compatibility, and required inter-plugin dependency ranges, falling back to the highest
 * compatible version when a higher release is incompatible.</p>
 */
public final class PluginAutoUpdatePlanner {

    /** Runtime Turboism API version used by default for {@code turboismApi} range checks. */
    public static final PluginVersion DEFAULT_TURBOISM_API_VERSION =
            FrameworkBuildInfo.current().apiVersion();

    /** Required prefix for first-party official Turboism plugin identifiers. */
    public static final String FIRST_PARTY_ID_PREFIX = "dev.turboism.plugin.";

    private static final String REQUIRED_PLATFORM = "windows-x64";

    private final FrameworkBuildInfo.Channel channel;
    private final Optional<String> cubismVersion;
    private final PluginVersion turboismApiVersion;

    /** Creates a planner for the given framework channel and optional exact host Cubism version. */
    public PluginAutoUpdatePlanner(final FrameworkBuildInfo.Channel channel, final Optional<String> cubismVersion) {
        this(channel, cubismVersion, DEFAULT_TURBOISM_API_VERSION);
    }

    /** Creates a planner with an explicit runtime Turboism API version for focused testing. */
    public PluginAutoUpdatePlanner(
            final FrameworkBuildInfo.Channel channel,
            final Optional<String> cubismVersion,
            final PluginVersion turboismApiVersion) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.cubismVersion = Objects.requireNonNull(cubismVersion, "cubismVersion")
                .map(String::trim)
                .filter(value -> !value.isEmpty());
        this.turboismApiVersion = Objects.requireNonNull(turboismApiVersion, "turboismApiVersion");
    }

    /** Returns whether {@code pluginId} is an eligible first-party official plugin identifier. */
    public static boolean isFirstPartyOfficialId(final String pluginId) {
        return pluginId != null
                && pluginId.startsWith(FIRST_PARTY_ID_PREFIX)
                && !CorePluginManagement.CORE_PLUGIN_ID.equals(pluginId)
                && !PluginJarContract.RETIRED_PLUGIN_IDS.contains(pluginId);
    }

    /**
     * Computes the ordered list of compatible updates for installed first-party official plugins.
     *
     * @param catalog verified v2 catalog
     * @param installedVersions map of locally installed plugin IDs (including disabled ones) to current version
     * @return ordered list of planned updates
     */
    public List<PlannedUpdate> plan(final PluginCatalog catalog, final Map<String, PluginVersion> installedVersions) {
        return plan(catalog, installedVersions, Set.of(), Set.of());
    }

    /**
     * Computes the ordered list of compatible updates, excluding plugins staged for uninstall
     * and any release keys (`pluginId@version`) rejected during post-download JAR inspection.
     *
     * @param catalog verified v2 catalog
     * @param installedVersions map of locally installed plugin IDs (including disabled ones) to current version
     * @param pendingUninstallIds plugin IDs with a pending {@code UNINSTALL} journal entry
     * @param excludedReleaseKeys release keys (`pluginId@version`) to skip
     * @return ordered list of planned updates
     */
    public List<PlannedUpdate> plan(
            final PluginCatalog catalog,
            final Map<String, PluginVersion> installedVersions,
            final Set<String> pendingUninstallIds,
            final Set<String> excludedReleaseKeys) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(installedVersions, "installedVersions");
        Objects.requireNonNull(pendingUninstallIds, "pendingUninstallIds");
        Objects.requireNonNull(excludedReleaseKeys, "excludedReleaseKeys");

        final Map<String, List<PlannedUpdate>> candidatesByPlugin = new LinkedHashMap<>();
        for (CatalogPlugin plugin : catalog.plugins()) {
            if (!plugin.isOfficial() || !isFirstPartyOfficialId(plugin.id())) {
                continue;
            }
            if (pendingUninstallIds.contains(plugin.id())) {
                continue;
            }
            final PluginVersion installedVersion = installedVersions.get(plugin.id());
            if (installedVersion == null) {
                continue;
            }
            final List<PlannedUpdate> candidates =
                    compatibleReleasesDescending(plugin, installedVersion, excludedReleaseKeys);
            if (!candidates.isEmpty()) {
                candidatesByPlugin.put(plugin.id(), candidates);
            }
        }

        final Map<String, Integer> selectedIndex = new LinkedHashMap<>();
        for (String pluginId : candidatesByPlugin.keySet()) {
            selectedIndex.put(pluginId, 0);
        }

        // Fixed-point dependency satisfaction: if a selected candidate's required dependency
        // is missing or outside its version range, fall back to the next highest compatible
        // release for that plugin until all selected updates have satisfied dependencies.
        boolean changed = true;
        while (changed) {
            changed = false;
            final Map<String, PluginVersion> postUpdateVersions = new HashMap<>();
            installedVersions.forEach((id, version) -> {
                if (!pendingUninstallIds.contains(id)) {
                    postUpdateVersions.put(id, version);
                }
            });
            for (Map.Entry<String, Integer> entry : selectedIndex.entrySet()) {
                final List<PlannedUpdate> list = candidatesByPlugin.get(entry.getKey());
                final int idx = entry.getValue();
                if (idx < list.size()) {
                    postUpdateVersions.put(entry.getKey(), list.get(idx).targetVersion());
                }
            }
            for (Map.Entry<String, List<PlannedUpdate>> entry : candidatesByPlugin.entrySet()) {
                final String pluginId = entry.getKey();
                final List<PlannedUpdate> list = entry.getValue();
                final int idx = selectedIndex.get(pluginId);
                if (idx >= list.size()) continue;
                final PlannedUpdate current = list.get(idx);
                if (!dependenciesSatisfied(current.release(), postUpdateVersions)) {
                    selectedIndex.put(pluginId, idx + 1);
                    changed = true;
                }
            }
        }

        final List<PlannedUpdate> selected = new ArrayList<>();
        for (Map.Entry<String, List<PlannedUpdate>> entry : candidatesByPlugin.entrySet()) {
            final int idx = selectedIndex.get(entry.getKey());
            if (idx < entry.getValue().size()) {
                selected.add(entry.getValue().get(idx));
            }
        }
        return orderByDependencies(selected);
    }

    private List<PlannedUpdate> compatibleReleasesDescending(
            final CatalogPlugin plugin, final PluginVersion installedVersion, final Set<String> excludedReleaseKeys) {
        final List<PlannedUpdate> matches = new ArrayList<>();
        final List<CatalogRelease> releases = plugin.releases();
        for (int index = releases.size() - 1; index >= 0; index--) {
            final CatalogRelease release = releases.get(index);
            if (!release.isActive() || release.isYanked()) {
                continue;
            }
            if (excludedReleaseKeys.contains(plugin.id() + "@" + release.version())) {
                continue;
            }
            final PluginVersion releaseVersion = PluginVersion.parse(release.version());
            if (releaseVersion.compareTo(installedVersion) <= 0) {
                continue;
            }
            if (!channelAccepted(release.channel())) {
                continue;
            }
            if (!cubismAccepted(release)) {
                continue;
            }
            if (!turboismApiAccepted(release)) {
                continue;
            }
            if (!release.platforms().contains(REQUIRED_PLATFORM)) {
                continue;
            }
            matches.add(new PlannedUpdate(plugin, release, installedVersion, releaseVersion));
        }
        return matches;
    }

    private boolean channelAccepted(final String releaseChannel) {
        return switch (channel) {
            case BETA, NIGHTLY ->
                CatalogRelease.CHANNEL_STABLE.equals(releaseChannel)
                        || CatalogRelease.CHANNEL_PREVIEW.equals(releaseChannel);
            case STABLE, UNKNOWN -> CatalogRelease.CHANNEL_STABLE.equals(releaseChannel);
        };
    }

    private boolean cubismAccepted(final CatalogRelease release) {
        if (!release.requiresCubism()) {
            return true;
        }
        if (cubismVersion.isEmpty()) {
            return false;
        }
        return release.cubismVersions().contains(cubismVersion.orElseThrow());
    }

    private boolean turboismApiAccepted(final CatalogRelease release) {
        try {
            return VersionRange.parse(release.turboismApi()).contains(turboismApiVersion);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean dependenciesSatisfied(
            final CatalogRelease release, final Map<String, PluginVersion> availableVersions) {
        for (CatalogDependency dependency : release.dependencies()) {
            if (!dependency.isRequired()) continue;
            final PluginVersion available = availableVersions.get(dependency.id());
            if (available == null) return false;
            try {
                if (!VersionRange.parse(dependency.version()).contains(available)) {
                    return false;
                }
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }
        return true;
    }

    private static List<PlannedUpdate> orderByDependencies(final List<PlannedUpdate> updates) {
        final List<PlannedUpdate> sortedById = updates.stream()
                .sorted(Comparator.comparing(PlannedUpdate::pluginId))
                .toList();
        final Map<String, PlannedUpdate> byId = new LinkedHashMap<>();
        for (PlannedUpdate update : sortedById) {
            byId.put(update.pluginId(), update);
        }
        final List<PlannedUpdate> ordered = new ArrayList<>(sortedById.size());
        final Set<String> visited = new HashSet<>();
        final Set<String> visiting = new HashSet<>();
        for (PlannedUpdate update : sortedById) {
            visit(update, byId, visited, visiting, ordered);
        }
        return List.copyOf(ordered);
    }

    private static void visit(
            final PlannedUpdate update,
            final Map<String, PlannedUpdate> byId,
            final Set<String> visited,
            final Set<String> visiting,
            final List<PlannedUpdate> ordered) {
        if (visited.contains(update.pluginId())) return;
        if (!visiting.add(update.pluginId())) return;
        for (CatalogDependency dependency : update.release().dependencies()) {
            final PlannedUpdate depUpdate = byId.get(dependency.id());
            if (depUpdate != null) {
                visit(depUpdate, byId, visited, visiting, ordered);
            }
        }
        visiting.remove(update.pluginId());
        if (visited.add(update.pluginId())) {
            ordered.add(update);
        }
    }

    /**
     * One planned update for an installed first-party official plugin.
     *
     * @param plugin catalog plugin entry
     * @param release selected compatible target release
     * @param installedVersion currently installed version
     * @param targetVersion parsed target release version
     */
    public record PlannedUpdate(
            CatalogPlugin plugin, CatalogRelease release, PluginVersion installedVersion, PluginVersion targetVersion) {
        public PlannedUpdate {
            plugin = Objects.requireNonNull(plugin, "plugin");
            release = Objects.requireNonNull(release, "release");
            installedVersion = Objects.requireNonNull(installedVersion, "installedVersion");
            targetVersion = Objects.requireNonNull(targetVersion, "targetVersion");
        }

        /** Returns the plugin identifier (`plugin.id()`). */
        public String pluginId() {
            return plugin.id();
        }

        /** Returns the composite release key (`pluginId@version`). */
        public String releaseKey() {
            return plugin.id() + "@" + release.version();
        }
    }
}
