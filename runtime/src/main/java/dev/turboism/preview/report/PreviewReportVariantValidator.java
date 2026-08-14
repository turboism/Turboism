package dev.turboism.preview.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

import static dev.turboism.preview.report.PreviewReportValidationSupport.array;
import static dev.turboism.preview.report.PreviewReportValidationSupport.boundedArray;
import static dev.turboism.preview.report.PreviewReportValidationSupport.boundedText;
import static dev.turboism.preview.report.PreviewReportValidationSupport.bool;
import static dev.turboism.preview.report.PreviewReportValidationSupport.enumText;
import static dev.turboism.preview.report.PreviewReportValidationSupport.exact;
import static dev.turboism.preview.report.PreviewReportValidationSupport.failure;
import static dev.turboism.preview.report.PreviewReportValidationSupport.fields;
import static dev.turboism.preview.report.PreviewReportValidationSupport.nonnegative;
import static dev.turboism.preview.report.PreviewReportValidationSupport.object;
import static dev.turboism.preview.report.PreviewReportValidationSupport.optionalDigest;
import static dev.turboism.preview.report.PreviewReportValidationSupport.optionalNonnegative;
import static dev.turboism.preview.report.PreviewReportValidationSupport.optionalPath;
import static dev.turboism.preview.report.PreviewReportValidationSupport.optionalText;
import static dev.turboism.preview.report.PreviewReportValidationSupport.positive;
import static dev.turboism.preview.report.PreviewReportValidationSupport.validateCleanupCounts;
import static dev.turboism.preview.report.PreviewReportValidationSupport.validateEvidenceArray;
import static dev.turboism.preview.report.PreviewReportValidationSupport.validateFailureArray;
import static dev.turboism.preview.report.PreviewReportValidationSupport.validateRegistrationCounts;
import static dev.turboism.preview.report.PreviewReportValidationSupport.validateShutdownCounts;
import static dev.turboism.preview.report.PreviewReportValidationSupport.validateTextArray;

/** Closed variant payload validation for preview-report v1. */
final class PreviewReportVariantValidator {

    private static final Set<String> ADAPTER_STATES = Set.of(
        "UNAVAILABLE", "INITIALIZING", "READY", "DEGRADED", "FAILED", "SHUTDOWN"
    );
    private static final Set<String> RUNTIME_STATES = Set.of(
        "STARTING", "RUNNING", "DEGRADED", "STOPPING", "STOPPED", "FAILED"
    );
    private static final Set<String> IDENTITY_STATES = Set.of(
        "UNKNOWN", "MATCHED", "MISMATCHED", "NOT_APPLICABLE"
    );
    private static final Set<String> DISCOVERY_STATES = Set.of(
        "DISCOVERED", "INVALID_DESCRIPTOR", "BAD_NEIGHBOR", "NOT_DISCOVERED"
    );
    private static final Set<String> DEPENDENCY_STATES = Set.of(
        "NOT_EVALUATED", "RESOLVED", "FAILED"
    );
    private static final Set<String> LIFECYCLE_STATES = Set.of(
        "DISCOVERED", "RESOLVED", "CLASSLOADER_CREATED", "CONSTRUCTED", "LOADED",
        "ENABLED", "DISABLED", "SHUTDOWN", "UNLOADED", "INVALID_DESCRIPTOR",
        "DEPENDENCY_FAILED", "PERMISSION_DENIED", "CLASSLOADER_FAILED", "LOAD_FAILED",
        "ENABLE_FAILED", "DISABLE_FAILED", "SHUTDOWN_FAILED"
    );
    private static final Set<String> CLEANUP_STATES = Set.of(
        "NOT_REQUIRED", "NOT_STARTED", "SUCCEEDED", "FAILED"
    );
    private static final Set<String> CAPABILITY_AVAILABILITY = Set.of(
        "AVAILABLE", "UNAVAILABLE", "DEGRADED", "UNKNOWN"
    );
    private static final Set<String> PERMISSION_AVAILABILITY = Set.of(
        "GRANTED", "DENIED", "NOT_DECLARED", "NOT_REQUIRED", "UNKNOWN"
    );
    private static final Set<String> REGISTRATION_STATES = Set.of(
        "NOT_APPLICABLE", "NONE", "ACTIVE", "PARTIAL", "CLEANED", "LEAKED", "UNKNOWN"
    );
    private static final Set<String> LOCALE_SOURCES = Set.of(
        "PREVIEW_OPTION", "STARTUP", "DISPLAY_LOCALE", "JVM_DISPLAY_DEFAULT"
    );
    private static final Set<String> CATALOG_STATES = Set.of(
        "AVAILABLE", "MISSING", "INVALID"
    );

