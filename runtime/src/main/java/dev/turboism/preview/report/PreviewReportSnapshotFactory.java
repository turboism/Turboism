package dev.turboism.preview.report;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.i18n.RuntimePluginLocalization;
import dev.turboism.preview.LocalPluginRuntime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Converts neutral runtime evidence into the four closed preview report documents. */
public final class PreviewReportSnapshotFactory {

    private static final Set<String> VERIFIED_PROJECT_CAPABILITIES = Set.of(
        "cubism.project.read",
        "cubism.workspace.read"
    );

    private PreviewReportSnapshotFactory() {
    }

    public static Map<PreviewReportType, ObjectNode> create(
        final String runtimeId,
        final Instant createdAt,
        final Path home,
        final HostSession.State hostState,
        final Path hostArtifact,
        final Path verificationRecord,
        final LocalPluginRuntime.LoadReport loadReport,
        final List<LocalPluginRuntime.LoadedPluginSummary> summaries,
        final boolean stopped
    ) {
        Objects.requireNonNull(loadReport, "loadReport");
        final List<LocalPluginRuntime.LoadedPluginSummary> neutralSummaries =
            List.copyOf(Objects.requireNonNull(summaries, "summaries"));
        final EnumMap<PreviewReportType, ObjectNode> reports =
            new EnumMap<>(PreviewReportType.class);
        reports.put(
            PreviewReportType.PREVIEW_RUNTIME,
            previewRuntime(
                runtimeId,
                createdAt,
                hostState,
                hostArtifact,
                neutralSummaries,
                stopped
            )
        );
        reports.put(
            PreviewReportType.PLUGIN_LOAD,
            pluginLoad(runtimeId, createdAt, home, loadReport, neutralSummaries)
        );
        reports.put(
            PreviewReportType.CAPABILITY,
            capability(
                runtimeId,
                createdAt,
                home,
                hostState,
                verificationRecord,
                neutralSummaries,
                stopped
            )
        );
        reports.put(
            PreviewReportType.I18N,
            i18n(runtimeId, createdAt, neutralSummaries)
        );
        return Map.copyOf(reports);
    }

    private static ObjectNode previewRuntime(
        final String runtimeId,
        final Instant createdAt,
        final HostSession.State hostState,
        final Path hostArtifact,
        final List<LocalPluginRuntime.LoadedPluginSummary> summaries,
        final boolean stopped
    ) {
        final ObjectNode report = PreviewReportDocuments.emptyReport(
            PreviewReportType.PREVIEW_RUNTIME,
            runtimeId,
            createdAt
        );
        final ObjectNode payload = (ObjectNode) report.get("payload");
        final ObjectNode host = (ObjectNode) payload.get("host");
        host.put("version", hostState == HostSession.State.ACTIVE ? "5.3.02" : "UNKNOWN");
        host.put("identityState", switch (hostState) {
            case ACTIVE -> "MATCHED";
            case FAILED -> "MISMATCHED";
            case SAFE_MODE -> "UNKNOWN";
            case CLOSED -> "NOT_APPLICABLE";
        });
        fileDigest(hostArtifact).ifPresent(digest -> {
            host.put("artifactSha256", digest.sha256());
            host.put("artifactSizeBytes", digest.size());
        });
        payload.put("adapterState", switch (hostState) {
            case ACTIVE -> stopped ? "SHUTDOWN" : "READY";
            case FAILED -> "FAILED";
            case SAFE_MODE -> "UNAVAILABLE";
            case CLOSED -> "SHUTDOWN";
        });
        payload.put(
            "runtimeState",
            stopped ? "STOPPED" : hostState == HostSession.State.FAILED ? "DEGRADED" : "RUNNING"
        );

        final long attempted = stopped ? summaries.size() : 0;
        final long succeeded = stopped
            ? summaries.stream().filter(summary -> summary.unloadState().equals("SUCCEEDED")).count()
            : 0;
        final long failed = stopped ? attempted - succeeded : 0;
        payload.set(
            "shutdownCounts",
            PreviewReportDocuments.shutdownCounts(attempted, succeeded, failed, 0)
        );
        final ObjectNode cleanup = PreviewReportDocuments.emptyCleanupCounts();
        if (stopped) {
            cleanup.put(
                "scopesClosed",
                summaries.stream().filter(summary ->
                    summary.scopeCleanupState().equals("SUCCEEDED")
                ).count()
            );
            cleanup.put(
                "classloadersClosed",
                summaries.stream().filter(summary ->
                    summary.classloaderCleanupState().equals("SUCCEEDED")
                ).count()
            );
            cleanup.put(
                "failures",
                summaries.stream().mapToLong(summary -> summary.failures().size()).sum()
            );
        }
        payload.set("cleanupCounts", cleanup);
        return report;
    }

