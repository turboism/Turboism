package dev.turboism.plugin.mcp;

import static dev.turboism.plugin.mcp.McpDomainSupport.envelope;
import static dev.turboism.plugin.mcp.McpSchemas.entry;
import static dev.turboism.plugin.mcp.McpSchemas.immutableMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Publishes an explicit, read-only ledger of registered MCP operation semantics. */
final class McpCapabilitiesDomain {

    static final String CAPABILITIES_READ = "turboism.capabilities.read";

    /** Longest tool name the filter accepts, matching the shared identifier budget. */
    private static final int MAX_TOOL_NAME_LENGTH = 4096;

    private static final String CODE_UNKNOWN_TOOL = "UNKNOWN_TOOL";

    private static final String CODE_INVALID_ARGUMENTS = "INVALID_ARGUMENTS";

    private static final String CODE_TOOL_NOT_REGISTERED = "TOOL_NOT_REGISTERED";

    /** Accepted {@code turboism.capabilities.read} arguments. */
    private static final java.util.Set<String> ALLOWED_ARGUMENTS = java.util.Set.of("tool", "includeCoverage");

    private final Supplier<McpToolCatalog> tools;

    McpCapabilitiesDomain(final Supplier<McpToolCatalog> tools) {
        this.tools = Objects.requireNonNull(tools, "tools");
    }

    McpToolCatalog tools() {
        return new McpToolCatalog(List.of(definition()), this::call);
    }

    Map<String, Object> call(final String name, final Map<String, Object> arguments) {
        if (!CAPABILITIES_READ.equals(name)) {
            return envelope(failure(CODE_UNKNOWN_TOOL));
        }
        final Map<String, Object> checked = arguments == null ? Map.of() : arguments;
        final String requestedTool = requestedTool(checked);
        final boolean includeCoverage = requestedIncludeCoverage(checked);
        final McpToolCatalog catalog = Objects.requireNonNull(tools.get(), "tools.get()");
        final List<Map<String, Object>> operations = new ArrayList<>();
        boolean matched = requestedTool == null;
        for (McpRegisteredTool registration : catalog.registrations()) {
            final Object rawName = registration.publicDefinition().get("name");
            if (!(rawName instanceof String toolName) || toolName.isBlank()) {
                throw new IllegalStateException("registered tool has no public name");
            }
            if (requestedTool != null && !requestedTool.equals(toolName)) continue;
            matched = true;
            operations.add(operation(registration, toolName));
        }
        if (!matched) {
            return envelope(immutableMap(
                    entry("ok", false),
                    entry("code", CODE_TOOL_NOT_REGISTERED),
                    entry("operations", List.of()),
                    entry("coverage", coverage(includeCoverage))));
        }
        return envelope(immutableMap(
                entry("ok", true),
                entry("operations", List.copyOf(operations)),
                entry("coverage", coverage(includeCoverage))));
    }

    /**
     * Returns the {@code coverage} member. Agents that only need one tool's semantics omit the
     * multi-hundred-kilobyte ledger; callers before this argument existed keep receiving it.
     */
    private static Object coverage(final boolean includeCoverage) {
        return includeCoverage ? McpSdkCoverageLedger.snapshot() : null;
    }

    private static Map<String, Object> operation(final McpRegisteredTool registration, final String toolName) {
        final LinkedHashMap<String, Object> operation = new LinkedHashMap<>();
        operation.put("name", toolName);
        operation.put("effect", registration.effect().name());
        operation.put("affinity", registration.affinity().name());
        operation.put("transactionEligible", registration.transactionEligible());
        if (registration.versionSupport().scoped()) {
            operation.put("providerCapabilityId", registration.versionSupport().providerCapabilityId());
            operation.put("supportedVersions", registration.versionSupport().supportedVersions());
            if (!registration.versionSupport().operations().isEmpty()) {
                operation.put(
                        "operations",
                        registration.versionSupport().operations().stream()
                                .map(McpCapabilitiesDomain::operationSupport)
                                .toList());
            }
        }
        return Collections.unmodifiableMap(operation);
    }

    /**
     * Returns the exact tool name filter, or {@code null} when the caller omitted it.
     *
     * @throws IllegalArgumentException when {@code arguments} carries an unknown or malformed member
     */
    private static String requestedTool(final Map<String, Object> arguments) {
        rejectUnknown(arguments);
        if (!arguments.containsKey("tool")) return null;
        if (!(arguments.get("tool") instanceof String tool) || tool.isBlank() || tool.length() > MAX_TOOL_NAME_LENGTH) {
            throw new IllegalArgumentException("tool must be a non-blank registered tool name");
        }
        return tool;
    }

