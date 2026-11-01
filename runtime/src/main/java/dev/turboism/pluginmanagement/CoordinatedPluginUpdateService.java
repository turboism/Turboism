package dev.turboism.pluginmanagement;

import dev.turboism.core.FrameworkBuildInfo;
import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.core.version.PluginVersion;
import dev.turboism.core.version.VersionRange;
import dev.turboism.distribution.LocalPluginJarPreparer;
import dev.turboism.distribution.PluginJarPreflight;
import dev.turboism.distribution.PreparedPluginJar;
import dev.turboism.internal.core.CorePluginManagement;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogArtifact;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogDependency;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogPlugin;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogRelease;
import dev.turboism.sdk.plugin.PluginDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Coordinates selective, dual-API-compatible plugin updates alongside a framework update.
 *
 * <p>All network and filesystem IO runs off the UI thread. Downloaded packages remain uncommitted
 * in isolated staging files until the user explicitly schedules installation on exit.</p>
 */
public class CoordinatedPluginUpdateService {

    /**
     * Classification of one row of the coordinated plan.
     *
     * <p>Where a newer release is on offer the status describes that offered version; otherwise it
     * describes the installed one. {@code DUAL_COMPATIBLE} means the planned release runs on the current
     * and on the target API, {@code FUTURE_ONLY} means only a newer release that requires the target API
     * exists (so installing it before the framework update is not rollback safe), and
     * {@code TARGET_INCOMPATIBLE} means the installed version does not declare support for the target API.
     * {@code UP_TO_DATE} has nothing newer and compatible to offer, and {@code NOT_OFFICIAL} is not managed
     * by this updater.</p>
     */
    public enum CompatibilityStatus {
        DUAL_COMPATIBLE,
        FUTURE_ONLY,
        TARGET_INCOMPATIBLE,
        UP_TO_DATE,
        NOT_OFFICIAL
    }

    /**
     * One selectable row of the coordinated update plan.
     *
     * @param pluginId plugin id this row belongs to
     * @param pluginName display name from the installed descriptor
     * @param installedVersion currently installed version, or an empty string when the plugin is absent
     * @param targetVersion version the plan would install, or an empty string when nothing is offered
     * @param enabled whether the runtime configuration currently enables this plugin
     * @param selected whether the user wants this row installed together with the framework update
     * @param status classification of the offered or installed version, see {@link CompatibilityStatus}
     * @param explanation user-facing reason for that classification
     * @param requiredDependencies plugin ids that must be installed together with this row
     * @param plannedUpdate catalog release to download, empty when the row needs no download
     */
    public record PluginUpdateRow(
            String pluginId,
            String pluginName,
            String installedVersion,
            String targetVersion,
            boolean enabled,
            boolean selected,
            CompatibilityStatus status,
            String explanation,
            List<String> requiredDependencies,
            Optional<PluginAutoUpdatePlanner.PlannedUpdate> plannedUpdate) {

        /**
         * Returns a copy of this row with only the selection flag replaced.
         *
         * @param newSelected new selection state
         * @return a new immutable row, never this instance
         */
        public PluginUpdateRow withSelected(final boolean newSelected) {
            return new PluginUpdateRow(
                    pluginId,
                    pluginName,
                    installedVersion,
                    targetVersion,
                    enabled,
                    newSelected,
                    status,
                    explanation,
                    requiredDependencies,
                    plannedUpdate);
        }
    }

    /**
     * Outcome of a coordinated download. Prepared staging files are returned only on success and are
     * deleted again on failure or cancellation, so the pending journal is never touched here.
     *
     * @param succeeded whether every selected plugin was downloaded, verified and prepared
     * @param code stable machine-readable outcome such as {@code DOWNLOAD_SUCCESS}
     * @param message user-facing explanation, empty on success
     * @param prepared files prepared for a later explicit commit, empty unless {@code succeeded}
     */
    public record DownloadResult(boolean succeeded, String code, String message, List<PreparedPluginJar> prepared) {

        /**
         * Reports a fully prepared download.
         *
         * @param prepared prepared plugin files, copied into the result
         * @return a successful result carrying those files
         */
        public static DownloadResult success(final List<PreparedPluginJar> prepared) {
            return new DownloadResult(true, "DOWNLOAD_SUCCESS", "", List.copyOf(prepared));
        }

        /**
         * Reports a failed download.
         *
         * @param code stable failure code exposed to the UI and diagnostics
         * @param message user-facing explanation
         * @return a failed result with no prepared files
         */
        public static DownloadResult failure(final String code, final String message) {
            return new DownloadResult(false, code, message, List.of());
        }