    private static ObjectNode pluginLoad(
        final String runtimeId,
        final Instant createdAt,
        final Path home,
        final LocalPluginRuntime.LoadReport loadReport,
        final List<LocalPluginRuntime.LoadedPluginSummary> summaries
    ) {
        final ObjectNode report = PreviewReportDocuments.emptyReport(
            PreviewReportType.PLUGIN_LOAD,
            runtimeId,
            createdAt
        );
        final ArrayNode plugins = (ArrayNode) report.path("payload").path("plugins");
        for (LocalPluginRuntime.LoadedPluginSummary summary : summaries.stream()
            .sorted(Comparator.comparing(LocalPluginRuntime.LoadedPluginSummary::id))
            .toList()) {
            final ObjectNode entry = PreviewReportDocuments.pluginLoadEntry(
                summary.id(),
                relativeArtifact(home, summary.jar()),
                fileDigest(summary.jar()).map(FileDigest::sha256).orElse(null),
                "DISCOVERED",
                "RESOLVED",
                lifecycle(summary.state().name()),
                false
            );
            entry.put("disableState", summary.disableState());
            entry.put("shutdownState", summary.shutdownState());
            entry.put("unloadState", summary.unloadState());
            entry.put("scopeCleanupState", summary.scopeCleanupState());
            entry.put("classloaderCleanupState", summary.classloaderCleanupState());
            final ArrayNode failures = (ArrayNode) entry.get("failures");
            for (LocalPluginRuntime.PluginSummaryFailure failure : summary.failures()) {
                failures.add(PreviewReportDocuments.failure(
                    failure.code(),
                    "ERROR",
                    failure.phase(),
                    summary.id(),
                    null,
                    null,
                    failure.message(),
                    null,
                    1
                ));
            }
            plugins.add(entry);
        }
        for (LocalPluginRuntime.PluginFailure failure : loadReport.failures().stream()
            .sorted(Comparator.comparing(LocalPluginRuntime.PluginFailure::pluginId)
                .thenComparing(LocalPluginRuntime.PluginFailure::code))
            .toList()) {
            plugins.add(failedPluginEntry(home, failure));
        }
        return report;
    }

    private static ObjectNode failedPluginEntry(
        final Path home,
        final LocalPluginRuntime.PluginFailure failure
    ) {
        final boolean dependency = failure.code().contains("DEPENDENCY");
        final boolean badNeighbor = failure.code().equals("DUPLICATE_PLUGIN_ID");
        final boolean invalidDescriptor = failure.code().contains("DESCRIPTOR")
            || failure.code().contains("JAR_READ")
            || failure.code().equals("TURBOISM_API_INCOMPATIBLE");
        final String lifecycle = dependency
            ? "DEPENDENCY_FAILED"
            : invalidDescriptor ? "INVALID_DESCRIPTOR" : lifecycle(failure.code());
        final String relative = Files.isRegularFile(failure.jar())
            ? relativeArtifact(home, failure.jar())
            : null;
        final ObjectNode entry = PreviewReportDocuments.pluginLoadEntry(
            sanitizePluginId(failure.pluginId()),
            relative,
            relative == null
                ? null
                : fileDigest(failure.jar()).map(FileDigest::sha256).orElse(null),
            badNeighbor
                ? "BAD_NEIGHBOR"
                : invalidDescriptor ? "INVALID_DESCRIPTOR" : "DISCOVERED",
            dependency ? "FAILED" : invalidDescriptor ? "NOT_EVALUATED" : "RESOLVED",
            lifecycle,
            badNeighbor
        );
        entry.put("disableState", "NOT_REQUIRED");
        entry.put("shutdownState", "NOT_REQUIRED");
        entry.put("unloadState", "NOT_REQUIRED");
        entry.put("scopeCleanupState", "NOT_REQUIRED");
        entry.put("classloaderCleanupState", "NOT_REQUIRED");
        ((ArrayNode) entry.get("failures")).add(PreviewReportDocuments.failure(
            stableCode(failure.code()),
            "ERROR",
            dependency ? "dependency" : "load",
            sanitizePluginId(failure.pluginId()),
            null,
            null,
            stableFailureMessage(failure.code()),
            relative,
            1
        ));
        return entry;
    }

