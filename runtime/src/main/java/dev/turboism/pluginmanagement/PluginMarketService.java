package dev.turboism.pluginmanagement;

import dev.turboism.core.version.PluginVersion;
import dev.turboism.core.version.VersionRange;
import dev.turboism.distribution.LocalPluginJarPreparer;
import dev.turboism.distribution.PreparedPluginJar;
import dev.turboism.internal.core.CorePluginManagement.OperationResult;
import dev.turboism.internal.core.CorePluginManagement.PluginInfo;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogPlugin;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogRelease;
import dev.turboism.pluginmanagement.PluginCatalogClient.CatalogFetchResult;
import dev.turboism.pluginmanagement.PluginCatalogVerifier.PluginCatalogVerificationException;
import dev.turboism.preview.PluginHotReloadCoordinator;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Service for browsing the verified Plugin Directory catalog v2, checking compatibility,
 * and performing verified installs and updates.
 */
public final class PluginMarketService implements AutoCloseable {

    public enum StatusType {
        NOT_INSTALLED,
        INSTALLED,
        UPDATE_AVAILABLE,
        INCOMPATIBLE
    }

    public record PluginMarketStatus(
            StatusType type,
            Optional<String> installedVersion,
            Optional<CatalogRelease> targetRelease,
            Optional<String> incompatibleReason) {}

    private final Path home;
    private final Path pluginsDirectory;
    private final Path stagingPackages;
    private final PluginCatalogClient client;
    private final PluginCatalogVerifier verifier;
    private final PluginCatalogStateStore stateStore;
    private final PendingPluginOperations pending;
    private final RuntimePluginManagementService managementService;
    private final Supplier<Optional<String>> cubismVersionSupplier;
    private final PluginVersion turboismApiVersion;
    private final Consumer<String> logSink;
    private final ExecutorService marketExecutor;
    private volatile PluginHotReloadCoordinator hotReloadCoordinator;
    private volatile PluginCatalog currentCatalog;

