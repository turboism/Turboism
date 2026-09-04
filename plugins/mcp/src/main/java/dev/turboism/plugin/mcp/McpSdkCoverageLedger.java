package dev.turboism.plugin.mcp;

import dev.turboism.protocol.json.StrictJson;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Loads the build-guarded, machine-readable MCP decision ledger bundled with the plugin. */
final class McpSdkCoverageLedger {

    static final String RESOURCE = "META-INF/turboism/mcp-sdk-coverage.json";
    private static final Set<String> CLASSIFICATIONS = Set.of(
        "MCP_READ",
        "MCP_WRITE_UNDOABLE",
        "MCP_COMMAND_NON_UNDOABLE",
        "RUNTIME_UNAVAILABLE",
        "EXCLUDED_WITH_REASON"
    );

    private McpSdkCoverageLedger() {
    }

    static Map<String, Object> snapshot() {
        return Holder.SNAPSHOT;
    }

    private static Map<String, Object> load() {
        try (InputStream input = McpSdkCoverageLedger.class.getClassLoader()
            .getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("MCP SDK coverage ledger is missing: " + RESOURCE);
            }
            final Object parsed = StrictJson.parse(input.readAllBytes());
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new IllegalStateException("MCP SDK coverage ledger must be a JSON object");
            }
            final Map<String, Object> ledger = stringMap(map);
            if (!(ledger.get("schemaVersion") instanceof Number version)
                || version.intValue() != 1) {
                throw new IllegalStateException("Unsupported MCP SDK coverage schema version");
            }
            requireStringArray(ledger, "trackedOwners", false);
            validateEntries(ledger.get("entries"));
            validateExceptions(ledger.get("temporaryPublicExceptions"));
            return immutableJsonObject(ledger);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read MCP SDK coverage ledger", failure);
        }
    }

    private static void validateEntries(final Object value) {
        if (!(value instanceof List<?> entries) || entries.isEmpty()) {
            throw new IllegalStateException("MCP SDK coverage ledger entries must not be empty");
        }
        for (Object raw : entries) {
            if (!(raw instanceof Map<?, ?> map)) {
                throw new IllegalStateException("MCP SDK coverage entry must be an object");
            }
            final Map<String, Object> entry = stringMap(map);
            final boolean sdkMethod = entry.get("sdkMethod") instanceof String;
            final boolean semantic = entry.get("semanticCapability") instanceof String;
            if (sdkMethod == semantic) {
                throw new IllegalStateException(
                    "MCP SDK coverage entry requires exactly one identity"
                );
            }
            final String classification = requireString(entry, "classification");
            if (!CLASSIFICATIONS.contains(classification)) {
                throw new IllegalStateException(
                    "Unknown MCP SDK coverage classification: " + classification
                );
            }
            requireString(entry, "endpoint");
            requireString(entry, "operation");
            requireString(entry, "effect");
            if (!(entry.get("transactionEligible") instanceof Boolean)) {
                throw new IllegalStateException(
                    "MCP SDK coverage transactionEligible must be boolean"
                );
            }
            requireString(entry, "undoVerification");
            requireStringArray(entry, "supportedVersions", true);
            requireString(entry, "reason");
        }
    }

    private static void validateExceptions(final Object value) {
        if (!(value instanceof List<?> exceptions)) {
            throw new IllegalStateException(
                "MCP SDK coverage temporaryPublicExceptions must be an array"
            );
        }
        for (Object raw : exceptions) {
            if (!(raw instanceof Map<?, ?> map)) {
                throw new IllegalStateException("MCP public exception must be an object");
            }
            final Map<String, Object> exception = stringMap(map);
            requireNonBlank(exception, "endpoint");
            requireNonBlank(exception, "replacement");
            requireNonBlank(exception, "reason");
        }
    }

    private static List<String> requireStringArray(
        final Map<String, Object> object,
        final String field,
        final boolean emptyAllowed
    ) {
        final Object value = object.get(field);
        if (!(value instanceof List<?> values) || (!emptyAllowed && values.isEmpty())) {
            throw new IllegalStateException(field + " must be a string array");
        }
        final ArrayList<String> result = new ArrayList<>(values.size());
        for (Object item : values) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new IllegalStateException(field + " must contain non-blank strings");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static String requireNonBlank(
        final Map<String, Object> object,
        final String field
    ) {
        final String value = requireString(object, field);
        if (value.isBlank()) throw new IllegalStateException(field + " must not be blank");
        return value;
    }

    private static String requireString(
        final Map<String, Object> object,
        final String field
    ) {
        final Object value = object.get(field);
        if (!(value instanceof String text)) {
            throw new IllegalStateException(field + " must be a string");
        }
        return text;
    }

    private static Map<String, Object> immutableJsonObject(final Map<?, ?> source) {
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalStateException("coverage JSON object key must be a string");
            }
            result.put(key, immutableJson(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Object immutableJson(final Object value) {
        if (value instanceof Map<?, ?> map) return immutableJsonObject(map);
        if (value instanceof List<?> list) {
            final ArrayList<Object> result = new ArrayList<>(list.size());
            for (Object item : list) result.add(immutableJson(item));
            return List.copyOf(result);
        }
        return value;
    }

    private static Map<String, Object> stringMap(final Map<?, ?> source) {
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalStateException("coverage JSON object key must be a string");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static final class Holder {
        private static final Map<String, Object> SNAPSHOT = load();
    }
}
