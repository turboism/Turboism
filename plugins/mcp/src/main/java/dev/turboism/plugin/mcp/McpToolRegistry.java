package dev.turboism.plugin.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Typed, duplicate-free owner of MCP tool definitions and invocation handlers. */
final class McpToolRegistry {

    private final List<McpRegisteredTool> registrations;
    private final List<Map<String, Object>> definitions;
    private final Map<String, McpRegisteredTool> byName;

    McpToolRegistry(final List<McpRegisteredTool> registrations) {
        Objects.requireNonNull(registrations, "registrations");
        final ArrayList<McpRegisteredTool> ordered = new ArrayList<>(registrations.size());
        final ArrayList<Map<String, Object>> projected = new ArrayList<>(registrations.size());
        final LinkedHashMap<String, McpRegisteredTool> indexed = new LinkedHashMap<>();
        for (McpRegisteredTool registration : registrations) {
            final McpRegisteredTool checked = Objects.requireNonNull(registration, "registration");
            if (indexed.putIfAbsent(checked.name(), checked) != null) {
                throw new IllegalArgumentException("Duplicate MCP tool: " + checked.name());
            }
            ordered.add(checked);
            projected.add(checked.publicDefinition());
        }
        this.registrations = List.copyOf(ordered);
        this.definitions = List.copyOf(projected);
        this.byName = Collections.unmodifiableMap(indexed);
    }

    List<McpRegisteredTool> registrations() {
        return registrations;
    }

    List<Map<String, Object>> definitions() {
        return definitions;
    }

    McpRegisteredTool registration(final String name) {
        final McpRegisteredTool registration = byName.get(name);
        if (registration == null) {
            throw new IllegalArgumentException("Unknown MCP tool: " + name);
        }
        return registration;
    }

    Map<String, Object> call(final String name, final Map<String, Object> arguments) {
        return registration(name).invokePublic(Objects.requireNonNull(arguments, "arguments"));
    }

    Map<String, Object> callRaw(final String name, final Map<String, Object> arguments) {
        final McpRegisteredTool registration = registration(name);
        if (!registration.transactionEligible()) {
            throw new IllegalArgumentException("MCP tool is not transaction-eligible: " + name);
        }
        return registration.invokeRaw(Objects.requireNonNull(arguments, "arguments"));
    }
}