    private static boolean requestedIncludeCoverage(final Map<String, Object> arguments) {
        if (!arguments.containsKey("includeCoverage")) return true;
        if (!(arguments.get("includeCoverage") instanceof Boolean include)) {
            throw new IllegalArgumentException("includeCoverage must be a boolean");
        }
        return include;
    }

    private static void rejectUnknown(final Map<String, Object> arguments) {
        for (String key : arguments.keySet()) {
            if (!ALLOWED_ARGUMENTS.contains(key)) {
                throw new IllegalArgumentException("unknown argument: " + key);
            }
        }
    }

    private static Map<String, Object> operationSupport(final McpVersionSupport.OperationSupport support) {
        return immutableMap(
                entry("operation", support.operation()),
                entry("availability", support.availability().name()),
                entry("effect", support.effect().name()),
                entry("transactionEligible", support.transactionEligible()),
                entry("undoVerification", support.undoVerification().name()),
                entry("supportedVersions", support.supportedVersions()),
                entry("reason", support.reason()));
    }

    private static Map<String, Object> failure(final String code) {
        return immutableMap(
                entry("ok", false), entry("code", code), entry("operations", List.of()), entry("coverage", null));
    }

    private static Map<String, Object> definition() {
        return immutableMap(
                entry("name", CAPABILITIES_READ),
                entry("title", "Read MCP operation capabilities"),
                entry(
                        "description",
                        "Lists the effect, execution affinity, and authoring-transaction "
                                + "eligibility of every registered Turboism MCP tool. Pass tool to read one "
                                + "tool, and includeCoverage=false to omit the SDK coverage ledger."),
                entry(
                        "inputSchema",
                        immutableMap(
                                entry("type", "object"),
                                entry(
                                        "properties",
                                        immutableMap(
                                                entry(
                                                        "tool",
                                                        immutableMap(
                                                                entry("type", "string"),
                                                                entry("minLength", 1),
                                                                entry("maxLength", MAX_TOOL_NAME_LENGTH),
                                                                entry(
                                                                        "description",
                                                                        "Exact registered tool name to report."))),
                                                entry(
                                                        "includeCoverage",
                                                        immutableMap(
                                                                entry("type", "boolean"),
                                                                entry("default", true),
                                                                entry(
                                                                        "description",
                                                                        "Include the SDK coverage ledger; defaults to true."))))),
                                entry("additionalProperties", false))),
                entry("outputSchema", outputSchema()),
                entry(
                        "annotations",
                        immutableMap(
                                entry("readOnlyHint", true),
                                entry("destructiveHint", false),
                                entry("idempotentHint", true))));
    }

