package dev.turboism.plugin.mcp;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class McpToolRegistryTest {

    @Test
    void uiThreadToolDispatchesStandaloneExactlyOnceAndRawInvocationDoesNotRedispatch() {
        final AtomicInteger dispatches = new AtomicInteger();
        final AtomicInteger invocations = new AtomicInteger();
        final UiScheduler scheduler = new UiScheduler() {
            @Override
            public Registration runOnUiThread(final Runnable work) {
                dispatches.incrementAndGet();
                work.run();
                return () -> { };
            }

            @Override
            public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
                throw new UnsupportedOperationException();
            }
        };
        final McpExecutionBridge execution = new McpExecutionBridge(scheduler);
        final McpRegisteredTool tool = McpRegisteredTool.typed(
            Map.of(
                "name", "turboism.test.write",
                "title", "Test write",
                "description", "Writes a test value.",
                "inputSchema", closedObjectSchema(),
                "outputSchema", closedOutputSchema()
            ),
            McpOperationEffect.UNDOABLE_WRITE,
            McpExecutionAffinity.UI_THREAD,
            true,
            execution,
            arguments -> {
                invocations.incrementAndGet();
                return envelope(Map.of("ok", true));
            }
        );
        final McpToolCatalog catalog = McpToolCatalog.of(List.of(tool));

        catalog.call("turboism.test.write", Map.of());
        assertEquals(1, dispatches.get());
        assertEquals(1, invocations.get());

        catalog.callRaw("turboism.test.write", Map.of());
        assertEquals(1, dispatches.get());
        assertEquals(2, invocations.get());
    }

    @Test
    void duplicatePublicNamesAreRejected() {
        final McpRegisteredTool first = directRead("turboism.test.duplicate");
        final McpRegisteredTool second = directRead("turboism.test.duplicate");

        assertThrows(IllegalArgumentException.class,
            () -> new McpToolRegistry(List.of(first, second)));
    }

    @Test
    void typedRegistrationRequiresBothSchemas() {
        final LinkedHashMap<String, Object> missingInput = baseDefinition("turboism.test.input");
        missingInput.remove("inputSchema");
        final LinkedHashMap<String, Object> missingOutput = baseDefinition("turboism.test.output");
        missingOutput.remove("outputSchema");

        assertThrows(IllegalArgumentException.class, () -> McpRegisteredTool.typed(
            missingInput,
            McpOperationEffect.READ,
            McpExecutionAffinity.DIRECT,
            false,
            new McpExecutionBridge(immediateScheduler()),
            arguments -> envelope(Map.of("ok", true))
        ));
        assertThrows(IllegalArgumentException.class, () -> McpRegisteredTool.typed(
            missingOutput,
            McpOperationEffect.READ,
            McpExecutionAffinity.DIRECT,
            false,
            new McpExecutionBridge(immediateScheduler()),
            arguments -> envelope(Map.of("ok", true))
        ));
    }

    @Test
    void nonAuthoringEffectsCannotBeTransactionEligible() {
        for (McpOperationEffect effect : List.of(
            McpOperationEffect.HISTORY_CONTROL,
            McpOperationEffect.EXTERNAL_SIDE_EFFECT,
            McpOperationEffect.TRANSACTION_CONTROL
        )) {
            assertThrows(IllegalArgumentException.class, () -> McpRegisteredTool.typed(
                baseDefinition("turboism.test." + effect.name().toLowerCase()),
                effect,
                McpExecutionAffinity.DIRECT,
                true,
                new McpExecutionBridge(immediateScheduler()),
                arguments -> envelope(Map.of("ok", true))
            ));
        }
    }

    @Test
    void typedRegistryProjectsOnlyThePublicDefinition() {
        final LinkedHashMap<String, Object> definition = baseDefinition("turboism.test.projected");
        definition.put("annotations", Map.of("readOnlyHint", true));
        final McpToolCatalog catalog = McpToolCatalog.of(List.of(McpRegisteredTool.typed(
            definition,
            McpOperationEffect.READ,
            McpExecutionAffinity.DIRECT,
            true,
            new McpExecutionBridge(immediateScheduler()),
            arguments -> envelope(Map.of("ok", true))
        )));

        assertEquals(definition, catalog.definitions().get(0));
        assertEquals(false, catalog.definitions().get(0).containsKey("effect"));
        assertEquals(false, catalog.definitions().get(0).containsKey("transactionEligible"));
    }

    @Test
    void transactionEligibleToolRejectsOpenOutputSchema() {
        final McpExecutionBridge execution = new McpExecutionBridge(immediateScheduler());
        final Map<String, Object> openOutput = Map.of(
            "type", "object",
            "properties", Map.of("ok", Map.of("type", "boolean")),
            "required", List.of("ok"),
            "additionalProperties", true
        );

        assertThrows(IllegalArgumentException.class, () -> McpRegisteredTool.typed(
            Map.of(
                "name", "turboism.test.open-output",
                "title", "Open output",
                "description", "Must not be transaction eligible.",
                "inputSchema", closedObjectSchema(),
                "outputSchema", openOutput
            ),
            McpOperationEffect.READ,
            McpExecutionAffinity.DIRECT,
            true,
            execution,
            arguments -> envelope(Map.of("ok", true))
        ));
    }

    @Test
    void diagnosticsDecorationPreservesTypedMetadataAndRawInvocation() {
        final AtomicInteger dispatches = new AtomicInteger();
        final McpExecutionBridge execution = new McpExecutionBridge(new UiScheduler() {
            @Override
            public Registration runOnUiThread(final Runnable work) {
                dispatches.incrementAndGet();
                work.run();
                return () -> { };
            }

            @Override
            public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
                throw new UnsupportedOperationException();
            }
        });
        final McpToolCatalog original = McpToolCatalog.of(List.of(McpRegisteredTool.typed(
            Map.of(
                "name", "turboism.test.observed",
                "title", "Observed write",
                "description", "Exercises the diagnostics decorator.",
                "inputSchema", closedObjectSchema(),
                "outputSchema", closedOutputSchema()
            ),
            McpOperationEffect.UNDOABLE_WRITE,
            McpExecutionAffinity.UI_THREAD,
            true,
            execution,
            arguments -> envelope(Map.of("ok", true))
        )));

        final McpToolCatalog observed = new McpRuntimeDiagnostics().observe(original);

        assertEquals(McpOperationEffect.UNDOABLE_WRITE,
            observed.registration("turboism.test.observed").effect());
        assertEquals(McpExecutionAffinity.UI_THREAD,
            observed.registration("turboism.test.observed").affinity());
        assertEquals(true,
            observed.registration("turboism.test.observed").transactionEligible());
        observed.callRaw("turboism.test.observed", Map.of());
        assertEquals(0, dispatches.get());
    }

    @Test
    void diagnosticsDecorationObservesRawInvocationFailures() {
        final McpRuntimeDiagnostics diagnostics = new McpRuntimeDiagnostics();
        final McpToolCatalog original = McpToolCatalog.of(List.of(McpRegisteredTool.typed(
            Map.of(
                "name", "turboism.test.raw-failure",
                "title", "Raw failure",
                "description", "Fails inside a transaction child invocation.",
                "inputSchema", closedObjectSchema(),
                "outputSchema", closedOutputSchema()
            ),
            McpOperationEffect.UNDOABLE_WRITE,
            McpExecutionAffinity.DIRECT,
            true,
            new McpExecutionBridge(immediateScheduler()),
            arguments -> { throw new IllegalStateException("raw failure"); }
        )));
        final McpToolCatalog observed = diagnostics.observe(original);

        assertThrows(IllegalStateException.class,
            () -> observed.callRaw("turboism.test.raw-failure", Map.of()));
        assertEquals(1, diagnostics.snapshot().events().size());
        assertEquals("turboism.test.raw-failure",
            diagnostics.snapshot().events().get(0).provider());
    }

    private static McpRegisteredTool directRead(final String name) {
        return McpRegisteredTool.typed(
            baseDefinition(name),
            McpOperationEffect.READ,
            McpExecutionAffinity.DIRECT,
            true,
            new McpExecutionBridge(immediateScheduler()),
            arguments -> envelope(Map.of("ok", true))
        );
    }

    private static LinkedHashMap<String, Object> baseDefinition(final String name) {
        final LinkedHashMap<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", name);
        definition.put("title", "Test tool");
        definition.put("description", "Test tool definition.");
        definition.put("inputSchema", closedObjectSchema());
        definition.put("outputSchema", closedOutputSchema());
        return definition;
    }

    private static UiScheduler immediateScheduler() {
        return new UiScheduler() {
            @Override
            public Registration runOnUiThread(final Runnable work) {
                work.run();
                return () -> { };
            }

            @Override
            public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static Map<String, Object> closedObjectSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(),
            "additionalProperties", false
        );
    }

    private static Map<String, Object> closedOutputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of("ok", Map.of("type", "boolean")),
            "required", List.of("ok"),
            "additionalProperties", false
        );
    }

    private static Map<String, Object> envelope(final Map<String, Object> output) {
        return Map.of(
            "content", List.of(Map.of(
                "type", "text",
                "text", Json.stringify(output)
            )),
            "structuredContent", output,
            "isError", false
        );
    }
}
