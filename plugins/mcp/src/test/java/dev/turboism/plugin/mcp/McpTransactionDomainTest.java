package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionReceipt;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionWork;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpTransactionDomainTest {

    @Test
    void attachDecoratesTheCombinedCatalogOnceAndPublishesTheTransactionTool() {
        final AtomicInteger decorations = new AtomicInteger();
        final McpToolCatalog base = new McpToolCatalog(
            List.of(Map.of(
                "name", "turboism.fixture.read",
                "inputSchema", Map.of("type", "object"),
                "outputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of("ok", Map.of("type", "boolean")),
                    "required", List.of("ok"),
                    "additionalProperties", false
                )
            )),
            (name, arguments) -> envelope(Map.of("ok", true))
        );

        final McpToolCatalog attached = McpTransactionDomain.attach(
            base,
            AuthoringTransactionService.unavailable(),
            new McpExecutionBridge(immediateScheduler()),
            catalog -> {
                decorations.incrementAndGet();
                return catalog;
            }
        );

        assertEquals(1, decorations.get());
        assertEquals(
            Set.of(
                "turboism.fixture.read",
                McpTransactionDomain.TRANSACTION_EXECUTE,
                McpCapabilitiesDomain.CAPABILITIES_READ
            ),
            attached.definitions().stream()
                .map(definition -> (String) definition.get("name"))
                .collect(java.util.stream.Collectors.toSet())
        );

        final Map<String, Object> capabilityOutput = output(attached.call(
            McpCapabilitiesDomain.CAPABILITIES_READ,
            Map.of()
        ));
        assertTrue((Boolean) capabilityOutput.get("ok"));
        final Map<String, Map<String, Object>> operations = array(
            capabilityOutput.get("operations")
        ).stream().map(McpTransactionDomainTest::object).collect(
            java.util.stream.Collectors.toMap(
                operation -> (String) operation.get("name"),
                operation -> operation
            )
        );
        assertEquals(
            "TRANSACTION_CONTROL",
            operations.get(McpTransactionDomain.TRANSACTION_EXECUTE).get("effect")
        );
        assertEquals(
            false,
            operations.get(McpTransactionDomain.TRANSACTION_EXECUTE).get("transactionEligible")
        );
        assertFalse(operations.values().stream().anyMatch(operation ->
            operation.containsKey("handler") || operation.containsKey("rawHandler")
        ));
    }

    @Test
    void executesEligibleStepsInOrderAndResolvesBackwardJsonPointerReferences() {
        final AtomicReference<Map<String, Object>> writeArguments = new AtomicReference<>();
        final FakeToolSource tools = new FakeToolSource();
        tools.register(
            "turboism.glues.read",
            McpOperationEffect.READ,
            true,
            arguments -> envelope(Map.of(
                "ok", true,
                "glue", Map.of("id", "GlueA", "intensity", 0.4)
            ))
        );
        tools.register(
            "turboism.glues.write",
            McpOperationEffect.UNDOABLE_WRITE,
            true,
            arguments -> {
                writeArguments.set(arguments);
                return envelope(Map.of("ok", true, "changed", true));
            }
        );
        final RecordingAuthoringService transactions = new RecordingAuthoringService();
        final McpTransactionDomain domain = new McpTransactionDomain(tools, transactions);

        final Map<String, Object> result = output(domain.call(
            McpTransactionDomain.TRANSACTION_EXECUTE,
            Map.of(
                "label", "Adjust GlueA",
                "steps", List.of(
                    Map.of(
                        "id", "read",
                        "tool", "turboism.glues.read",
                        "arguments", Map.of()
                    ),
                    Map.of(
                        "id", "write",
                        "tool", "turboism.glues.write",
                        "arguments", Map.of(
                            "glueId", Map.of(
                                "$ref", Map.of(
                                    "step", "read",
                                    "pointer", "/glue/id"
                                )
                            ),
                            "intensity", 0.75
                        )
                    )
                )
            )
        ));

        assertTrue((Boolean) result.get("ok"));
        assertEquals("COMMITTED", result.get("outcome"));
        assertEquals("Adjust GlueA", transactions.label.get());
        assertEquals("GlueA", writeArguments.get().get("glueId"));
        assertEquals(0.75, writeArguments.get().get("intensity"));
        assertEquals(List.of("turboism.glues.read", "turboism.glues.write"), tools.calls);
        assertEquals(2, array(result.get("steps")).size());
        assertTrue(object(result.get("receipt")).containsKey("historyEntryId"));
    }

    @Test
    void rejectsIneligibleOrNonAuthoringToolsBeforeOpeningTransaction() {
        final FakeToolSource tools = new FakeToolSource();
        tools.register(
            "turboism.history.undo",
            McpOperationEffect.HISTORY_CONTROL,
            false,
            arguments -> envelope(Map.of("ok", true))
        );
        final RecordingAuthoringService transactions = new RecordingAuthoringService();
        final McpTransactionDomain domain = new McpTransactionDomain(tools, transactions);

        final Map<String, Object> result = output(domain.call(
            McpTransactionDomain.TRANSACTION_EXECUTE,
            Map.of(
                "label", "Invalid",
                "steps", List.of(Map.of(
                    "id", "undo",
                    "tool", "turboism.history.undo",
                    "arguments", Map.of(
                        "expectedGeneration", 1,
                        "expectedRevision", 2,
                        "steps", 1
                    )
                ))
            )
        ));

        assertFalse((Boolean) result.get("ok"));
        assertEquals("REJECTED_REQUEST", result.get("outcome"));
        assertEquals("mcp.transaction.tool_not_eligible", result.get("diagnosticId"));
        assertEquals(0, transactions.invocations.get());
        assertEquals(List.of(), tools.calls);
    }

    @Test
    void rejectsForwardAndMissingReferencesBeforeOpeningTransaction() {
        final FakeToolSource tools = new FakeToolSource();
        tools.register(
            "turboism.glues.write",
            McpOperationEffect.UNDOABLE_WRITE,
            true,
            arguments -> envelope(Map.of("ok", true))
        );
        final RecordingAuthoringService transactions = new RecordingAuthoringService();
        final McpTransactionDomain domain = new McpTransactionDomain(tools, transactions);

        final Map<String, Object> result = output(domain.call(
            McpTransactionDomain.TRANSACTION_EXECUTE,
            Map.of(
                "label", "Forward reference",
                "steps", List.of(Map.of(
                    "id", "first",
                    "tool", "turboism.glues.write",
                    "arguments", Map.of(
                        "glueId", Map.of(
                            "$ref", Map.of("step", "later", "pointer", "/id")
                        )
                    )
                ))
            )
        ));

        assertFalse((Boolean) result.get("ok"));
        assertEquals("REJECTED_REQUEST", result.get("outcome"));
        assertEquals("mcp.transaction.invalid_reference", result.get("diagnosticId"));
        assertEquals(0, transactions.invocations.get());
        assertEquals(List.of(), tools.calls);
    }

    @Test
    void failedChildStepIsReportedAndCausesTypedRollbackOutcome() {
        final FakeToolSource tools = new FakeToolSource();
        tools.register(
            "turboism.glues.write",
            McpOperationEffect.UNDOABLE_WRITE,
            true,
            arguments -> envelope(Map.of(
                "ok", false,
                "code", "GLUE_STALE",
                "message", "Glue changed"
            ))
        );
        final RecordingAuthoringService transactions = new RecordingAuthoringService();
        final McpTransactionDomain domain = new McpTransactionDomain(tools, transactions);

        final Map<String, Object> result = output(domain.call(
            McpTransactionDomain.TRANSACTION_EXECUTE,
            Map.of(
                "label", "Rollback",
                "steps", List.of(Map.of(
                    "id", "write",
                    "tool", "turboism.glues.write",
                    "arguments", Map.of("glueId", "GlueA", "intensity", 0.9)
                ))
            )
        ));

        assertFalse((Boolean) result.get("ok"));
        assertEquals("ROLLED_BACK", result.get("outcome"));
        assertEquals(1, array(result.get("steps")).size());
        final Map<String, Object> failedStep = object(array(result.get("steps")).get(0));
        assertEquals(false, object(failedStep.get("output")).get("ok"));
        assertEquals("mcp.transaction.step_failed", failedStep.get("diagnosticId"));
    }

    @Test
    void rejectsConcreteInputSchemaViolationsBeforeOpeningTransaction() {
        final FakeToolSource tools = new FakeToolSource();
        tools.register(
            "turboism.fixture.write",
            McpOperationEffect.UNDOABLE_WRITE,
            true,
            objectSchema(Map.of("id", Map.of("type", "string")), List.of("id")),
            objectSchema(Map.of("ok", Map.of("type", "boolean")), List.of("ok")),
            arguments -> envelope(Map.of("ok", true))
        );
        final RecordingAuthoringService transactions = new RecordingAuthoringService();
        final McpTransactionDomain domain = new McpTransactionDomain(tools, transactions);

        final Map<String, Object> result = output(domain.call(
            McpTransactionDomain.TRANSACTION_EXECUTE,
            Map.of(
                "label", "Invalid input",
                "steps", List.of(Map.of(
                    "id", "write",
                    "tool", "turboism.fixture.write",
                    "arguments", Map.of("id", 42)
                ))
            )
        ));

        assertFalse((Boolean) result.get("ok"));
        assertEquals("mcp.transaction.input_schema_mismatch", result.get("diagnosticId"));
        assertEquals(0, transactions.invocations.get());
        assertEquals(List.of(), tools.calls);
    }

    @Test
    void rejectsReferenceSchemaMismatchBeforeOpeningTransaction() {
        final FakeToolSource tools = new FakeToolSource();
        tools.register(
            "turboism.fixture.read",
            McpOperationEffect.READ,
            true,
            objectSchema(Map.of(), List.of()),
            objectSchema(
                Map.of(
                    "ok", Map.of("type", "boolean"),
                    "value", Map.of("type", "integer")
                ),
                List.of("ok", "value")
            ),
            arguments -> envelope(Map.of("ok", true, "value", 7))
        );
        tools.register(
            "turboism.fixture.write",
            McpOperationEffect.UNDOABLE_WRITE,
            true,
            objectSchema(Map.of("id", Map.of("type", "string")), List.of("id")),
            objectSchema(Map.of("ok", Map.of("type", "boolean")), List.of("ok")),
            arguments -> envelope(Map.of("ok", true))
        );
        final RecordingAuthoringService transactions = new RecordingAuthoringService();
        final McpTransactionDomain domain = new McpTransactionDomain(tools, transactions);

        final Map<String, Object> result = output(domain.call(
            McpTransactionDomain.TRANSACTION_EXECUTE,
            Map.of(
                "label", "Incompatible reference",
                "steps", List.of(
                    Map.of(
                        "id", "read",
                        "tool", "turboism.fixture.read",
                        "arguments", Map.of()
                    ),
                    Map.of(
                        "id", "write",
                        "tool", "turboism.fixture.write",
                        "arguments", Map.of(
                            "id", Map.of("$ref", Map.of(
                                "step", "read",
                                "pointer", "/value"
                            ))
                        )
                    )
                )
            )
        ));

        assertFalse((Boolean) result.get("ok"));
        assertEquals(
            "mcp.transaction.reference_schema_incompatible",
            result.get("diagnosticId")
        );
        assertEquals(0, transactions.invocations.get());
        assertEquals(List.of(), tools.calls);
    }

    @Test
    void childOutputSchemaMismatchStopsExecutionAndRollsBack() {
        final FakeToolSource tools = new FakeToolSource();
        tools.register(
            "turboism.fixture.write",
            McpOperationEffect.UNDOABLE_WRITE,
            true,
            objectSchema(Map.of(), List.of()),
            objectSchema(
                Map.of(
                    "ok", Map.of("type", "boolean"),
                    "value", Map.of("type", "string")
                ),
                List.of("ok", "value")
            ),
            arguments -> envelope(Map.of("ok", true, "value", 7))
        );
        final RecordingAuthoringService transactions = new RecordingAuthoringService();
        final McpTransactionDomain domain = new McpTransactionDomain(tools, transactions);

        final Map<String, Object> result = output(domain.call(
            McpTransactionDomain.TRANSACTION_EXECUTE,
            Map.of(
                "label", "Invalid child output",
                "steps", List.of(Map.of(
                    "id", "write",
                    "tool", "turboism.fixture.write",
                    "arguments", Map.of()
                ))
            )
        ));

        assertFalse((Boolean) result.get("ok"));
        assertEquals("ROLLED_BACK", result.get("outcome"));
        assertEquals(1, transactions.invocations.get());
        assertEquals(List.of("turboism.fixture.write"), tools.calls);
        assertEquals(
            "mcp.transaction.step_output_schema_mismatch",
            object(array(result.get("steps")).get(0)).get("diagnosticId")
        );
    }

    @Test
    void rejectsArgumentTreesDeeperThanTheTransactionLimit() {
        final FakeToolSource tools = new FakeToolSource();
        tools.register(
            "turboism.fixture.read",
            McpOperationEffect.READ,
            true,
            arguments -> envelope(Map.of("ok", true))
        );
        final RecordingAuthoringService transactions = new RecordingAuthoringService();
        final McpTransactionDomain domain = new McpTransactionDomain(tools, transactions);
        Object nested = "leaf";
        for (int depth = 0; depth < 40; depth++) nested = Map.of("next", nested);

        final Map<String, Object> result = output(domain.call(
            McpTransactionDomain.TRANSACTION_EXECUTE,
            Map.of(
                "label", "Too deep",
                "steps", List.of(Map.of(
                    "id", "read",
                    "tool", "turboism.fixture.read",
                    "arguments", Map.of("nested", nested)
                ))
            )
        ));

        assertFalse((Boolean) result.get("ok"));
        assertEquals("mcp.transaction.payload_too_deep", result.get("diagnosticId"));
        assertEquals(0, transactions.invocations.get());
        assertEquals(List.of(), tools.calls);
    }

    @Test
    void unavailableAuthoringServiceNeverInvokesChildTools() {
        final FakeToolSource tools = new FakeToolSource();
        tools.register(
            "turboism.glues.read",
            McpOperationEffect.READ,
            true,
            arguments -> envelope(Map.of("ok", true))
        );
        final McpTransactionDomain domain = new McpTransactionDomain(
            tools,
            AuthoringTransactionService.unavailable()
        );

        final Map<String, Object> result = output(domain.call(
            McpTransactionDomain.TRANSACTION_EXECUTE,
            Map.of(
                "label", "Unavailable",
                "steps", List.of(Map.of(
                    "id", "read",
                    "tool", "turboism.glues.read",
                    "arguments", Map.of()
                ))
            )
        ));

        assertFalse((Boolean) result.get("ok"));
        assertEquals("UNAVAILABLE", result.get("outcome"));
        assertEquals(List.of(), tools.calls);
        assertEquals(List.of(), array(result.get("steps")));
    }

    private static final class FakeToolSource implements McpTransactionDomain.ToolSource {
        private final Map<String, McpTransactionDomain.ToolDescriptor> descriptors =
            new LinkedHashMap<>();
        private final Map<String, java.util.function.Function<Map<String, Object>, Map<String, Object>>>
            handlers = new LinkedHashMap<>();
        private final List<String> calls = new ArrayList<>();

        private void register(
            final String name,
            final McpOperationEffect effect,
            final boolean eligible,
            final java.util.function.Function<Map<String, Object>, Map<String, Object>> handler
        ) {
            register(name, effect, eligible, Map.of(), Map.of(), handler);
        }

        private void register(
            final String name,
            final McpOperationEffect effect,
            final boolean eligible,
            final Map<String, Object> inputSchema,
            final Map<String, Object> outputSchema,
            final java.util.function.Function<Map<String, Object>, Map<String, Object>> handler
        ) {
            descriptors.put(
                name,
                new McpTransactionDomain.ToolDescriptor(
                    effect,
                    eligible,
                    inputSchema,
                    outputSchema
                )
            );
            handlers.put(name, handler);
        }

        @Override
        public Optional<McpTransactionDomain.ToolDescriptor> descriptor(final String name) {
            return Optional.ofNullable(descriptors.get(name));
        }

        @Override
        public Map<String, Object> callRaw(
            final String name,
            final Map<String, Object> arguments
        ) {
            calls.add(name);
            return handlers.get(name).apply(arguments);
        }
    }

    private static final class RecordingAuthoringService implements AuthoringTransactionService {
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicReference<String> label = new AtomicReference<>();

        @Override
        public <T> AuthoringTransactionResult<T> execute(
            final AuthoringTransactionOptions options,
            final AuthoringTransactionWork<T> work
        ) {
            invocations.incrementAndGet();
            label.set(options.label());
            final HistorySnapshot before = history(4, 8, 0, List.of());
            try {
                final T value = work.run();
                final HistorySnapshot after = history(
                    4,
                    9,
                    1,
                    List.of(new HistoryEntry(0, options.label(), true))
                );
                return AuthoringTransactionResult.committed(
                    value,
                    new AuthoringTransactionReceipt(
                        "transaction-1",
                        options.label(),
                        before,
                        after,
                        Optional.of("history-entry-1")
                    )
                );
            } catch (Exception failure) {
                return AuthoringTransactionResult.rolledBack(
                    new AuthoringTransactionReceipt(
                        "transaction-1",
                        options.label(),
                        before,
                        before,
                        Optional.empty()
                    ),
                    "runtime.transaction.rolled_back"
                );
            }
        }
    }

    private static HistorySnapshot history(
        final long generation,
        final long revision,
        final int position,
        final List<HistoryEntry> entries
    ) {
        return new HistorySnapshot(
            HistorySnapshot.Availability.AVAILABLE,
            generation,
            revision,
            position,
            entries,
            position > 0,
            position < entries.size()
        );
    }

    private static Map<String, Object> objectSchema(
        final Map<String, Object> properties,
        final List<String> required
    ) {
        return Map.of(
            "type", "object",
            "properties", properties,
            "required", required,
            "additionalProperties", false
        );
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

    private static Map<String, Object> envelope(final Map<String, Object> output) {
        return Map.of(
            "content", List.of(Map.of("type", "text", "text", "fixture")),
            "structuredContent", output,
            "isError", Boolean.FALSE
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(final Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("structuredContent");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(final Object value) {
        return (List<Object>) value;
    }
}
