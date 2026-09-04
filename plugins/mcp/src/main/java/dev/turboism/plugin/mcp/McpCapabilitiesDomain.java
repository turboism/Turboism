package dev.turboism.plugin.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Publishes an explicit, read-only ledger of registered MCP operation semantics. */
final class McpCapabilitiesDomain {

    static final String CAPABILITIES_READ = "turboism.capabilities.read";

    private final Supplier<McpToolCatalog> tools;

    McpCapabilitiesDomain(final Supplier<McpToolCatalog> tools) {
        this.tools = Objects.requireNonNull(tools, "tools");
    }

    McpToolCatalog tools() {
        return new McpToolCatalog(List.of(definition()), this::call);
    }

    Map<String, Object> call(final String name, final Map<String, Object> arguments) {
        if (!CAPABILITIES_READ.equals(name)) {
            return envelope(failure("UNKNOWN_TOOL"));
        }
        if (arguments != null && !arguments.isEmpty()) {
            return envelope(failure("INVALID_ARGUMENTS"));
        }
        final McpToolCatalog catalog = Objects.requireNonNull(tools.get(), "tools.get()");
        final List<Map<String, Object>> operations = new ArrayList<>();
        for (McpRegisteredTool registration : catalog.registrations()) {
            final Object rawName = registration.publicDefinition().get("name");
            if (!(rawName instanceof String toolName) || toolName.isBlank()) {
                throw new IllegalStateException("registered tool has no public name");
            }
            final LinkedHashMap<String, Object> operation = new LinkedHashMap<>();
            operation.put("name", toolName);
            operation.put("effect", registration.effect().name());
            operation.put("affinity", registration.affinity().name());
            operation.put("transactionEligible", registration.transactionEligible());
            if (registration.versionSupport().scoped()) {
                operation.put(
                    "providerCapabilityId",
                    registration.versionSupport().providerCapabilityId()
                );
                operation.put(
                    "supportedVersions",
                    registration.versionSupport().supportedVersions()
                );
                if (!registration.versionSupport().operations().isEmpty()) {
                    operation.put(
                        "operations",
                        registration.versionSupport().operations().stream()
                            .map(McpCapabilitiesDomain::operationSupport)
                            .toList()
                    );
                }
            }
            operations.add(Collections.unmodifiableMap(operation));
        }
        operations.sort(Comparator.comparing(operation -> (String) operation.get("name")));
        return envelope(immutableMap(
            entry("ok", true),
            entry("operations", List.copyOf(operations)),
            entry("coverage", McpSdkCoverageLedger.snapshot())
        ));
    }

    private static Map<String, Object> operationSupport(
        final McpVersionSupport.OperationSupport support
    ) {
        return immutableMap(
            entry("operation", support.operation()),
            entry("availability", support.availability().name()),
            entry("effect", support.effect().name()),
            entry("transactionEligible", support.transactionEligible()),
            entry("undoVerification", support.undoVerification().name()),
            entry("supportedVersions", support.supportedVersions()),
            entry("reason", support.reason())
        );
    }

    private static Map<String, Object> failure(final String code) {
        return immutableMap(
            entry("ok", false),
            entry("code", code),
            entry("operations", List.of())
        );
    }

    private static Map<String, Object> envelope(final Map<String, Object> output) {
        return immutableMap(
            entry("content", List.of(immutableMap(
                entry("type", "text"),
                entry("text", Json.stringify(output))
            ))),
            entry("structuredContent", output),
            entry("isError", !Boolean.TRUE.equals(output.get("ok")))
        );
    }

    private static Map<String, Object> definition() {
        return immutableMap(
            entry("name", CAPABILITIES_READ),
            entry("title", "Read MCP operation capabilities"),
            entry("description", "Lists the effect, execution affinity, and authoring-transaction "
                + "eligibility of every registered Turboism MCP tool."),
            entry("inputSchema", immutableMap(
                entry("type", "object"),
                entry("properties", Map.of()),
                entry("additionalProperties", false)
            )),
            entry("outputSchema", outputSchema()),
            entry("annotations", immutableMap(
                entry("readOnlyHint", true),
                entry("destructiveHint", false),
                entry("idempotentHint", true)
            ))
        );
    }