    private PreviewReportVariantValidator() {
    }

    static void validate(
        final PreviewReportType type,
        final ObjectNode payload
    ) {
        switch (type) {
            case PREVIEW_RUNTIME -> validatePreviewRuntime(payload);
            case PLUGIN_LOAD -> validatePluginLoad(payload);
            case CAPABILITY -> validateCapability(payload);
            case I18N -> validateI18n(payload);
        }
    }

    private static void validatePreviewRuntime(final ObjectNode payload) {
        requireVariantField(payload, "host", PreviewReportType.PREVIEW_RUNTIME);
        exact(
            payload,
            Set.of(
                "host", "adapterState", "runtimeState", "taskFailures",
                "storageFailures", "configFailures", "shutdownCounts", "cleanupCounts"
            ),
            Set.of(),
            "UNKNOWN_FIELD",
            "PREVIEW_RUNTIME payload"
        );
        final ObjectNode host = object(payload.get("host"), "BAD_HOST", "host");
        exact(
            host,
            Set.of("product", "version", "identityState"),
            Set.of("artifactSha256", "artifactSizeBytes"),
            "UNKNOWN_FIELD",
            "host"
        );
        boundedText(host, "product", 256, "BAD_HOST");
        boundedText(host, "version", 128, "BAD_HOST");
        enumText(host, "identityState", IDENTITY_STATES, "BAD_HOST");
        optionalDigest(host, "artifactSha256");
        optionalNonnegative(host, "artifactSizeBytes", "BAD_HOST");
        enumText(payload, "adapterState", ADAPTER_STATES, "BAD_ADAPTER_STATE");
        enumText(payload, "runtimeState", RUNTIME_STATES, "BAD_RUNTIME_STATE");
        validateFailureArray(payload.get("taskFailures"), "taskFailures");
        validateFailureArray(payload.get("storageFailures"), "storageFailures");
        validateFailureArray(payload.get("configFailures"), "configFailures");
        validateShutdownCounts(payload.get("shutdownCounts"));
        validateCleanupCounts(payload.get("cleanupCounts"));
    }

    private static void validatePluginLoad(final ObjectNode payload) {
        requireVariantField(payload, "plugins", PreviewReportType.PLUGIN_LOAD);
        exact(payload, Set.of("plugins"), Set.of(), "UNKNOWN_FIELD", "PLUGIN_LOAD payload");
        final JsonNode plugins = array(payload.get("plugins"), "BAD_PLUGIN_LIST", "plugins");
        boundedArray(plugins, "plugins");
        for (JsonNode value : plugins) {
            final ObjectNode plugin = object(value, "BAD_PLUGIN_ENTRY", "plugin");
            exact(
                plugin,
                Set.of(
                    "pluginId", "discoveryState", "dependencyState", "lifecycleState",
                    "badNeighbor", "disableState", "shutdownState", "unloadState",
                    "scopeCleanupState", "classloaderCleanupState",
                    "registrationsBeforeCleanup", "registrationsAfterCleanup", "failures"
                ),
                Set.of("artifactRelativePath", "artifactSha256"),
                "UNKNOWN_FIELD",
                "plugin"
            );
            boundedText(plugin, "pluginId", 256, "BAD_PLUGIN_ENTRY");
            optionalPath(plugin, "artifactRelativePath");
            optionalDigest(plugin, "artifactSha256");
            enumText(plugin, "discoveryState", DISCOVERY_STATES, "BAD_PLUGIN_ENTRY");
            enumText(plugin, "dependencyState", DEPENDENCY_STATES, "BAD_PLUGIN_ENTRY");
            enumText(plugin, "lifecycleState", LIFECYCLE_STATES, "BAD_PLUGIN_ENTRY");
            bool(plugin, "badNeighbor", "BAD_PLUGIN_ENTRY");
            enumText(plugin, "disableState", CLEANUP_STATES, "BAD_PLUGIN_ENTRY");
            enumText(plugin, "shutdownState", CLEANUP_STATES, "BAD_PLUGIN_ENTRY");
            enumText(plugin, "unloadState", CLEANUP_STATES, "BAD_PLUGIN_ENTRY");
            enumText(plugin, "scopeCleanupState", CLEANUP_STATES, "BAD_PLUGIN_ENTRY");
            enumText(plugin, "classloaderCleanupState", CLEANUP_STATES, "BAD_PLUGIN_ENTRY");
            validateRegistrationCounts(plugin.get("registrationsBeforeCleanup"));
            validateRegistrationCounts(plugin.get("registrationsAfterCleanup"));
            validateFailureArray(plugin.get("failures"), "plugin.failures");
        }
    }