    private static Map<String, Object> outputSchema() {
        final Map<String, Object> operationSupport = immutableMap(
                entry("type", "object"),
                entry(
                        "properties",
                        immutableMap(
                                entry("operation", immutableMap(entry("type", "string"))),
                                entry(
                                        "availability",
                                        immutableMap(
                                                entry("type", "string"),
                                                entry(
                                                        "enum",
                                                        java.util.Arrays.stream(McpVersionSupport.Availability.values())
                                                                .map(Enum::name)
                                                                .toList()))),
                                entry(
                                        "effect",
                                        immutableMap(
                                                entry("type", "string"),
                                                entry(
                                                        "enum",
                                                        java.util.Arrays.stream(McpOperationEffect.values())
                                                                .map(Enum::name)
                                                                .toList()))),
                                entry("transactionEligible", immutableMap(entry("type", "boolean"))),
                                entry(
                                        "undoVerification",
                                        immutableMap(
                                                entry("type", "string"),
                                                entry(
                                                        "enum",
                                                        java.util.Arrays.stream(
                                                                        McpVersionSupport.UndoVerification.values())
                                                                .map(Enum::name)
                                                                .toList()))),
                                entry(
                                        "supportedVersions",
                                        immutableMap(
                                                entry("type", "array"),
                                                entry("items", immutableMap(entry("type", "string"))))),
                                entry("reason", immutableMap(entry("type", "string"))))),
                entry(
                        "required",
                        List.of(
                                "operation",
                                "availability",
                                "effect",
                                "transactionEligible",
                                "undoVerification",
                                "supportedVersions",
                                "reason")),
                entry("additionalProperties", false));
        final Map<String, Object> operation = immutableMap(
                entry("type", "object"),
                entry(
                        "properties",
                        immutableMap(
                                entry("name", immutableMap(entry("type", "string"))),
                                entry(
                                        "effect",
                                        immutableMap(
                                                entry("type", "string"),
                                                entry(
                                                        "enum",
                                                        java.util.Arrays.stream(McpOperationEffect.values())
                                                                .map(Enum::name)
                                                                .toList()))),
                                entry(
                                        "affinity",
                                        immutableMap(
                                                entry("type", "string"),
                                                entry(
                                                        "enum",
                                                        java.util.Arrays.stream(McpExecutionAffinity.values())
                                                                .map(Enum::name)
                                                                .toList()))),
                                entry("transactionEligible", immutableMap(entry("type", "boolean"))),
                                entry("providerCapabilityId", immutableMap(entry("type", "string"))),
                                entry(
                                        "supportedVersions",
                                        immutableMap(
                                                entry("type", "array"),
                                                entry("items", immutableMap(entry("type", "string"))),
                                                entry("minItems", 1))),
                                entry(
                                        "operations",
                                        immutableMap(entry("type", "array"), entry("items", operationSupport))))),
                entry("required", List.of("name", "effect", "affinity", "transactionEligible")),
                entry("additionalProperties", false));
        return immutableMap(
                entry("type", "object"),
                entry(
                        "properties",
                        immutableMap(
                                entry("ok", immutableMap(entry("type", "boolean"))),
                                entry("code", immutableMap(entry("type", "string"))),
                                entry("operations", immutableMap(entry("type", "array"), entry("items", operation))),
                                entry(
                                        "coverage",
                                        immutableMap(entry(
                                                "oneOf",
                                                List.of(coverageSchema(), immutableMap(entry("type", "null")))))))),
                entry("required", List.of("ok", "operations", "coverage")),
                entry("additionalProperties", false));
    }

    private static Map<String, Object> coverageSchema() {
        final Map<String, Object> entrySchema = immutableMap(
                entry("type", "object"),
                entry(
                        "properties",
                        immutableMap(
                                entry("sdkMethod", immutableMap(entry("type", "string"))),
                                entry("semanticCapability", immutableMap(entry("type", "string"))),
                                entry("classification", immutableMap(entry("type", "string"))),
                                entry("endpoint", immutableMap(entry("type", "string"))),
                                entry("operation", immutableMap(entry("type", "string"))),
                                entry("effect", immutableMap(entry("type", "string"))),
                                entry("transactionEligible", immutableMap(entry("type", "boolean"))),
                                entry("undoVerification", immutableMap(entry("type", "string"))),
                                entry(
                                        "supportedVersions",
                                        immutableMap(
                                                entry("type", "array"),
                                                entry("items", immutableMap(entry("type", "string"))))),
                                entry("reason", immutableMap(entry("type", "string"))))),
                entry(
                        "required",
                        List.of(
                                "classification",
                                "endpoint",
                                "operation",
                                "effect",
                                "transactionEligible",
                                "undoVerification",
                                "supportedVersions",
                                "reason")),
                entry("additionalProperties", false));
        final Map<String, Object> exceptionSchema = immutableMap(
                entry("type", "object"),
                entry(
                        "properties",
                        immutableMap(
                                entry("endpoint", immutableMap(entry("type", "string"))),
                                entry("replacement", immutableMap(entry("type", "string"))),
                                entry("reason", immutableMap(entry("type", "string"))))),
                entry("required", List.of("endpoint", "replacement", "reason")),
                entry("additionalProperties", false));
        return immutableMap(
                entry("type", "object"),
                entry(
                        "properties",
                        immutableMap(
                                entry("schemaVersion", immutableMap(entry("type", "integer"))),
                                entry(
                                        "trackedOwners",
                                        immutableMap(
                                                entry("type", "array"),
                                                entry("items", immutableMap(entry("type", "string"))))),
                                entry("entries", immutableMap(entry("type", "array"), entry("items", entrySchema))),
                                entry(
                                        "temporaryPublicExceptions",
                                        immutableMap(entry("type", "array"), entry("items", exceptionSchema))))),
                entry("required", List.of("schemaVersion", "trackedOwners", "entries", "temporaryPublicExceptions")),
                entry("additionalProperties", false));
    }
}