    private static ObjectNode capability(
        final String runtimeId,
        final Instant createdAt,
        final Path home,
        final HostSession.State hostState,
        final Path verificationRecord,
        final List<LocalPluginRuntime.LoadedPluginSummary> summaries,
        final boolean stopped
    ) {
        final ObjectNode report = PreviewReportDocuments.emptyReport(
            PreviewReportType.CAPABILITY,
            runtimeId,
            createdAt
        );
        final ArrayNode entries = (ArrayNode) report.path("payload").path("capabilities");
        final String recordPath = relativeArtifact(home, verificationRecord);
        final String recordDigest = fileDigest(verificationRecord)
            .map(FileDigest::sha256)
            .orElse(null);
        for (LocalPluginRuntime.LoadedPluginSummary summary : summaries.stream()
            .sorted(Comparator.comparing(LocalPluginRuntime.LoadedPluginSummary::id))
            .toList()) {
            for (String capabilityId : summary.capabilities().stream().sorted().toList()) {
                final String permissionId = permissionFor(summary.permissionIds(), capabilityId);
                final boolean verifiedProject = VERIFIED_PROJECT_CAPABILITIES.contains(capabilityId);
                final boolean runtimeAvailable = verifiedProject
                    && hostState == HostSession.State.ACTIVE
                    && !stopped
                    && summary.state().name().equals("ENABLED");
                final String availability = verifiedProject
                    ? runtimeAvailable ? "AVAILABLE" : "UNAVAILABLE"
                    : "UNKNOWN";
                final ObjectNode entry = PreviewReportDocuments.capabilityEntry(
                    summary.id(),
                    capabilityId,
                    capabilityId,
                    permissionId,
                    availability,
                    permissionId == null ? "NOT_DECLARED" : "GRANTED",
                    "NONE"
                );
                final ArrayNode evidence = (ArrayNode) entry.get("evidence");
                if (verifiedProject && recordPath != null && recordDigest != null) {
                    evidence.add(PreviewReportDocuments.evidence(
                        runtimeAvailable ? "RUNTIME_OBSERVED" : "STATIC_VERIFIED",
                        availability,
                        runtimeAvailable
                            ? "Exact Cubism 5.3.02 adapter was connected for this runtime session."
                            : "Exact Cubism 5.3.02 static record exists; runtime availability was not established.",
                        recordPath,
                        recordDigest
                    ));
                } else {
                    evidence.add(PreviewReportDocuments.evidence(
                        "DECLARED",
                        "UNKNOWN",
                        "Capability is declared by the plugin descriptor; support is not elevated.",
                        null,
                        null
                    ));
                }
                entries.add(entry);
            }
        }
        return report;
    }

    private static ObjectNode i18n(
        final String runtimeId,
        final Instant createdAt,
        final List<LocalPluginRuntime.LoadedPluginSummary> summaries
    ) {
        final ObjectNode report = PreviewReportDocuments.emptyReport(
            PreviewReportType.I18N,
            runtimeId,
            createdAt
        );
        final ArrayNode plugins = (ArrayNode) report.path("payload").path("plugins");
        for (LocalPluginRuntime.LoadedPluginSummary summary : summaries.stream()
            .sorted(Comparator.comparing(LocalPluginRuntime.LoadedPluginSummary::id))
            .toList()) {
            final RuntimePluginLocalization.ReportSnapshot snapshot = summary.localization();
            final ObjectNode entry = PreviewReportDocuments.i18nPluginEntry(
                snapshot.pluginId(),
                snapshot.localeSource(),
                snapshot.requestedLocale(),
                snapshot.normalizedLocale(),
                snapshot.fallbackChain()
            );
            final ArrayNode catalogs = (ArrayNode) entry.get("catalogs");
            for (RuntimePluginLocalization.CatalogSnapshot catalog : snapshot.catalogs()) {
                catalogs.add(PreviewReportDocuments.catalogEntry(
                    catalog.locale(),
                    catalog.state(),
                    catalog.keyCount()
                ));
            }
            final ArrayNode missing = (ArrayNode) entry.get("missingKeys");
            for (RuntimePluginLocalization.MissingKeySnapshot value : snapshot.missingKeys()) {
                final ObjectNode item = PreviewReportDocuments.JSON.createObjectNode();
                item.put("key", value.key());
                item.put("locale", value.locale());
                item.put("marker", value.marker());
                item.put("count", value.count());
                missing.add(item);
            }
            final ArrayNode malformed = (ArrayNode) entry.get("malformedPatterns");
            for (RuntimePluginLocalization.MalformedPatternSnapshot value
                : snapshot.malformedPatterns()) {
                final ObjectNode item = PreviewReportDocuments.JSON.createObjectNode();
                item.put("key", value.key());
                item.put("locale", value.locale());
                item.put("code", value.code());
                item.put("marker", value.marker());
                item.put("count", value.count());
                malformed.add(item);
            }
            final ObjectNode suppression = (ObjectNode) entry.get("suppression");
            suppression.put("missingWarningsEmitted", snapshot.missingWarningsEmitted());
            suppression.put("missingWarningsSuppressed", snapshot.missingWarningsSuppressed());
            suppression.put("malformedWarningsEmitted", snapshot.malformedWarningsEmitted());
            suppression.put("malformedWarningsSuppressed", snapshot.malformedWarningsSuppressed());
            plugins.add(entry);
        }
        return report;
    }