    private static void validateCapability(final ObjectNode payload) {
        requireVariantField(payload, "capabilities", PreviewReportType.CAPABILITY);
        exact(
            payload,
            Set.of("capabilities"),
            Set.of(),
            "UNKNOWN_FIELD",
            "CAPABILITY payload"
        );
        final JsonNode capabilities = array(
            payload.get("capabilities"),
            "BAD_CAPABILITY_LIST",
            "capabilities"
        );
        boundedArray(capabilities, "capabilities");
        for (JsonNode value : capabilities) {
            final ObjectNode capability = object(
                value,
                "BAD_CAPABILITY_ENTRY",
                "capability"
            );
            exact(
                capability,
                Set.of(
                    "capabilityId", "operationId", "capabilityAvailability",
                    "permissionAvailability", "registrationState", "registrationCounts",
                    "evidence", "failures"
                ),
                Set.of("pluginId", "permissionId"),
                "UNKNOWN_FIELD",
                "capability"
            );
            optionalText(capability, "pluginId", 256, "BAD_CAPABILITY_ENTRY");
            boundedText(capability, "capabilityId", 256, "BAD_CAPABILITY_ENTRY");
            boundedText(capability, "operationId", 256, "BAD_CAPABILITY_ENTRY");
            optionalText(capability, "permissionId", 256, "BAD_CAPABILITY_ENTRY");
            enumText(
                capability,
                "capabilityAvailability",
                CAPABILITY_AVAILABILITY,
                "BAD_CAPABILITY_ENTRY"
            );
            enumText(
                capability,
                "permissionAvailability",
                PERMISSION_AVAILABILITY,
                "BAD_CAPABILITY_ENTRY"
            );
            enumText(
                capability,
                "registrationState",
                REGISTRATION_STATES,
                "BAD_CAPABILITY_ENTRY"
            );
            validateRegistrationCounts(capability.get("registrationCounts"));
            validateEvidenceArray(capability.get("evidence"));
            validateFailureArray(capability.get("failures"), "capability.failures");
        }
    }

    private static void validateI18n(final ObjectNode payload) {
        requireVariantField(payload, "plugins", PreviewReportType.I18N);
        exact(payload, Set.of("plugins"), Set.of(), "UNKNOWN_FIELD", "I18N payload");
        final JsonNode plugins = array(payload.get("plugins"), "BAD_I18N_LIST", "plugins");
        boundedArray(plugins, "i18n.plugins");
        for (JsonNode value : plugins) {
            final ObjectNode plugin = object(value, "BAD_I18N_ENTRY", "i18n plugin");
            exact(
                plugin,
                Set.of(
                    "pluginId", "localeSource", "requestedLocale", "normalizedLocale",
                    "fallbackChain", "catalogs", "missingKeys", "malformedPatterns",
                    "suppression"
                ),
                Set.of(),
                "UNKNOWN_FIELD",
                "i18n plugin"
            );
            boundedText(plugin, "pluginId", 256, "BAD_I18N_ENTRY");
            enumText(plugin, "localeSource", LOCALE_SOURCES, "BAD_I18N_ENTRY");
            boundedText(plugin, "requestedLocale", 128, "BAD_I18N_ENTRY");
            boundedText(plugin, "normalizedLocale", 128, "BAD_I18N_ENTRY");
            validateTextArray(plugin.get("fallbackChain"), "BAD_I18N_ENTRY", true);
            validateCatalogs(plugin.get("catalogs"));
            validateMissingKeys(plugin.get("missingKeys"));
            validateMalformedPatterns(plugin.get("malformedPatterns"));
            validateSuppression(plugin.get("suppression"));
        }
    }