    private static Map<String, Object> outputSchema() {
        final Map<String, Object> operationSupport = immutableMap(
            entry("type", "object"),
            entry("properties", immutableMap(
                entry("operation", immutableMap(entry("type", "string"))),
                entry("availability", immutableMap(
                    entry("type", "string"),
                    entry("enum", java.util.Arrays.stream(
                        McpVersionSupport.Availability.values()
                    ).map(Enum::name).toList())
                )),
                entry("effect", immutableMap(
                    entry("type", "string"),
                    entry("enum", java.util.Arrays.stream(McpOperationEffect.values())
                        .map(Enum::name)
                        .toList())
                )),
                entry("transactionEligible", immutableMap(entry("type", "boolean"))),
                entry("undoVerification", immutableMap(
                    entry("type", "string"),
                    entry("enum", java.util.Arrays.stream(
                        McpVersionSupport.UndoVerification.values()
                    ).map(Enum::name).toList())
                )),
                entry("supportedVersions", immutableMap(
                    entry("type", "array"),
                    entry("items", immutableMap(entry("type", "string")))
                )),
                entry("reason", immutableMap(entry("type", "string")))
            )),
            entry("required", List.of(
                "operation", "availability", "effect", "transactionEligible",
                "undoVerification", "supportedVersions", "reason"
            )),
            entry("additionalProperties", false)
        );
        final Map<String, Object> operation = immutableMap(
            entry("type", "object"),
            entry("properties", immutableMap(
                entry("name", immutableMap(entry("type", "string"))),
                entry("effect", immutableMap(
                    entry("type", "string"),
                    entry("enum", java.util.Arrays.stream(McpOperationEffect.values())
                        .map(Enum::name)
                        .toList())
                )),
                entry("affinity", immutableMap(
                    entry("type", "string"),
                    entry("enum", java.util.Arrays.stream(McpExecutionAffinity.values())
                        .map(Enum::name)
                        .toList())
                )),
                entry("transactionEligible", immutableMap(entry("type", "boolean"))),
                entry("providerCapabilityId", immutableMap(entry("type", "string"))),
                entry("supportedVersions", immutableMap(
                    entry("type", "array"),
                    entry("items", immutableMap(entry("type", "string"))),
                    entry("minItems", 1)
                )),
                entry("operations", immutableMap(
                    entry("type", "array"),
                    entry("items", operationSupport)
                ))
            )),
            entry("required", List.of("name", "effect", "affinity", "transactionEligible")),
            entry("additionalProperties", false)
        );
        return immutableMap(
            entry("type", "object"),
            entry("properties", immutableMap(
                entry("ok", immutableMap(entry("type", "boolean"))),
                entry("code", immutableMap(entry("type", "string"))),
                entry("operations", immutableMap(
                    entry("type", "array"),
                    entry("items", operation)
                )),
                entry("coverage", coverageSchema())
            )),
            entry("required", List.of("ok", "operations")),
            entry("additionalProperties", false)
        );
    }

    private static Map<String, Object> coverageSchema() {
        final Map<String, Object> entrySchema = immutableMap(
            entry("type", "object"),
            entry("properties", immutableMap(
                entry("sdkMethod", immutableMap(entry("type", "string"))),
                entry("semanticCapability", immutableMap(entry("type", "string"))),
                entry("classification", immutableMap(entry("type", "string"))),
                entry("endpoint", immutableMap(entry("type", "string"))),
                entry("operation", immutableMap(entry("type", "string"))),
                entry("effect", immutableMap(entry("type", "string"))),
                entry("transactionEligible", immutableMap(entry("type", "boolean"))),
                entry("undoVerification", immutableMap(entry("type", "string"))),
                entry("supportedVersions", immutableMap(
                    entry("type", "array"),
                    entry("items", immutableMap(entry("type", "string")))
                )),
                entry("reason", immutableMap(entry("type", "string")))
            )),
            entry("required", List.of(
                "classification", "endpoint", "operation", "effect",
                "transactionEligible", "undoVerification", "supportedVersions", "reason"
            )),
            entry("additionalProperties", false)
        );
        final Map<String, Object> exceptionSchema = immutableMap(
            entry("type", "object"),
            entry("properties", immutableMap(
                entry("endpoint", immutableMap(entry("type", "string"))),
                entry("replacement", immutableMap(entry("type", "string"))),
                entry("reason", immutableMap(entry("type", "string")))
            )),
            entry("required", List.of("endpoint", "replacement", "reason")),
            entry("additionalProperties", false)
        );
        return immutableMap(
            entry("type", "object"),
            entry("properties", immutableMap(
                entry("schemaVersion", immutableMap(entry("type", "integer"))),
                entry("trackedOwners", immutableMap(
                    entry("type", "array"),
                    entry("items", immutableMap(entry("type", "string")))
                )),
                entry("entries", immutableMap(
                    entry("type", "array"),
                    entry("items", entrySchema)
                )),
                entry("temporaryPublicExceptions", immutableMap(
                    entry("type", "array"),
                    entry("items", exceptionSchema)
                ))
            )),
            entry("required", List.of(
                "schemaVersion", "trackedOwners", "entries", "temporaryPublicExceptions"
            )),
            entry("additionalProperties", false)
        );
    }

    @SafeVarargs
    private static Map<String, Object> immutableMap(
        final Map.Entry<String, Object>... entries
    ) {
        final Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) {
            values.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(values);
    }

    private static Map.Entry<String, Object> entry(final String key, final Object value) {
        return Map.entry(key, value);
    }
}
