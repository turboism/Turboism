package dev.turboism.preview.report;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Deterministic runtime-owned builders for closed preview report documents. */
public final class PreviewReportDocuments {

    static final ObjectMapper JSON = new ObjectMapper(new JsonFactory())
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private PreviewReportDocuments() {
    }

    public static Map<PreviewReportType, ObjectNode> emptyReportSet(
        final String runtimeId,
        final Instant createdAt
    ) {
        final EnumMap<PreviewReportType, ObjectNode> reports =
            new EnumMap<>(PreviewReportType.class);
        for (PreviewReportType type : PreviewReportType.values()) {
            reports.put(type, emptyReport(type, runtimeId, createdAt));
        }
        return Map.copyOf(reports);
    }

    public static ObjectNode emptyReport(
        final PreviewReportType type,
        final String runtimeId,
        final Instant createdAt
    ) {
        return envelope(
            type,
            runtimeId,
            createdAt,
            switch (type) {
                case PREVIEW_RUNTIME -> emptyPreviewRuntimePayload();
                case PLUGIN_LOAD -> objectWithArray("plugins");
                case CAPABILITY -> objectWithArray("capabilities");
                case I18N -> objectWithArray("plugins");
            }
        );
    }

    public static ObjectNode envelope(
        final PreviewReportType type,
        final String runtimeId,
        final Instant createdAt,
        final ObjectNode payload
    ) {
        final ObjectNode report = JSON.createObjectNode();
        report.put("format", "turboism.preview.report");
        report.put("schemaVersion", 1);
        report.put("reportType", Objects.requireNonNull(type, "type").name());
        report.put("runtimeId", requireText(runtimeId, "runtimeId"));
        report.put("createdAt", Objects.requireNonNull(createdAt, "createdAt").toString());
        report.set("truncation", noTruncation());
        report.set("payload", Objects.requireNonNull(payload, "payload").deepCopy());
        return report;
    }

    public static ObjectNode pluginLoadEntry(
        final String pluginId,
        final String artifactRelativePath,
        final String artifactSha256,
        final String discoveryState,
        final String dependencyState,
        final String lifecycleState,
        final boolean badNeighbor
    ) {
        final ObjectNode entry = JSON.createObjectNode();
        entry.put("pluginId", requireText(pluginId, "pluginId"));
        if (artifactRelativePath != null) {
            entry.put("artifactRelativePath", artifactRelativePath);
        }
        if (artifactSha256 != null) {
            entry.put("artifactSha256", artifactSha256);
        }
        entry.put("discoveryState", requireText(discoveryState, "discoveryState"));
        entry.put("dependencyState", requireText(dependencyState, "dependencyState"));
        entry.put("lifecycleState", requireText(lifecycleState, "lifecycleState"));
        entry.put("badNeighbor", badNeighbor);
        entry.put("disableState", "NOT_STARTED");
        entry.put("shutdownState", "NOT_STARTED");
        entry.put("unloadState", "NOT_STARTED");
        entry.put("scopeCleanupState", "NOT_STARTED");
        entry.put("classloaderCleanupState", "NOT_STARTED");
        entry.set("registrationsBeforeCleanup", emptyRegistrationCounts());
        entry.set("registrationsAfterCleanup", emptyRegistrationCounts());
        entry.set("failures", JSON.createArrayNode());
        return entry;
    }

    public static ObjectNode capabilityEntry(
        final String pluginId,
        final String capabilityId,
        final String operationId,
        final String permissionId,
        final String capabilityAvailability,
        final String permissionAvailability,
        final String registrationState
    ) {
        final ObjectNode entry = JSON.createObjectNode();
        if (pluginId != null) {
            entry.put("pluginId", pluginId);
        }
        entry.put("capabilityId", requireText(capabilityId, "capabilityId"));
        entry.put("operationId", requireText(operationId, "operationId"));
        if (permissionId != null) {
            entry.put("permissionId", permissionId);
        }
        entry.put(
            "capabilityAvailability",
            requireText(capabilityAvailability, "capabilityAvailability")
        );
        entry.put(
            "permissionAvailability",
            requireText(permissionAvailability, "permissionAvailability")
        );
        entry.put("registrationState", requireText(registrationState, "registrationState"));
        entry.set("registrationCounts", emptyRegistrationCounts());
        entry.set("evidence", JSON.createArrayNode());
        entry.set("failures", JSON.createArrayNode());
        return entry;
    }

    public static ObjectNode evidence(
        final String kind,
        final String state,
        final String summary,
        final String relativeRecordPath,
        final String digestSha256
    ) {
        final ObjectNode evidence = JSON.createObjectNode();
        evidence.put("kind", requireText(kind, "kind"));
        evidence.put("state", requireText(state, "state"));
        evidence.put("summary", requireText(summary, "summary"));
        if (relativeRecordPath != null) {
            evidence.put("relativeRecordPath", relativeRecordPath);
        }
        if (digestSha256 != null) {
            evidence.put("digestSha256", digestSha256);
        }
        return evidence;
    }