    private static void requireVariantField(
        final ObjectNode payload,
        final String required,
        final PreviewReportType expected
    ) {
        if (payload.has(required)) {
            return;
        }
        final Set<String> variantRoots = Set.of("host", "plugins", "capabilities");
        if (!fields(payload).isEmpty()
            && fields(payload).stream().anyMatch(variantRoots::contains)) {
            throw failure(
                "REPORT_TYPE_MISMATCH",
                "Preview report discriminator does not match its payload."
            );
        }
        throw failure(
            "BAD_PAYLOAD",
            expected + " payload is missing required field " + required + "."
        );
    }

    private static void validateCatalogs(final JsonNode value) {
        final JsonNode catalogs = array(value, "BAD_I18N_CATALOG", "catalogs");
        boundedArray(catalogs, "catalogs");
        for (JsonNode item : catalogs) {
            final ObjectNode catalog = object(item, "BAD_I18N_CATALOG", "catalog");
            exact(
                catalog,
                Set.of("locale", "state", "keyCount"),
                Set.of(),
                "UNKNOWN_FIELD",
                "catalog"
            );
            boundedText(catalog, "locale", 128, "BAD_I18N_CATALOG");
            enumText(catalog, "state", CATALOG_STATES, "BAD_I18N_CATALOG");
            nonnegative(catalog, "keyCount", "BAD_I18N_CATALOG");
        }
    }

    private static void validateMissingKeys(final JsonNode value) {
        final JsonNode entries = array(value, "BAD_I18N_MISSING", "missingKeys");
        boundedArray(entries, "missingKeys");
        for (JsonNode item : entries) {
            final ObjectNode entry = object(item, "BAD_I18N_MISSING", "missing key");
            exact(
                entry,
                Set.of("key", "locale", "marker", "count"),
                Set.of(),
                "UNKNOWN_FIELD",
                "missing key"
            );
            boundedText(entry, "key", 512, "BAD_I18N_MISSING");
            boundedText(entry, "locale", 128, "BAD_I18N_MISSING");
            boundedText(entry, "marker", 1024, "BAD_I18N_MISSING");
            positive(entry, "count", "BAD_I18N_MISSING");
        }
    }

    private static void validateMalformedPatterns(final JsonNode value) {
        final JsonNode entries = array(
            value,
            "BAD_I18N_PATTERN",
            "malformedPatterns"
        );
        boundedArray(entries, "malformedPatterns");
        for (JsonNode item : entries) {
            final ObjectNode entry = object(item, "BAD_I18N_PATTERN", "malformed pattern");
            exact(
                entry,
                Set.of("key", "locale", "code", "marker", "count"),
                Set.of(),
                "UNKNOWN_FIELD",
                "malformed pattern"
            );
            boundedText(entry, "key", 512, "BAD_I18N_PATTERN");
            boundedText(entry, "locale", 128, "BAD_I18N_PATTERN");
            boundedText(entry, "code", 256, "BAD_I18N_PATTERN");
            boundedText(entry, "marker", 1024, "BAD_I18N_PATTERN");
            positive(entry, "count", "BAD_I18N_PATTERN");
        }
    }

    private static void validateSuppression(final JsonNode value) {
        final ObjectNode suppression = object(
            value,
            "BAD_I18N_SUPPRESSION",
            "suppression"
        );
        exact(
            suppression,
            Set.of(
                "missingWarningsEmitted", "missingWarningsSuppressed",
                "malformedWarningsEmitted", "malformedWarningsSuppressed"
            ),
            Set.of(),
            "UNKNOWN_FIELD",
            "suppression"
        );
        for (String field : fields(suppression)) {
            nonnegative(suppression, field, "BAD_I18N_SUPPRESSION");
        }
    }
}