        /**
         * Reports a download the user cancelled before it completed.
         *
         * @return a cancelled result with no prepared files
         */
        public static DownloadResult cancelled() {
            return new DownloadResult(false, "DOWNLOAD_CANCELLED", "Plugin download was cancelled.", List.of());
        }
    }

    /**
     * Outcome of committing prepared plugin files to the pending install journal.
     *
     * @param succeeded whether the staged set became a pending next-launch install
     * @param code stable machine-readable outcome such as {@code COMMIT_SUCCESS}
     * @param message user-facing explanation, empty on success
     * @param staged files handed to the journal, empty unless {@code succeeded}
     */
    public record CommitResult(boolean succeeded, String code, String message, List<PreparedPluginJar> staged) {

        /**
         * Reports a commit that staged exactly the requested files.
         *
         * @param staged files now recorded in the pending journal
         * @return a successful commit result
         */
        public static CommitResult success(final List<PreparedPluginJar> staged) {
            return new CommitResult(true, "COMMIT_SUCCESS", "", List.copyOf(staged));
        }

        /**
         * Reports a commit refused because another pending plugin operation already owns the journal.
         *
         * @param message user-facing explanation of the conflicting pending work
         * @return a conflict result with no staged files
         */
        public static CommitResult conflict(final String message) {
            return new CommitResult(false, "CONFLICT_PENDING_PLUGIN", message, List.of());
        }

        /**
         * Reports a commit that did not stage anything.
         *
         * @param code stable failure code exposed to the UI and diagnostics
         * @param message user-facing explanation
         * @return a failed commit result with no staged files
         */
        public static CommitResult failed(final String code, final String message) {
            return new CommitResult(false, code, message, List.of());
        }
    }

    private final Path home;
    private final Path pluginsDirectory;
    private final Path stagingPackages;
    private final PluginCatalogClient client;
    private final PendingPluginOperations pending;
    private final FrameworkBuildInfo.Channel channel;
    private final java.util.function.Supplier<Optional<String>> cubismVersion;
    private final PluginVersion currentApiVersion;

