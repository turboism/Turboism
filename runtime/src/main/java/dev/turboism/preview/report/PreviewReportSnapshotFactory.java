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
import java.util.Comparator;
import java.util.EnumMap;
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
    private static final String UNMAPPED_CAPABILITY_OPERATION = "unmapped.capability";
    private static final Map<String, List<CapabilityBinding>> CAPABILITY_BINDINGS = Map.ofEntries(
        binding("cubism.project.read", "cubismRead.activeProject", "turboism.cubism.project.read"),
        binding("cubism.workspace.read", "cubismRead.workspace", "turboism.cubism.project.read"),
        binding("cubism.mesh.read", "cubismRead.meshes", "turboism.cubism.model.read"),
        binding("cubism.deformer.read", "cubismRead.deformers", "turboism.cubism.model.read"),
        binding("cubism.psd.read", "cubismRead.psdDocuments", "turboism.cubism.model.read"),
        binding("cubism.clipmask.read", "cubismRead.clipMasks", "turboism.cubism.model.read"),
        binding("cubism.texture-atlas.read", "cubismRead.textureAtlases", "turboism.cubism.model.read"),
        binding("cubism.render.status.read", "cubismRead.renderStatus", "turboism.cubism.model.read"),
        binding("cubism.theme.status.read", "cubismRead.themeStatus", "turboism.cubism.project.read"),
        bindings(
            "cubism.selection.read",
            binding("cubismRead.selection", "turboism.cubism.model.read"),
            binding("selectionQuery.currentSelection", "turboism.cubism.model.read"),
            binding("selectionQuery.selectedIds", "turboism.cubism.model.read"),
            binding("selectionQuery.onSelectionChanged", "turboism.cubism.model.read")
        ),
        bindings(
            "cubism.parameter.read",
            binding("cubismRead.parameters", "turboism.cubism.model.read"),
            binding("parameterQuery.findById", "turboism.cubism.parameter.read"),
            binding("parameterQuery.listAll", "turboism.cubism.parameter.read"),
            binding("parameterQuery.exists", "turboism.cubism.parameter.read")
        ),
        bindings(
            "cubism.parameter.write",
            binding("transaction.open", "turboism.cubism.model.write"),
            binding("transaction.enqueue", "turboism.cubism.model.write"),
            binding("transaction.commit", "turboism.cubism.model.write")
        ),
        bindings(
            "cubism.model-tree.read",
            binding("cubismRead.activeDocument", "turboism.cubism.model.read"),
            binding("cubismRead.activeModel", "turboism.cubism.model.read"),
            binding("cubismRead.modelObjects", "turboism.cubism.model.read"),
            binding("modelHierarchyQuery.currentHierarchy", "turboism.cubism.model.read"),
            binding("modelHierarchyQuery.childrenOf", "turboism.cubism.model.read"),
            binding("modelHierarchyQuery.findNode", "turboism.cubism.model.read")
        ),
        binding("ui.context-source.read", "ui.context-source.read", "turboism.ui.context-source.read"),
        binding("ui.overlay.contribute", "ui.overlay.contribute", "turboism.ui.overlay.contribute"),
        binding("ui.viewport.read", "ui.viewport.read", "turboism.ui.viewport.read"),
        binding("ui.dialog.contribute", "ui.dialog.contribute", "turboism.ui.dialog.contribute"),
        binding("ui.embedded-panel.contribute", "ui.panel.contribute", "turboism.ui.panel.contribute"),
        binding("ui.file-chooser.request", "ui.file-chooser.request", "turboism.ui.file-chooser.request"),
        binding("ui.status.notify", "ui.status.notify", "turboism.ui.status.notify"),
        binding("ui.palette-toolbar.contribute", "ui.palette-toolbar.contribute", "turboism.ui.toolbar.palette.contribute"),
        binding("ui.main-toolbar.contribute", "ui.main-toolbar.contribute", "turboism.ui.toolbar.main.contribute")
    );
    private static final Set<String> KNOWN_UNMAPPED_CAPABILITIES = Set.of(
        "cubism.model-tree.write",
        "cubism.mesh.write",
        "cubism.deformer.write",
        "cubism.mirror.writeback",
        "cubism.psd.binding.write",
        "cubism.clipmask.write",
        "cubism.canvas.write",
        "cubism.bounding-box.action.write",
        "event.project.lifecycle",
        "event.selection.changed",
        "event.texture-atlas.reinit",
        "event.render.status.changed",
        "hook-ingress.project.lifecycle",
        "hook-ingress.selection.changed",
        "hook-ingress.context-menu.opening",
        "hook-ingress.texture-atlas.reinit",
        "hook-ingress.viewport.overlay.lifecycle",
        "hook-ingress.render.status",
        "hook-ingress.model.tree.changed",
        "hook-ingress.parameter.changed",
        "plugin.localization",
        "plugin.task.schedule",
        "plugin.storage",
        "plugin.config.typed",
        "plugin.user-file",
        "runtime.host-read.async",
        "cubism.selection.write",
        "cubism.geometry.read",
        "cubism.parameter-binding.read",
        "cubism.psd.layer-relationship.read",
        "cubism.psd.binding-candidate.read",
        "cubism.psd.layer-bounds.read",
        "cubism.transaction.real-write-undo",
        "cubism.recent-preview.manage",
        "ui.file-chooser.history-policy",
        "ui.host-settings.open",
        "ui.log-filter.control",
        "ui.theme.apply",
        "cubism.theme.restore",
        "cubism.render.modify",
        "cubism.render.restore"
    );

    static {
        final Set<String> overlappingPolicies = new java.util.HashSet<>(CAPABILITY_BINDINGS.keySet());
        overlappingPolicies.retainAll(KNOWN_UNMAPPED_CAPABILITIES);
        if (!overlappingPolicies.isEmpty()) {
            throw new IllegalStateException(
                "Mapped and known-unmapped preview capability policies overlap: " + overlappingPolicies
            );
        }
    }

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
            cleanup.put("taskHandlesCanceled", summaries.stream().mapToLong(summary ->
                summary.cleanupEvidence().taskHandlesCanceled()).sum());
            cleanup.put("taskCompletionsSettled", summaries.stream().mapToLong(summary ->
                summary.cleanupEvidence().taskCompletionsSettled()).sum());
            cleanup.put("pluginContinuationsDrained", summaries.stream().mapToLong(summary ->
                summary.cleanupEvidence().pluginContinuationsDrained()).sum());
            cleanup.put("userFileHandlesRevoked", summaries.stream().mapToLong(summary ->
                summary.cleanupEvidence().userFileHandlesRevoked()).sum());
            cleanup.put("configSchemasUnregistered", summaries.stream().mapToLong(summary ->
                summary.cleanupEvidence().configSchemasUnregistered()).sum());
            cleanup.put("temporaryFilesDeleted", summaries.stream().mapToLong(summary ->
                summary.cleanupEvidence().temporaryFilesDeleted()).sum());
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
                summaries.stream().mapToLong(summary ->
                    summary.cleanupEvidence().failures()
                        + (summary.scopeCleanupState().equals("FAILED") ? 1 : 0)
                        + (summary.classloaderCleanupState().equals("FAILED") ? 1 : 0)
                ).sum()
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
                final List<CapabilityBinding> bindings = CAPABILITY_BINDINGS.get(capabilityId);
                final boolean mapped = bindings != null;
                final boolean knownUnmapped = KNOWN_UNMAPPED_CAPABILITIES.contains(capabilityId);
                final boolean verifiedProject = VERIFIED_PROJECT_CAPABILITIES.contains(capabilityId);
                final boolean runtimeAvailable = verifiedProject
                    && hostState == HostSession.State.ACTIVE
                    && !stopped
                    && summary.state().name().equals("ENABLED");
                final String availability = verifiedProject
                    ? runtimeAvailable ? "AVAILABLE" : "UNAVAILABLE"
                    : "UNKNOWN";
                if (mapped) {
                    for (CapabilityBinding binding : bindings) {
                        final String permissionId = permissionFor(summary.permissionIds(), binding);
                        final ObjectNode entry = PreviewReportDocuments.capabilityEntry(
                            summary.id(),
                            capabilityId,
                            binding.operationId(),
                            permissionId,
                            availability,
                            permissionId == null ? "NOT_DECLARED" : "GRANTED",
                            "NONE"
                        );
                        addCapabilityEvidence(
                            (ArrayNode) entry.get("evidence"),
                            verifiedProject,
                            runtimeAvailable,
                            availability,
                            recordPath,
                            recordDigest
                        );
                        entries.add(entry);
                    }
                } else if (knownUnmapped) {
                    entries.add(unmappedCapabilityEntry(summary.id(), capabilityId));
                } else {
                    entries.add(unknownCapabilityFallbackEntry(summary.id(), capabilityId));
                }
            }
        }
        return report;
    }

    private static ObjectNode unmappedCapabilityEntry(final String pluginId, final String capabilityId) {
        return capabilityFallbackEntry(pluginId, capabilityId);
    }

    private static ObjectNode unknownCapabilityFallbackEntry(final String pluginId, final String capabilityId) {
        return capabilityFallbackEntry(pluginId, capabilityId);
    }

    private static ObjectNode capabilityFallbackEntry(final String pluginId, final String capabilityId) {
        final ObjectNode entry = PreviewReportDocuments.capabilityEntry(
            pluginId,
            capabilityId,
            UNMAPPED_CAPABILITY_OPERATION,
            null,
            "UNKNOWN",
            "UNKNOWN",
            "NONE"
        );
        addCapabilityEvidence(
            (ArrayNode) entry.get("evidence"),
            false,
            false,
            "UNKNOWN",
            null,
            null
        );
        return entry;
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

    private static void addCapabilityEvidence(
        final ArrayNode evidence,
        final boolean verifiedProject,
        final boolean runtimeAvailable,
        final String availability,
        final String recordPath,
        final String recordDigest
    ) {
        if (verifiedProject && recordPath != null && recordDigest != null) {
            evidence.add(PreviewReportDocuments.evidence(
                "STATIC_VERIFIED",
                availability,
                runtimeAvailable
                    ? "Exact Cubism 5.3.02 static record applies to the connected runtime session."
                    : "Exact Cubism 5.3.02 static record exists; runtime availability was not established.",
                recordPath,
                recordDigest
            ));
            return;
        }
        evidence.add(PreviewReportDocuments.evidence(
            "DECLARED",
            "UNKNOWN",
            "Capability is declared by the plugin descriptor; support is not elevated.",
            null,
            null
        ));
    }

    private static String permissionFor(
        final List<String> permissionIds,
        final CapabilityBinding binding
    ) {
        return permissionIds.contains(binding.permissionId()) ? binding.permissionId() : null;
    }

    private static Map.Entry<String, List<CapabilityBinding>> binding(
        final String capabilityId,
        final String operationId,
        final String permissionId
    ) {
        return bindings(capabilityId, binding(operationId, permissionId));
    }

    private static Map.Entry<String, List<CapabilityBinding>> bindings(
        final String capabilityId,
        final CapabilityBinding... bindings
    ) {
        return Map.entry(capabilityId, List.of(bindings));
    }

    private static CapabilityBinding binding(final String operationId, final String permissionId) {
        return new CapabilityBinding(operationId, permissionId);
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

    private record CapabilityBinding(String operationId, String permissionId) {
    }

    private record FileDigest(String sha256, long size) {
    }
}