    public static ObjectNode failure(
        final String code,
        final String severity,
        final String phase,
        final String pluginId,
        final String operationId,
        final String permissionId,
        final String message,
        final String relativePath,
        final long count
    ) {
        final ObjectNode failure = JSON.createObjectNode();
        failure.put("code", requireText(code, "code"));
        failure.put("severity", requireText(severity, "severity"));
        failure.put("phase", requireText(phase, "phase"));
        if (pluginId != null) {
            failure.put("pluginId", pluginId);
        }
        if (operationId != null) {
            failure.put("operationId", operationId);
        }
        if (permissionId != null) {
            failure.put("permissionId", permissionId);
        }
        failure.put("message", requireText(message, "message"));
        if (relativePath != null) {
            failure.put("relativePath", relativePath);
        }
        failure.put("count", count);
        return failure;
    }

    public static ObjectNode i18nPluginEntry(
        final String pluginId,
        final String localeSource,
        final String requestedLocale,
        final String normalizedLocale,
        final Iterable<String> fallbackChain
    ) {
        final ObjectNode entry = JSON.createObjectNode();
        entry.put("pluginId", requireText(pluginId, "pluginId"));
        entry.put("localeSource", requireText(localeSource, "localeSource"));
        entry.put("requestedLocale", requireText(requestedLocale, "requestedLocale"));
        entry.put("normalizedLocale", requireText(normalizedLocale, "normalizedLocale"));
        final ArrayNode fallback = entry.putArray("fallbackChain");
        for (String value : fallbackChain) {
            fallback.add(requireText(value, "fallback locale"));
        }
        entry.set("catalogs", JSON.createArrayNode());
        entry.set("missingKeys", JSON.createArrayNode());
        entry.set("malformedPatterns", JSON.createArrayNode());
        final ObjectNode suppression = entry.putObject("suppression");
        suppression.put("missingWarningsEmitted", 0);
        suppression.put("missingWarningsSuppressed", 0);
        suppression.put("malformedWarningsEmitted", 0);
        suppression.put("malformedWarningsSuppressed", 0);
        return entry;
    }

    public static ObjectNode catalogEntry(
        final String locale,
        final String state,
        final long keyCount
    ) {
        final ObjectNode entry = JSON.createObjectNode();
        entry.put("locale", requireText(locale, "locale"));
        entry.put("state", requireText(state, "state"));
        entry.put("keyCount", keyCount);
        return entry;
    }

    public static ObjectNode emptyRegistrationCounts() {
        final ObjectNode counts = JSON.createObjectNode();
        counts.put("actions", 0);
        counts.put("events", 0);
        counts.put("menus", 0);
        counts.put("toolbars", 0);
        counts.put("contextMenus", 0);
        counts.put("overlays", 0);
        counts.put("dialogs", 0);
        counts.put("panels", 0);
        counts.put("status", 0);
        counts.put("tasks", 0);
        counts.put("configSchemas", 0);
        counts.put("userFileHandles", 0);
        counts.put("total", 0);
        return counts;
    }

    public static ObjectNode emptyCleanupCounts() {
        final ObjectNode counts = JSON.createObjectNode();
        counts.put("taskHandlesCanceled", 0);
        counts.put("taskCompletionsSettled", 0);
        counts.put("pluginContinuationsDrained", 0);
        counts.put("userFileHandlesRevoked", 0);
        counts.put("configSchemasUnregistered", 0);
        counts.put("temporaryFilesDeleted", 0);
        counts.put("scopesClosed", 0);
        counts.put("classloadersClosed", 0);
        counts.put("failures", 0);
        return counts;
    }

    public static ObjectNode shutdownCounts(
        final long attempted,
        final long succeeded,
        final long failed,
        final long timedOut
    ) {
        final ObjectNode counts = JSON.createObjectNode();
        counts.put("attempted", attempted);
        counts.put("succeeded", succeeded);
        counts.put("failed", failed);
        counts.put("timedOut", timedOut);
        return counts;
    }

    public static ObjectNode noTruncation() {
        final ObjectNode truncation = JSON.createObjectNode();
        truncation.put("truncated", false);
        truncation.put("droppedEntries", 0);
        truncation.put("reason", "NONE");
        return truncation;
    }

    private static ObjectNode emptyPreviewRuntimePayload() {
        final ObjectNode payload = JSON.createObjectNode();
        final ObjectNode host = payload.putObject("host");
        host.put("product", "Live2D Cubism");
        host.put("version", "UNKNOWN");
        host.put("identityState", "UNKNOWN");
        payload.put("adapterState", "UNAVAILABLE");
        payload.put("runtimeState", "RUNNING");
        payload.set("taskFailures", JSON.createArrayNode());
        payload.set("storageFailures", JSON.createArrayNode());
        payload.set("configFailures", JSON.createArrayNode());
        payload.set("shutdownCounts", shutdownCounts(0, 0, 0, 0));
        payload.set("cleanupCounts", emptyCleanupCounts());
        return payload;
    }

    private static ObjectNode objectWithArray(final String field) {
        final ObjectNode payload = JSON.createObjectNode();
        payload.set(field, JSON.createArrayNode());
        return payload;
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