    public CoordinatedPluginUpdateService(
            final Path home,
            final PluginCatalogClient client,
            final PendingPluginOperations pending,
            final FrameworkBuildInfo.Channel channel,
            final java.util.function.Supplier<Optional<String>> cubismVersion,
            final PluginVersion currentApiVersion) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.pluginsDirectory = this.home.resolve("plugins");
        this.stagingPackages = this.home.resolve("state/runtime/plugin-management/packages");
        this.client = Objects.requireNonNull(client, "client");
        this.pending = Objects.requireNonNull(pending, "pending");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.cubismVersion = Objects.requireNonNull(cubismVersion, "cubismVersion");
        this.currentApiVersion = Objects.requireNonNull(currentApiVersion, "currentApiVersion");
    }

    /**
     * Absolute Turboism home this service operates on; all staging stays beneath it.
     *
     * @return normalized absolute home path
     */
    public Path home() {
        return home;
    }

    /**
     * Plans dual-API-compatible updates for all installed plugins against a prospective target API.
     * Enforces dependency closure so that enabled plugins and their required dependencies are selected together.
     */
    public List<PluginUpdateRow> planCoordinatedUpdates(
            final PluginCatalog catalog,
            final List<CorePluginManagement.PluginInfo> installedPlugins,
            final Optional<PluginVersion> targetApiVersion) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(installedPlugins, "installedPlugins");
        Objects.requireNonNull(targetApiVersion, "targetApiVersion");
        if (pending.recoveryRequired()) {
            throw new IllegalStateException(
                    "PLUGIN_PENDING_REVIEW_REQUIRED: review pending changes in Plugin Management first.");
        }

        final Map<String, CorePluginManagement.PluginInfo> byId = new LinkedHashMap<>();
        final Map<String, PluginVersion> installedVersions = new LinkedHashMap<>();
        for (CorePluginManagement.PluginInfo info : installedPlugins) {
            byId.put(info.id(), info);
            try {
                installedVersions.put(info.id(), PluginVersion.parse(info.version()));
            } catch (IllegalArgumentException ignored) {
            }
        }

        final Optional<String> activeCubism = cubismVersion.get();
        final PluginAutoUpdatePlanner planner =
                new PluginAutoUpdatePlanner(channel, activeCubism, currentApiVersion, targetApiVersion);

        final Set<String> pendingUninstalls = new HashSet<>();
        for (PendingPluginOperations.Operation op : pending.operations()) {
            if ("UNINSTALL".equals(op.type())) {
                pendingUninstalls.add(op.pluginId());
            }
        }

        final List<PluginAutoUpdatePlanner.PlannedUpdate> plannedList =
                planner.plan(catalog, installedVersions, pendingUninstalls, Set.of());
        final Map<String, PluginAutoUpdatePlanner.PlannedUpdate> plannedById = new HashMap<>();
        for (PluginAutoUpdatePlanner.PlannedUpdate planned : plannedList) {
            plannedById.put(planned.pluginId(), planned);
        }

        final Map<String, PluginUpdateRow> rawRows = new LinkedHashMap<>();
        for (CorePluginManagement.PluginInfo info : installedPlugins) {
            if (info.core()) continue;

            final String id = info.id();
            final boolean isOfficial = PluginAutoUpdatePlanner.isFirstPartyOfficialId(id);
            if (!isOfficial) {
                rawRows.put(
                        id,
                        new PluginUpdateRow(
                                id,
                                info.name(),
                                info.version(),
                                "",
                                isEnabled(info),
                                false,
                                CompatibilityStatus.NOT_OFFICIAL,
                                "Third-party plugin (catalog unmanaged)",
                                List.of(),
                                Optional.empty()));
                continue;
            }

            final Optional<CatalogPlugin> catPlugin =
                    catalog.plugins().stream().filter(p -> p.id().equals(id)).findFirst();

            if (catPlugin.isEmpty()) {
                rawRows.put(
                        id,
                        new PluginUpdateRow(
                                id,
                                info.name(),
                                info.version(),
                                "",
                                isEnabled(info),
                                false,
                                CompatibilityStatus.UP_TO_DATE,
                                "No catalog entry",
                                List.of(),
                                Optional.empty()));
                continue;
            }

            final PluginAutoUpdatePlanner.PlannedUpdate planned = plannedById.get(id);
            if (planned != null) {
                final List<String> requiredDeps = planned.release().dependencies().stream()
                        .filter(CatalogDependency::isRequired)
                        .map(CatalogDependency::id)
                        .toList();
                rawRows.put(
                        id,
                        new PluginUpdateRow(
                                id,
                                info.name(),
                                info.version(),
                                planned.release().version(),
                                isEnabled(info),
                                isEnabled(info),
                                CompatibilityStatus.DUAL_COMPATIBLE,
                                "Compatible with current and target framework",
                                requiredDeps,
                                Optional.of(planned)));
                continue;
            }

            CompatibilityStatus status = CompatibilityStatus.UP_TO_DATE;
            String explanation = "Up to date";
            if (targetApiVersion.isPresent()) {
                final PluginVersion targetApi = targetApiVersion.get();
                final PluginVersion curVer = installedVersions.get(id);
                boolean hasFutureOnly = false;
                for (CatalogRelease rel : catPlugin.get().releases()) {
                    if (!rel.isActive() || rel.isYanked()) continue;
                    try {
                        final PluginVersion rVer = PluginVersion.parse(rel.version());
                        if (curVer != null && rVer.compareTo(curVer) > 0) {
                            if (planner.isFutureOnly(rel)) {
                                hasFutureOnly = true;
                                break;
                            }
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                if (hasFutureOnly) {
                    status = CompatibilityStatus.FUTURE_ONLY;
                    explanation = "Newer version requires target API only (rollback unsafe)";
                } else if (curVer != null) {
                    final Optional<CatalogRelease> currentRel = catPlugin.get().releases().stream()
                            .filter(r -> r.version().equals(info.version()))
                            .findFirst();
                    if (currentRel.isPresent()) {
                        try {
                            if (!VersionRange.parse(currentRel.get().turboismApi())
                                    .contains(targetApi)) {
                                status = CompatibilityStatus.TARGET_INCOMPATIBLE;
                                explanation =
                                        "Current version does not support target framework API (" + targetApi + ")";
                            }
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
            }

            rawRows.put(
                    id,
                    new PluginUpdateRow(
                            id,
                            info.name(),
                            info.version(),
                            "",
                            isEnabled(info),
                            false,
                            status,
                            explanation,
                            List.of(),
                            Optional.empty()));
        }

        // Enforce dependency closure on default selection:
        enforceDependencyClosure(rawRows);

        return Collections.unmodifiableList(new ArrayList<>(rawRows.values()));
    }

    private static void enforceDependencyClosure(final Map<String, PluginUpdateRow> rowMap) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (PluginUpdateRow row : new ArrayList<>(rowMap.values())) {
                if (row.selected() && row.plannedUpdate().isPresent()) {
                    for (String depId : row.requiredDependencies()) {
                        final PluginUpdateRow depRow = rowMap.get(depId);
                        if (depRow != null && depRow.plannedUpdate().isPresent() && !depRow.selected()) {
                            rowMap.put(depId, depRow.withSelected(true));
                            changed = true;
                        }
                    }
                }
            }
        }
    }

    private static boolean isEnabled(final CorePluginManagement.PluginInfo info) {
        return !"DISABLED".equalsIgnoreCase(info.desiredState());
    }

    /**
     * Recursively updates selection state to enforce dependency relationships.
     */
    public List<PluginUpdateRow> toggleSelection(
            final List<PluginUpdateRow> currentRows, final String toggledId, final boolean newSelected) {
        final Map<String, PluginUpdateRow> rowMap = new LinkedHashMap<>();
        for (PluginUpdateRow row : currentRows) {
            rowMap.put(row.pluginId(), row);
        }

        final PluginUpdateRow target = rowMap.get(toggledId);
        if (target == null || target.status() != CompatibilityStatus.DUAL_COMPATIBLE) {
            return currentRows;
        }

        if (newSelected) {
            rowMap.put(toggledId, target.withSelected(true));
            selectDependenciesRecursively(target, rowMap);
        } else {
            rowMap.put(toggledId, target.withSelected(false));
            deselectDependentsRecursively(toggledId, rowMap);
        }

        return List.copyOf(rowMap.values());
    }

    private void selectDependenciesRecursively(final PluginUpdateRow row, final Map<String, PluginUpdateRow> rowMap) {
        for (String depId : row.requiredDependencies()) {
            final PluginUpdateRow depRow = rowMap.get(depId);
            if (depRow != null && depRow.plannedUpdate().isPresent() && !depRow.selected()) {
                rowMap.put(depId, depRow.withSelected(true));
                selectDependenciesRecursively(depRow, rowMap);
            }
        }
    }

    private void deselectDependentsRecursively(final String deselectId, final Map<String, PluginUpdateRow> rowMap) {
        for (Map.Entry<String, PluginUpdateRow> entry : rowMap.entrySet()) {
            final PluginUpdateRow candidate = entry.getValue();
            if (candidate.selected() && candidate.requiredDependencies().contains(deselectId)) {
                rowMap.put(entry.getKey(), candidate.withSelected(false));
                deselectDependentsRecursively(entry.getKey(), rowMap);
            }
        }
    }

    /**
     * Downloads and prepares selected plugins into staging files WITHOUT modifying pending.json.
     * Uses strict inspectDownloadedJar verification. On failure or cancel, uncommitted files are deleted.
     */
    public DownloadResult downloadAndPrepare(
            final List<PluginUpdateRow> rows,
            final Optional<PluginVersion> targetApiVersion,
            final AtomicBoolean cancelled) {
        return downloadAndPrepare(rows, targetApiVersion, cancelled::get, new PluginCatalogClient.DownloadControl());
    }

    /**
     * Downloads and prepares the selected plugins while observing a caller-owned download control, so a
     * UI cancel propagates into the transport and uncommitted staging files are removed again.
     *
     * @param rows selected plan rows; non-selected rows are ignored
     * @param targetApiVersion framework API version the plan was computed for
     * @param control download control carrying the cancel signal and progress callbacks
     * @return the download outcome; nothing is written to the pending journal
     */
    public DownloadResult downloadAndPrepare(
            final List<PluginUpdateRow> rows,
            final Optional<PluginVersion> targetApiVersion,
            final PluginCatalogClient.DownloadControl control) {
        return downloadAndPrepare(rows, targetApiVersion, control::isCancelled, control);
    }

    /**
     * Prepares the selected batch and reports aggregate plugin byte progress on the worker thread.
     *
     * @param rows selected plan rows
     * @param targetApiVersion framework API to remain compatible with
     * @param control caller-owned cancellation
     * @param progress aggregate received and total bytes; completion still requires verification
     * @return verified packages without committing an installation
     */
    public DownloadResult downloadAndPrepare(
            final List<PluginUpdateRow> rows,
            final Optional<PluginVersion> targetApiVersion,
            final PluginCatalogClient.DownloadControl control,
            final java.util.function.Consumer<PluginCatalogClient.DownloadProgress> progress) {
        return downloadAndPrepare(rows, targetApiVersion, control::isCancelled, control, progress);
    }

    private DownloadResult downloadAndPrepare(
            final List<PluginUpdateRow> rows,
            final Optional<PluginVersion> targetApiVersion,
            final java.util.function.BooleanSupplier cancelled,
            final PluginCatalogClient.DownloadControl control) {
        return downloadAndPrepare(rows, targetApiVersion, cancelled, control, ignored -> {});
    }

    private DownloadResult downloadAndPrepare(
            final List<PluginUpdateRow> rows,
            final Optional<PluginVersion> targetApiVersion,
            final java.util.function.BooleanSupplier cancelled,
            final PluginCatalogClient.DownloadControl control,
            final java.util.function.Consumer<PluginCatalogClient.DownloadProgress> progress) {
        if (pending.recoveryRequired()) {
            return DownloadResult.failure(
                    "PLUGIN_PENDING_REVIEW_REQUIRED",
                    "Review pending changes in Plugin Management before downloading again.");
        }
        final List<PluginAutoUpdatePlanner.PlannedUpdate> selectedUpdates = rows.stream()
                .filter(PluginUpdateRow::selected)
                .flatMap(r -> r.plannedUpdate().stream())
                .toList();

        if (selectedUpdates.isEmpty()) {
            return DownloadResult.success(List.of());
        }

        final List<Path> tempDownloadFiles = new ArrayList<>();
        final List<PreparedPluginJar> preparedList = new ArrayList<>();
        final long totalBytes = selectedUpdates.stream()
                .mapToLong(update -> update.release().artifact().size())
                .sum();
        long completedBytes = 0;
        progress.accept(new PluginCatalogClient.DownloadProgress(0, totalBytes));

        try {
            Files.createDirectories(stagingPackages);
            final Optional<String> activeCubism = cubismVersion.get();

            for (PluginAutoUpdatePlanner.PlannedUpdate update : selectedUpdates) {
                if (cancelled.getAsBoolean()) {
                    cleanupTemp(tempDownloadFiles, preparedList);
                    return DownloadResult.cancelled();
                }

                final CatalogArtifact artifact = update.release().artifact();
                final Path tempDownload = Files.createTempFile(stagingPackages, "coord-download-", ".jar");
                tempDownloadFiles.add(tempDownload);

                try {
                    final long precedingBytes = completedBytes;
                    client.downloadArtifact(
                            URI.create(artifact.url()),
                            artifact.size(),
                            artifact.sha256(),
                            tempDownload,
                            control,
                            value -> progress.accept(new PluginCatalogClient.DownloadProgress(
                                    precedingBytes + value.receivedBytes(), totalBytes)));
                    completedBytes += artifact.size();
                } catch (IOException downloadError) {
                    cleanupTemp(tempDownloadFiles, preparedList);
                    return cancelled.getAsBoolean()
                            ? DownloadResult.cancelled()
                            : DownloadResult.failure(
                                    "PLUGIN_DOWNLOAD_FAILED",
                                    "Failed to download " + update.pluginId() + ": " + downloadError.getMessage());
                }

                if (cancelled.getAsBoolean()) {
                    cleanupTemp(tempDownloadFiles, preparedList);
                    return DownloadResult.cancelled();
                }

                // Strict verification reusing inspectDownloadedJar
                final RuntimePluginAutoUpdater.JarCheckResult check =
                        RuntimePluginAutoUpdater.inspectDownloadedJar(update, tempDownload, activeCubism);
                if (check != RuntimePluginAutoUpdater.JarCheckResult.VALID) {
                    cleanupTemp(tempDownloadFiles, preparedList);
                    return DownloadResult.failure(
                            "PLUGIN_VERIFICATION_FAILED",
                            "Downloaded JAR failed verification check (" + check + ") for " + update.pluginId());
                }

                // Verify actual descriptor dual API compatibility
                final PluginDescriptor descriptor = readJarDescriptor(tempDownload);
                if (descriptor == null) {
                    cleanupTemp(tempDownloadFiles, preparedList);
                    return DownloadResult.failure(
                            "DESCRIPTOR_PARSE_FAILED", "Failed to parse descriptor from " + update.pluginId());
                }
                final VersionRange apiRange = VersionRange.parse(descriptor.turboismApi());
                if (!apiRange.contains(currentApiVersion)) {
                    cleanupTemp(tempDownloadFiles, preparedList);
                    return DownloadResult.failure(
                            "CURRENT_API_INCOMPATIBLE",
                            "Downloaded artifact " + update.pluginId() + " does not support current API "
                                    + currentApiVersion);
                }
                if (targetApiVersion.isPresent() && !apiRange.contains(targetApiVersion.get())) {
                    cleanupTemp(tempDownloadFiles, preparedList);
                    return DownloadResult.failure(
                            "TARGET_API_INCOMPATIBLE",
                            "Downloaded artifact " + update.pluginId() + " does not support target API "
                                    + targetApiVersion.get());
                }

                // Prepare via LocalPluginJarPreparer
                final LocalPluginJarPreparer preparer = new LocalPluginJarPreparer();
                final LocalPluginJarPreparer.Preparation prep = preparer.prepare(tempDownload, stagingPackages);
                Files.deleteIfExists(tempDownload);
                tempDownloadFiles.remove(tempDownload);

                if (prep instanceof LocalPluginJarPreparer.PreparationRejected rejected) {
                    cleanupTemp(tempDownloadFiles, preparedList);
                    return DownloadResult.failure(
                            rejected.code(),
                            "Plugin " + update.pluginId() + " rejected by preflight: " + rejected.code());
                }

                final PreparedPluginJar prepared = ((LocalPluginJarPreparer.Prepared) prep).value();
                preparedList.add(prepared);
            }

            return DownloadResult.success(preparedList);

        } catch (Exception failure) {
            cleanupTemp(tempDownloadFiles, preparedList);
            return DownloadResult.failure("PREPARE_FAILED", failure.getMessage());
        }
    }

    private record ResolvedPluginMeta(
            String id, String version, String turboismApi, List<ResolvedDependency> requiredDependencies) {}

    private record ResolvedDependency(String id, String version) {}

    /**
     * Checks pre-commit conflicts, verifies dual compatibility of actual descriptors against certified target API,
     * serializes commit under the root lock, and atomically commits the batch to pending.json.
     */
    public CommitResult commitStagedBatch(
            final List<PreparedPluginJar> preparedBatch,
            final PluginVersion certifiedTargetApi,
            final List<CorePluginManagement.PluginInfo> installedPlugins) {
        Objects.requireNonNull(certifiedTargetApi, "certifiedTargetApi");
        Objects.requireNonNull(installedPlugins, "installedPlugins");

        synchronized (PluginUpdateGate.rootLock(home)) {
            try {
                return validateAndCommit(preparedBatch, certifiedTargetApi, installedPlugins);
            } catch (RuntimeException invalid) {
                return CommitResult.conflict("Plugin state could not be verified: " + invalid.getMessage());
            }
        }
    }

    private CommitResult validateAndCommit(
            final List<PreparedPluginJar> preparedBatch,
            final PluginVersion certifiedTargetApi,
            final List<CorePluginManagement.PluginInfo> installedPlugins) {
        final List<PendingPluginOperations.Operation> pendingSnapshot = pending.operations();
        final Set<String> uninstalledIds = new HashSet<>();
        // 1. Re-verify all prepared batch items for dual API compatibility against certifiedTargetApi
        for (PreparedPluginJar prep : preparedBatch) {
            try {
                final VersionRange range = VersionRange.parse(prep.descriptor().turboismApi());
                if (!range.contains(currentApiVersion) || !range.contains(certifiedTargetApi)) {
                    return CommitResult.conflict(
                            "Prepared plugin '" + prep.descriptor().id() + "' is not dual-compatible with target API "
                                    + certifiedTargetApi + " and current API " + currentApiVersion);
                }
            } catch (Exception invalid) {
                return CommitResult.conflict("Corrupt descriptor for prepared plugin "
                        + prep.descriptor().id());
            }
        }

        // 2. Build accurate postUpdateVersions map:
        // Scan actual installed JARs (reading metadata by ID, not assuming <id>.jar)
        final Map<String, Path> installedJarMap = scanInstalledJars(pluginsDirectory);
        final Map<String, ResolvedPluginMeta> activeMetadata = new HashMap<>();
        final Map<String, PluginVersion> postUpdateVersions = new HashMap<>();

        for (Map.Entry<String, Path> entry : installedJarMap.entrySet()) {
            final PluginDescriptor d = readJarDescriptor(entry.getValue());
            if (d != null) {
                final List<ResolvedDependency> reqDeps = d.dependencies().stream()
                        .filter(dep -> "required".equalsIgnoreCase(dep.type()))
                        .map(dep -> new ResolvedDependency(dep.id(), dep.version()))
                        .toList();
                activeMetadata.put(d.id(), new ResolvedPluginMeta(d.id(), d.version(), d.turboismApi(), reqDeps));
                try {
                    postUpdateVersions.put(d.id(), PluginVersion.parse(d.version()));
                } catch (Exception ignored) {
                }
            }
        }

        // Apply existing pending operations, retaining the same snapshot through atomic publication.
        for (PendingPluginOperations.Operation op : pendingSnapshot) {
            if ("UNINSTALL".equals(op.type())) {
                if (preparedBatch.stream().anyMatch(p -> p.descriptor().id().equals(op.pluginId()))) {
                    return CommitResult.conflict("Plugin was uninstalled after planning: " + op.pluginId());
                }
                uninstalledIds.add(op.pluginId());
                postUpdateVersions.remove(op.pluginId());
                activeMetadata.remove(op.pluginId());
            } else if ("INSTALL".equals(op.type())) {
                try {
                    final Path staged = Path.of(op.stagedJar());
                    if (!PluginJarPreflight.matches(
                            staged, op.pluginId(), op.version(), op.descriptorSha256(), op.jarSha256(), op.jarSize())) {
                        return CommitResult.conflict(
                                "Existing pending plugin bytes differ from its recorded intent: " + op.pluginId());
                    }
                    final PluginDescriptor d = readJarDescriptor(staged);
                    if (d == null) {
                        return CommitResult.conflict(
                                "Existing pending plugin '" + op.pluginId() + "' metadata is invalid.");
                    }
                    final List<ResolvedDependency> reqDeps = d.dependencies().stream()
                            .filter(dep -> "required".equalsIgnoreCase(dep.type()))
                            .map(dep -> new ResolvedDependency(dep.id(), dep.version()))
                            .toList();
                    activeMetadata.put(d.id(), new ResolvedPluginMeta(d.id(), d.version(), d.turboismApi(), reqDeps));
                    postUpdateVersions.put(op.pluginId(), PluginVersion.parse(d.version()));
                } catch (Exception ex) {
                    return CommitResult.conflict(
                            "Error validating existing pending plugin '" + op.pluginId() + "': " + ex.getMessage());
                }
            }
        }

        // Apply preparedBatch with implicit downgrade check
        for (PreparedPluginJar prep : preparedBatch) {
            final String id = prep.descriptor().id();
            final PluginVersion currentVer = postUpdateVersions.get(id);
            try {
                final PluginVersion targetVer =
                        PluginVersion.parse(prep.descriptor().version());
                if (currentVer != null && targetVer.compareTo(currentVer) < 0) {
                    return CommitResult.conflict(
                            "Cannot downgrade plugin '" + id + "' from " + currentVer + " to " + targetVer);
                }
                postUpdateVersions.put(id, targetVer);
                final List<ResolvedDependency> reqDeps = prep.descriptor().dependencies().stream()
                        .filter(dep -> "required".equalsIgnoreCase(dep.type()))
                        .map(dep -> new ResolvedDependency(dep.id(), dep.version()))
                        .toList();
                activeMetadata.put(
                        id,
                        new ResolvedPluginMeta(
                                id,
                                prep.descriptor().version(),
                                prep.descriptor().turboismApi(),
                                reqDeps));
            } catch (IllegalArgumentException invalidVer) {
                return CommitResult.conflict("Invalid target version for plugin " + id + ": "
                        + prep.descriptor().version());
            }
        }

        // 3. Verify all existing unselected pending operations: must support certifiedTargetApi
        for (PendingPluginOperations.Operation op : pendingSnapshot) {
            if ("INSTALL".equals(op.type())) {
                final boolean inBatch =
                        preparedBatch.stream().anyMatch(p -> p.descriptor().id().equals(op.pluginId()));
                if (!inBatch) {
                    final ResolvedPluginMeta meta = activeMetadata.get(op.pluginId());
                    if (meta == null
                            || !VersionRange.parse(meta.turboismApi()).contains(certifiedTargetApi)
                            || !VersionRange.parse(meta.turboismApi()).contains(currentApiVersion)) {
                        return CommitResult.conflict("Existing pending plugin '" + op.pluginId() + "' (" + op.version()
                                + ") does not support target framework API " + certifiedTargetApi
                                + ". Please clear or update it before proceeding.");
                    }
                }
            }
        }

        // Only enabled plugins participate in the loading graph; never silently enable a dependency.
        final Set<String> enabledIds = installedPlugins.stream()
                .filter(CoordinatedPluginUpdateService::isEnabled)
                .filter(info -> !uninstalledIds.contains(info.id()))
                .map(CorePluginManagement.PluginInfo::id)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        postUpdateVersions.put(CorePluginManagement.CORE_PLUGIN_ID, certifiedTargetApi);
        enabledIds.add(CorePluginManagement.CORE_PLUGIN_ID);
        // 4. Verify all currently enabled installed plugins:
        for (CorePluginManagement.PluginInfo info : installedPlugins) {
            if (info.core() || !enabledIds.contains(info.id())) continue;

            // Enabled plugin must not be staged for uninstall
            if (!postUpdateVersions.containsKey(info.id())) {
                return CommitResult.conflict("Enabled plugin '" + info.name() + "' is staged for uninstall.");
            }

            // If not updated in batch, verify actual descriptor supports certifiedTargetApi
            final boolean updatedInBatch =
                    preparedBatch.stream().anyMatch(p -> p.descriptor().id().equals(info.id()));
            if (!updatedInBatch) {
                final ResolvedPluginMeta meta = activeMetadata.get(info.id());
                if (meta == null) {
                    return CommitResult.conflict("Installed plugin '" + info.name()
                            + "' archive could not be verified from plugins directory.");
                }
                if (!VersionRange.parse(meta.turboismApi()).contains(certifiedTargetApi)) {
                    return CommitResult.conflict("Enabled plugin '" + info.name() + "' (" + info.version()
                            + ") does not support target framework API " + certifiedTargetApi
                            + ". Please update or disable it before proceeding.");
                }
            }
        }

        // 5. Verify dependency satisfaction across all active post-update descriptors
        for (Map.Entry<String, ResolvedPluginMeta> entry : activeMetadata.entrySet()) {
            final String pluginId = entry.getKey();
            final ResolvedPluginMeta meta = entry.getValue();
            if (!enabledIds.contains(pluginId)) continue;

            for (ResolvedDependency dep : meta.requiredDependencies()) {
                if (!enabledIds.contains(dep.id())) {
                    return CommitResult.conflict("Required dependency is disabled or removed: " + dep.id());
                }
                final PluginVersion avail = postUpdateVersions.get(dep.id());
                if (avail == null) {
                    return CommitResult.conflict("Plugin '" + pluginId + "' requires dependency '" + dep.id()
                            + "' which is not available in the post-update state.");
                }
                if (!VersionRange.parse(dep.version()).contains(avail)) {
                    return CommitResult.conflict("Plugin '" + pluginId + "' requires dependency '" + dep.id() + "' "
                            + dep.version() + ", but available version is " + avail);
                }
            }
        }

        // Even a framework-only batch must detect changed pending state before it is acknowledged.
        final PendingPluginOperations.StagedBatchResult batchResult =
                pending.stageBatch(preparedBatch, pendingSnapshot);
        if (!batchResult.accepted()) {
            return CommitResult.failed(batchResult.code(), "Staging batch failed: " + batchResult.code());
        }
        return CommitResult.success(preparedBatch);
    }

    /** Safely cleans up uncommitted prepared packages using discardIfUnreferenced. */
    public void discardUncommitted(final List<PreparedPluginJar> preparedBatch) {
        if (preparedBatch == null) return;
        for (PreparedPluginJar prep : preparedBatch) {
            if (prep != null && prep.stagedJar() != null) {
                pending.discardIfUnreferenced(prep.stagedJar());
            }
        }
    }

    private void cleanupTemp(final List<Path> tempDownloads, final List<PreparedPluginJar> preparedList) {
        for (Path p : tempDownloads) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
            }
        }
        discardUncommitted(preparedList);
    }

    /**
     * Maps plugin ids to the regular JAR files immediately inside the supplied directory.
     *
     * <p>The directory and file checks use {@code NOFOLLOW_LINKS}; unreadable descriptors are skipped. A
     * plugin id claimed by two JARs is rejected instead of silently picking one, so an ambiguous install
     * can never be updated on a guess.</p>
     *
     * @param pluginsDir directory to scan; {@code null} or a missing directory yields an empty map
     * @return plugin id to JAR path beneath the supplied directory; empty when nothing is installed
     * @throws IllegalStateException when directory enumeration fails or two JARs declare the same plugin id
     */
    public static Map<String, Path> scanInstalledJars(final Path pluginsDir) {
        if (pluginsDir == null || !Files.isDirectory(pluginsDir, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }
        final Map<String, Path> result = new HashMap<>();
        try (var entries = Files.list(pluginsDir)) {
            for (Path path : entries.filter(c -> Files.isRegularFile(c, LinkOption.NOFOLLOW_LINKS))
                    .filter(c -> c.getFileName().toString().endsWith(".jar"))
                    .toList()) {
                final PluginDescriptor d = readJarDescriptor(path);
                if (d != null) {
                    if (result.putIfAbsent(d.id(), path) != null) {
                        throw new IllegalStateException("Ambiguous installed plugin ID: " + d.id());
                    }
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not scan installed plugins", failure);
        }
        return result;
    }

    private static PluginDescriptor readJarDescriptor(final Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            final JarEntry entry = jar.getJarEntry("META-INF/turboism/plugin.json");
            if (entry == null) return null;
            try (InputStream in = jar.getInputStream(entry)) {
                return new PluginDescriptorParser().parse(in);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
