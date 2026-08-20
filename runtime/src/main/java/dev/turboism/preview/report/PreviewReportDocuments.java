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

    /**
     * Builds one empty document per report type, so a preview run always emits the full closed set
     * even when nothing was observed.
     *
     * @param runtimeId identifier of the runtime instance the reports describe; must not be blank
     * @param createdAt the timestamp stamped into every envelope, rendered in ISO-8601
     * @return an unmodifiable map keyed by every {@link PreviewReportType} constant
     * @throws NullPointerException if {@code runtimeId} or {@code createdAt} is {@code null}
     * @throws IllegalArgumentException if {@code runtimeId} is blank
     */
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

    /**
     * Builds a single report document with a valid envelope and an empty payload shaped for its
     * type: an unknown-host runtime payload, or an object holding one empty collection array.
     *
     * @param type the report to build; determines the payload shape
     * @param runtimeId identifier of the runtime instance; must not be blank
     * @param createdAt the envelope timestamp
     * @return a freshly created mutable node the caller may populate
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if {@code runtimeId} is blank
     */
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

    /**
     * Wraps a payload in the common report envelope: fixed {@code format} marker,
     * {@code schemaVersion} 1, report type, runtime id, ISO-8601 creation timestamp, and a
     * not-truncated marker.
     *
     * <p>The payload is stored as a deep copy, so later mutation of the caller's node does not
     * leak into the returned document.
     *
     * @param type the report type recorded in the envelope
     * @param runtimeId identifier of the runtime instance; must not be blank
     * @param createdAt the envelope timestamp
     * @param payload the type-specific body; copied defensively
     * @return the assembled report document
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if {@code runtimeId} is blank
     */
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

    /**
     * Builds one plugin entry for the plugin-load report, recording the states observed up to and
     * including lifecycle start.
     *
     * <p>The teardown fields ({@code disableState}, {@code shutdownState}, {@code unloadState},
     * {@code scopeCleanupState}, {@code classloaderCleanupState}) are seeded to
     * {@code NOT_STARTED} and the registration counts to zero — the caller overwrites them as the
     * run progresses. The optional artifact fields are omitted entirely when {@code null} rather
     * than written as JSON null.
     *
     * @param pluginId the plugin's identifier; must not be blank
     * @param artifactRelativePath path of the plugin artifact relative to the scanned root, or
     *     {@code null} to omit the field (no absolute path is ever recorded)
     * @param artifactSha256 digest of the artifact, or {@code null} to omit the field
     * @param discoveryState how discovery classified the artifact; must not be blank
     * @param dependencyState how dependency resolution classified it; must not be blank
     * @param lifecycleState how far the plugin got through its lifecycle; must not be blank
     * @param badNeighbor whether this plugin was implicated in disrupting other plugins
     * @return a mutable entry node
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if a required argument is blank
     */
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

    /**
     * Builds one entry for the capability report, pairing a capability operation with the
     * permission it requires and how each was resolved.
     *
     * <p>Registration counts start at zero and the {@code evidence} and {@code failures} arrays
     * start empty; callers append to them with {@link #evidence} and {@link #failure}. Optional
     * fields are omitted when {@code null} rather than written as JSON null.
     *
     * @param pluginId the plugin the capability was resolved for, or {@code null} for a
     *     runtime-level entry with no owning plugin
     * @param capabilityId identifier of the capability; must not be blank
     * @param operationId identifier of the operation within the capability; must not be blank
     * @param permissionId the permission gating the operation, or {@code null} when none applies
     * @param capabilityAvailability whether the capability was resolvable; must not be blank
     * @param permissionAvailability whether the permission was resolvable; must not be blank
     * @param registrationState how the runtime registered the operation; must not be blank
     * @return a mutable entry node
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if a required argument is blank
     */
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

    /**
     * Builds one evidence record supporting a capability entry's stated availability.
     *
     * <p>The record path is deliberately relative: preview reports must never carry an absolute
     * filesystem path. Both the path and the digest are omitted when {@code null}.
     *
     * @param kind what sort of evidence this is; must not be blank
     * @param state the evidence's verdict; must not be blank
     * @param summary short human-readable explanation; must not be blank
     * @param relativeRecordPath location of the backing record relative to the report root, or
     *     {@code null} when the evidence has no stored record
     * @param digestSha256 digest of that record, or {@code null} when not computed
     * @return a mutable evidence node
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if a required argument is blank
     */
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

    /**
     * Builds one failure record for a plugin-load, capability, or runtime report.
     *
     * <p>Carries a {@code count} so repeated identical failures are folded into a single record
     * instead of flooding the document. The optional attribution fields and the path are omitted
     * when {@code null}; the path is relative by contract, never absolute.
     *
     * @param code stable machine-readable failure code; must not be blank
     * @param severity how serious the failure is; must not be blank
     * @param phase the run phase in which it occurred; must not be blank
     * @param pluginId the plugin at fault, or {@code null} when not attributable to one
     * @param operationId the operation involved, or {@code null}
     * @param permissionId the permission involved, or {@code null}
     * @param message human-readable detail; must not be blank
     * @param relativePath the file involved, relative to the report root, or {@code null}
     * @param count how many occurrences this record folds together
     * @return a mutable failure node
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if a required argument is blank
     */
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

    /**
     * Builds one plugin entry for the i18n report, recording how the plugin's locale was chosen
     * and which locales it would fall back through.
     *
     * <p>The {@code catalogs}, {@code missingKeys}, and {@code malformedPatterns} arrays start
     * empty and the four suppression counters start at zero; the caller fills them in as catalogs
     * are loaded and warnings are emitted or suppressed. The fallback chain is copied into the
     * node in iteration order, so the caller's iterable is not retained.
     *
     * @param pluginId the plugin's identifier; must not be blank
     * @param localeSource where the requested locale came from; must not be blank
     * @param requestedLocale the locale as requested; must not be blank
     * @param normalizedLocale the locale after normalization; must not be blank
     * @param fallbackChain locales to consult in order after the normalized one; every element
     *     must be non-blank
     * @return a mutable entry node
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if a required argument or any fallback locale is blank
     */
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

    /**
     * Builds one message-catalog record for an i18n plugin entry.
     *
     * @param locale the catalog's locale tag; must not be blank
     * @param state how the catalog resolved (loaded, missing, and so on); must not be blank
     * @param keyCount number of message keys the catalog contributed
     * @return a mutable catalog node
     * @throws NullPointerException if {@code locale} or {@code state} is {@code null}
     * @throws IllegalArgumentException if either is blank
     */
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

    /**
     * @return a fresh registration-count node with every extension-point counter and the
     *     {@code total} set to zero; the field set is fixed so before/after cleanup counts are
     *     directly comparable
     */
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

    /**
     * @return a fresh cleanup-count node with every teardown counter, including {@code failures},
     *     set to zero; callers increment these as scopes, handles, and classloaders are released
     */
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

    /**
     * Records how plugin shutdown went in aggregate. The values are written verbatim; this builder
     * does not check that the three outcomes add up to {@code attempted}.
     *
     * @param attempted plugins whose shutdown was started
     * @param succeeded plugins that shut down cleanly
     * @param failed plugins whose shutdown raised
     * @param timedOut plugins that did not finish within the shutdown budget
     * @return a mutable counts node
     */
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

    /**
     * @return the truncation marker every envelope starts with: not truncated, no dropped entries,
     *     reason {@code NONE}; a writer that has to shed entries replaces it
     */
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