    public PluginMarketService(
            final Path home,
            final PluginCatalogClient client,
            final PluginCatalogVerifier verifier,
            final PluginCatalogStateStore stateStore,
            final PendingPluginOperations pending,
            final RuntimePluginManagementService managementService,
            final Supplier<Optional<String>> cubismVersionSupplier,
            final PluginVersion turboismApiVersion,
            final Consumer<String> logSink) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.pluginsDirectory = this.home.resolve("plugins");
        this.stagingPackages = this.home.resolve("state/runtime/plugin-management/packages");
        this.client = Objects.requireNonNull(client, "client");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.pending = Objects.requireNonNull(pending, "pending");
        this.managementService = Objects.requireNonNull(managementService, "managementService");
        this.cubismVersionSupplier = Objects.requireNonNull(cubismVersionSupplier, "cubismVersionSupplier");
        this.turboismApiVersion = Objects.requireNonNull(turboismApiVersion, "turboismApiVersion");
        this.logSink = Objects.requireNonNull(logSink, "logSink");
        this.marketExecutor = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "Turboism-PluginMarket");
            t.setDaemon(true);
            return t;
        });
    }

    public void bindHotReloadCoordinator(final PluginHotReloadCoordinator coordinator) {
        this.hotReloadCoordinator = coordinator;
    }

    public PluginCatalog currentCatalog() {
        return currentCatalog;
    }

    /**
     * Loads the verified v2 catalog: first validates cached state if available,
     * then attempts ETag revalidation over HTTPS. Never yields unverified data.
     */
    public CompletionStage<PluginCatalog> loadCatalog(final boolean forceRefresh) {
        return CompletableFuture.supplyAsync(
                () -> {
                    final PluginCatalogStateStore.State persisted = stateStore.read();
                    if (!forceRefresh
                            && persisted.cachedCatalogBytes().isPresent()
                            && persisted.cachedSignatureBytes().isPresent()) {
                        try {
                            final PluginCatalog cached = verifier.verify(
                                    persisted.cachedCatalogBytes().get(),
                                    persisted.cachedSignatureBytes().get());
                            currentCatalog = cached;
                        } catch (PluginCatalogVerificationException e) {
                            logSink.accept("Cached catalog verification failed: " + e.getMessage());
                        }
                    }

                    final Optional<String> etag = forceRefresh ? Optional.empty() : persisted.etag();
                    final CatalogFetchResult fetch;
                    try {
                        fetch = client.fetchCatalog(etag);
                    } catch (IOException netError) {
                        if (currentCatalog != null) {
                            return currentCatalog;
                        }
                        throw new IllegalStateException(
                                "Failed to fetch plugin catalog: " + netError.getMessage(), netError);
                    }

                    final byte[] catalogBytes;
                    final byte[] signatureBytes;
                    final String newEtag;
                    if (fetch instanceof CatalogFetchResult.NotModified notModified) {
                        if (persisted.cachedCatalogBytes().isEmpty()
                                || persisted.cachedSignatureBytes().isEmpty()) {
                            throw new IllegalStateException("Catalog 304 response with empty local cache");
                        }
                        catalogBytes = persisted.cachedCatalogBytes().get();
                        signatureBytes = persisted.cachedSignatureBytes().get();
                        newEtag = notModified.etag().orElse(persisted.etag().orElse(""));
                    } else if (fetch instanceof CatalogFetchResult.Fetched fetched) {
                        catalogBytes = fetched.catalogBytes();
                        signatureBytes = fetched.signatureBytes();
                        newEtag = fetched.etag().orElse("");
                    } else {
                        throw new IllegalStateException("Unexpected fetch result type");
                    }

                    try {
                        final PluginCatalog verified = verifier.verifyAndAdvance(
                                catalogBytes, signatureBytes, stateStore, Optional.of(newEtag));
                        currentCatalog = verified;
                        return verified;
                    } catch (PluginCatalogVerificationException verifyError) {
                        if (currentCatalog != null) {
                            return currentCatalog;
                        }
                        throw new IllegalStateException(
                                "Catalog verification failed: " + verifyError.getMessage(), verifyError);
                    }
                },
                marketExecutor);
    }

    /**
     * Resolves the market status of a catalog plugin against the currently installed runtime plugins.
     */
    public PluginMarketStatus resolveStatus(final CatalogPlugin plugin) {
        final List<PluginInfo> installedList = managementService.plugins();
        final PluginInfo installed = installedList.stream()
                .filter(p -> p.id().equals(plugin.id()))
                .findFirst()
                .orElse(null);

        final Optional<String> activeCubism = cubismVersionSupplier.get();
        final List<CatalogRelease> compatibleReleases = plugin.releases().stream()
                .filter(CatalogRelease::isActive)
                .filter(r -> isCompatible(r, activeCubism))
                .sorted(Comparator.comparing((CatalogRelease r) -> PluginVersion.parse(r.version()))
                        .reversed())
                .toList();

        if (compatibleReleases.isEmpty()) {
            final CatalogRelease latestActive = plugin.releases().stream()
                    .filter(CatalogRelease::isActive)
                    .max(Comparator.comparing(r -> PluginVersion.parse(r.version())))
                    .orElse(null);
            final String reason = latestActive != null
                    ? incompatibilityReason(latestActive, activeCubism)
                    : "No compatible releases available";
            return new PluginMarketStatus(
                    StatusType.INCOMPATIBLE,
                    installed != null ? Optional.of(installed.version()) : Optional.empty(),
                    Optional.ofNullable(latestActive),
                    Optional.of(reason));
        }

        final CatalogRelease bestRelease = compatibleReleases.get(0);
        if (installed != null) {
            final PluginVersion currentVer = PluginVersion.parse(installed.version());
            final PluginVersion bestVer = PluginVersion.parse(bestRelease.version());
            if (bestVer.compareTo(currentVer) > 0) {
                return new PluginMarketStatus(
                        StatusType.UPDATE_AVAILABLE,
                        Optional.of(installed.version()),
                        Optional.of(bestRelease),
                        Optional.empty());
            }
            return new PluginMarketStatus(
                    StatusType.INSTALLED, Optional.of(installed.version()), Optional.of(bestRelease), Optional.empty());
        }

        return new PluginMarketStatus(
                StatusType.NOT_INSTALLED, Optional.empty(), Optional.of(bestRelease), Optional.empty());
    }

    private boolean isCompatible(final CatalogRelease release, final Optional<String> activeCubism) {
        if (!VersionRange.parse(release.turboismApi()).contains(turboismApiVersion)) {
            return false;
        }
        if (release.requiresCubism()) {
            if (activeCubism.isEmpty()) {
                return false;
            }
            if (!release.cubismVersions().contains(activeCubism.get())) {
                return false;
            }
        }
        return true;
    }

    private String incompatibilityReason(final CatalogRelease release, final Optional<String> activeCubism) {
        if (!VersionRange.parse(release.turboismApi()).contains(turboismApiVersion)) {
            return "Requires Turboism API " + release.turboismApi() + " (current is " + turboismApiVersion + ")";
        }
        if (release.requiresCubism()) {
            if (activeCubism.isEmpty()) {
                return "Requires Cubism host";
            }
            if (!release.cubismVersions().contains(activeCubism.get())) {
                return "Cubism " + activeCubism.get() + " is not in supported versions";
            }
        }
        return "Release is incompatible";
    }

    /**
     * Downloads, strictly verifies, and installs or updates a catalog release.
     * Uses hot-load/hot-reload where safe, and falls back to staging otherwise.
     */
    public CompletionStage<OperationResult> installOrUpdate(final CatalogPlugin plugin, final CatalogRelease release) {
        return CompletableFuture.supplyAsync(
                () -> {
                    final Optional<String> activeCubism = cubismVersionSupplier.get();
                    if (!isCompatible(release, activeCubism)) {
                        return OperationResult.rejected(
                                "PLUGIN_INCOMPATIBLE", incompatibilityReason(release, activeCubism));
                    }

                    final Path tempDownload;
                    try {
                        Files.createDirectories(stagingPackages);
                        tempDownload = Files.createTempFile(stagingPackages, "market-download-", ".jar");
                    } catch (IOException e) {
                        return OperationResult.rejected(
                                "PLUGIN_DOWNLOAD_IO_FAILED", "Failed to create staging download file");
                    }

                    try {
                        client.downloadArtifact(
                                URI.create(release.artifact().url()),
                                release.artifact().size(),
                                release.artifact().sha256(),
                                tempDownload);
                    } catch (IOException downloadError) {
                        try {
                            Files.deleteIfExists(tempDownload);
                        } catch (IOException ignored) {
                        }
                        return OperationResult.rejected(
                                "PLUGIN_DOWNLOAD_FAILED",
                                "Failed to download plugin package: " + downloadError.getMessage());
                    }

                    // Inspect and prepare staged JAR
                    final LocalPluginJarPreparer preparer = new LocalPluginJarPreparer();
                    final LocalPluginJarPreparer.Preparation prep = preparer.prepare(tempDownload, stagingPackages);
                    try {
                        Files.deleteIfExists(tempDownload);
                    } catch (IOException ignored) {
                    }

                    if (prep instanceof LocalPluginJarPreparer.PreparationRejected rejected) {
                        return OperationResult.rejected(
                                rejected.code(), "Staged package rejected by security preflight");
                    }

                    final PreparedPluginJar prepared = ((LocalPluginJarPreparer.Prepared) prep).value();
                    if (!prepared.descriptorSha256()
                            .equalsIgnoreCase(release.artifact().descriptorSha256())) {
                        try {
                            Files.deleteIfExists(prepared.stagedJar());
                        } catch (IOException ignored) {
                        }
                        return OperationResult.rejected(
                                "DESCRIPTOR_SHA256_MISMATCH", "Embedded descriptor SHA-256 does not match catalog pin");
                    }

                    final boolean hotSafe = release.hotReload()
                            && hotReloadCoordinator != null
                            && hotReloadCoordinator.isHotReloadSafe(prepared.stagedJar());

                    if (hotSafe) {
                        final Path targetJar = pluginsDirectory.resolve(plugin.id() + ".jar");
                        final boolean wasInstalled = Files.isRegularFile(targetJar, LinkOption.NOFOLLOW_LINKS);
                        final Path backupJar = wasInstalled
                                ? stagingPackages.resolve(".backup-" + plugin.id() + "-" + UUID.randomUUID() + ".jar")
                                : null;

                        try {
                            Files.createDirectories(pluginsDirectory);
                            if (wasInstalled && backupJar != null) {
                                Files.copy(targetJar, backupJar, StandardCopyOption.REPLACE_EXISTING);
                            }

                            // Attempt file replacement
                            try {
                                Files.copy(prepared.stagedJar(), targetJar, StandardCopyOption.REPLACE_EXISTING);
                            } catch (IOException lockException) {
                                // Windows file lock or permission error: fall back to staging!
                                logSink.accept("File replacement failed (likely locked); falling back to staging: "
                                        + lockException.getMessage());
                                if (backupJar != null) {
                                    Files.deleteIfExists(backupJar);
                                }
                                return stageForRestart(prepared);
                            }

                            if (wasInstalled) {
                                final var reloadResult =
                                        hotReloadCoordinator.hotReload(plugin.id(), targetJar, backupJar);
                                if (reloadResult.succeeded()) {
                                    if (backupJar != null) Files.deleteIfExists(backupJar);
                                    Files.deleteIfExists(prepared.stagedJar());
                                    return OperationResult.accepted(
                                            "PLUGIN_HOT_APPLIED", "Plugin updated and applied immediately.");
                                } else if (reloadResult.rolledBack()) {
                                    // Rollback restored previous generation
                                    if (backupJar != null) {
                                        Files.copy(backupJar, targetJar, StandardCopyOption.REPLACE_EXISTING);
                                        Files.deleteIfExists(backupJar);
                                    }
                                    return stageForRestart(prepared);
                                } else {
                                    if (backupJar != null) Files.deleteIfExists(backupJar);
                                    return stageForRestart(prepared);
                                }
                            } else {
                                final var loadResult = hotReloadCoordinator.hotLoad(targetJar);
                                if (loadResult.succeeded()) {
                                    Files.deleteIfExists(prepared.stagedJar());
                                    return OperationResult.accepted(
                                            "PLUGIN_HOT_APPLIED", "Plugin installed and applied immediately.");
                                } else {
                                    Files.deleteIfExists(targetJar);
                                    return stageForRestart(prepared);
                                }
                            }
                        } catch (Exception e) {
                            logSink.accept(
                                    "Hot lifecycle operation failed; falling back to staging: " + e.getMessage());
                            if (backupJar != null) {
                                try {
                                    Files.deleteIfExists(backupJar);
                                } catch (IOException ignored) {
                                }
                            }
                            return stageForRestart(prepared);
                        }
                    } else {
                        return stageForRestart(prepared);
                    }
                },
                marketExecutor);
    }

    private OperationResult stageForRestart(final PreparedPluginJar prepared) {
        final var staged = pending.stage(prepared.stagedJar());
        if (staged.accepted()) {
            return OperationResult.accepted(
                    staged.code(), "Plugin installation is pending; restart Cubism to apply it.");
        }
        return OperationResult.rejected(staged.code(), "Failed to stage plugin package");
    }

    /**
     * Resolves localized name and summary for the specified catalog plugin using the given display locale.
     */
    public static CatalogPluginLocalized localizePlugin(final CatalogPlugin plugin, final Locale locale) {
        final Map<String, PluginCatalog.CatalogLocalization> locs = plugin.localizations();
        final String tag = locale.toLanguageTag();
        final String lang = locale.getLanguage();

        PluginCatalog.CatalogLocalization found = locs.get(tag);
        if (found == null && "zh".equals(lang)) {
            final String country = locale.getCountry();
            if ("TW".equalsIgnoreCase(country) || "HK".equalsIgnoreCase(country) || "MO".equalsIgnoreCase(country)) {
                found = locs.get("zh-Hant");
            } else {
                found = locs.get("zh-Hans");
            }
        }
        if (found == null) {
            found = locs.get(lang);
        }
        if (found == null) {
            found = locs.get("en");
        }
        final String name = found != null ? found.name() : plugin.name();
        final String summary = found != null ? found.summary() : plugin.summary();
        return new CatalogPluginLocalized(name, summary);
    }

    public record CatalogPluginLocalized(String name, String summary) {}

    @Override
    public void close() {
        marketExecutor.shutdownNow();
    }
}