    private static String permissionFor(
        final List<String> permissionIds,
        final String capabilityId
    ) {
        final String direct = "turboism." + capabilityId;
        if (permissionIds.contains(direct)) {
            return direct;
        }
        if (capabilityId.equals("cubism.workspace.read")
            && permissionIds.contains("turboism.cubism.project.read")) {
            return "turboism.cubism.project.read";
        }
        return null;
    }

    private static String lifecycle(final String value) {
        return switch (value) {
            case "DEPENDENCY_LOAD_FAILED", "DEPENDENCY_FAILED" -> "DEPENDENCY_FAILED";
            case "PERMISSION_DENIED" -> "PERMISSION_DENIED";
            case "CLASSLOADER_FAILED" -> "CLASSLOADER_FAILED";
            case "ENABLE_FAILED" -> "ENABLE_FAILED";
            case "DISABLE_FAILED" -> "DISABLE_FAILED";
            case "SHUTDOWN_FAILED" -> "SHUTDOWN_FAILED";
            case "DISCOVERED", "RESOLVED", "CLASSLOADER_CREATED", "CONSTRUCTED", "LOADED",
                 "ENABLED", "DISABLED", "SHUTDOWN", "UNLOADED", "LOAD_FAILED" -> value;
            default -> "LOAD_FAILED";
        };
    }

    private static String relativeArtifact(final Path home, final Path candidate) {
        if (home == null || candidate == null) {
            return null;
        }
        final Path normalizedHome = home.toAbsolutePath().normalize();
        final Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedHome)) {
            return null;
        }
        final String relative = normalizedHome.relativize(normalized)
            .toString()
            .replace('\\', '/');
        return PreviewReportValidator.isRelativePath(relative) ? relative : null;
    }

    private static String sanitizePluginId(final String value) {
        if (value == null || value.isBlank()) {
            return "unknown-plugin";
        }
        final String sanitized = value.replaceAll("[^A-Za-z0-9._<>-]", "_");
        return sanitized.isBlank() ? "unknown-plugin" : sanitized;
    }

    private static String stableCode(final String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,128}")) {
            return "PLUGIN_LOAD_FAILED";
        }
        return value;
    }

    private static String stableFailureMessage(final String code) {
        return switch (stableCode(code)) {
            case "PLUGIN_DESCRIPTOR_MISSING" -> "Plugin descriptor is missing.";
            case "DUPLICATE_PLUGIN_ID" -> "Plugin ID is duplicated by another artifact.";
            case "DEPENDENCY_FAILED", "DEPENDENCY_LOAD_FAILED" ->
                "Required plugin dependency could not be resolved or loaded.";
            case "TURBOISM_API_INCOMPATIBLE" -> "Plugin API range is incompatible.";
            default -> "Plugin loading failed safely.";
        };
    }

    private static java.util.Optional<FileDigest> fileDigest(final Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return java.util.Optional.empty();
        }
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return java.util.Optional.of(new FileDigest(
                java.util.HexFormat.of().formatHex(digest.digest()),
                Files.size(path)
            ));
        } catch (IOException | NoSuchAlgorithmException exception) {
            return java.util.Optional.empty();
        }
    }

    private record FileDigest(String sha256, long size) {
    }
}
