package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glue;
import dev.turboism.sdk.cubism.model.GlueId;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionReceipt;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionWork;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpGlueDomainTest {

    @Test
    void listReadReturnsCompleteGlueProjectionAndStateToken() {
        final FakeGlue glue = new FakeGlue(
            "GlueEyeL",
            "Left eye glue",
            0,
            0.45F,
            "ArtEyeL",
            "ArtFace",
            List.of("ParamEyeLOpen", "ParamAngleX")
        );
        final FakeFacade facade = new FakeFacade(List.of(glue));
        final McpGlueDomain domain = new McpGlueDomain(
            facade,
            new McpExecutionBridge(immediateScheduler())
        );

        final Map<String, Object> output = output(domain.tools().call(
            McpGlueDomain.GLUES_READ,
            Map.of("operation", "list")
        ));

        assertTrue((Boolean) output.get("ok"));
        assertEquals("list", output.get("operation"));
        assertEquals("AVAILABLE", output.get("availability"));
        assertEquals(
            List.of("5.2.03", "5.3.02", "5.3.03"),
            object(output.get("provider")).get("supportedVersions")
        );
        assertEquals("5.3.02", object(output.get("provider")).get("activeVersion"));
        final Map<String, Object> result = object(output.get("result"));
        assertEquals(1, result.get("total"));
        final Map<String, Object> projected = object(array(result.get("items")).get(0));
        assertEquals("GlueEyeL", projected.get("id"));
        assertEquals("Left eye glue", projected.get("name"));
        assertEquals(0, projected.get("index"));
        assertEquals(0.45F, ((Number) projected.get("intensity")).floatValue());
        assertEquals("ArtEyeL", projected.get("drawableAId"));
        assertEquals("ArtFace", projected.get("drawableBId"));
        assertEquals(List.of("ParamEyeLOpen", "ParamAngleX"), projected.get("parameterIds"));
        assertEquals("AVAILABLE", projected.get("availability"));

        final Map<String, Object> state = object(output.get("stateToken"));
        assertEquals("document-1", state.get("documentId"));
        assertEquals("model-1", state.get("modelId"));
        assertEquals(7L, ((Number) state.get("historyGeneration")).longValue());
        assertEquals(11L, ((Number) state.get("historyRevision")).longValue());
        assertEquals("5.3.02", state.get("providerVersion"));
    }

    @Test
    void getReadReturnsOneExactGlue() {
        final FakeFacade facade = new FakeFacade(List.of(
            new FakeGlue(
                "GlueEyeL", "Left eye glue", 0, 0.45F,
                "ArtEyeL", "ArtFace", List.of("ParamEyeLOpen")
            ),
            new FakeGlue(
                "GlueEyeR", "Right eye glue", 1, 0.55F,
                "ArtEyeR", "ArtFace", List.of("ParamEyeROpen")
            )
        ));
        final McpGlueDomain domain = new McpGlueDomain(
            facade,
            new McpExecutionBridge(immediateScheduler())
        );

        final Map<String, Object> output = output(domain.tools().call(
            McpGlueDomain.GLUES_READ,
            Map.of("operation", "get", "id", "GlueEyeR")
        ));

        assertTrue((Boolean) output.get("ok"));
        assertEquals("get", output.get("operation"));
        final Map<String, Object> glue = object(output.get("result"));
        assertEquals("GlueEyeR", glue.get("id"));
        assertEquals("Right eye glue", glue.get("name"));
        assertEquals(1, glue.get("index"));
        assertEquals("ArtEyeR", glue.get("drawableAId"));
    }

    @Test
    void setIntensityReturnsReadbackHistoryReceiptAndTypedRegistration() {
        final FakeGlue glue = new FakeGlue(
            "GlueEyeL", "Left eye glue", 0, 0.45F,
            "ArtEyeL", "ArtFace", List.of("ParamEyeLOpen")
        );
        final FakeFacade facade = new FakeFacade(List.of(glue));
        final McpGlueDomain domain = new McpGlueDomain(
            facade,
            new McpExecutionBridge(immediateScheduler())
        );
        final McpToolCatalog tools = domain.tools();
        final Map<String, Object> stateToken = object(output(tools.call(
            McpGlueDomain.GLUES_READ,
            Map.of("operation", "get", "id", "GlueEyeL")
        )).get("stateToken"));

        final Map<String, Object> output = output(tools.call(
            McpGlueDomain.GLUES_WRITE,
            Map.of(
                "operation", "set_intensity",
                "id", "GlueEyeL",
                "intensity", 0.8,
                "expectedState", stateToken
            )
        ));

        assertTrue((Boolean) output.get("ok"));
        assertEquals("APPLIED", output.get("outcome"));
        assertEquals("GlueEyeL", object(output.get("target")).get("id"));
        final Map<String, Object> result = object(output.get("result"));
        assertEquals(0.45F, ((Number) object(result.get("before")).get("intensity")).floatValue());
        assertEquals(0.8F, ((Number) object(result.get("readback")).get("intensity")).floatValue());
        final Map<String, Object> history = object(output.get("history"));
        assertEquals(11L, ((Number) history.get("revisionBefore")).longValue());
        assertEquals(12L, ((Number) history.get("revisionAfter")).longValue());
        assertEquals(1, history.get("entriesAdded"));
        assertEquals(
            McpOperationEffect.UNDOABLE_WRITE,
            tools.registration(McpGlueDomain.GLUES_WRITE).effect()
        );
        assertEquals(
            McpExecutionAffinity.UI_THREAD,
            tools.registration(McpGlueDomain.GLUES_WRITE).affinity()
        );
        assertEquals(true, tools.registration(McpGlueDomain.GLUES_WRITE).transactionEligible());
    }

    @Test
    void exposesAllFiveVerifiedGlueSettersWithFreshReadback() {
        final FakeGlue glue = new FakeGlue(
            "GlueEyeL", "Left eye glue", 0, 0.45F,
            "ArtEyeL", "ArtFace", List.of("ParamEyeLOpen")
        );
        final FakeFacade facade = new FakeFacade(List.of(glue));
        final McpToolCatalog tools = new McpGlueDomain(
            facade,
            new McpExecutionBridge(immediateScheduler())
        ).tools();

        Map<String, Object> state = state(tools, "GlueEyeL");
        assertApplied(tools.call(McpGlueDomain.GLUES_WRITE, Map.of(
            "operation", "set_name",
            "id", "GlueEyeL",
            "name", "Eye seam",
            "expectedState", state
        )));

        state = state(tools, "GlueEyeL");
        final Map<String, Object> rename = output(tools.call(
            McpGlueDomain.GLUES_WRITE,
            Map.of(
                "operation", "set_id",
                "id", "GlueEyeL",
                "newId", "GlueEyeLeft",
                "expectedState", state
            )
        ));
        assertEquals("APPLIED", rename.get("outcome"));
        assertEquals(
            "GlueEyeLeft",
            object(object(rename.get("result")).get("readback")).get("id")
        );

        state = state(tools, "GlueEyeLeft");
        assertApplied(tools.call(McpGlueDomain.GLUES_WRITE, Map.of(
            "operation", "set_drawable_a",
            "id", "GlueEyeLeft",
            "drawableId", "ArtEyeHighlight",
            "expectedState", state
        )));

        state = state(tools, "GlueEyeLeft");
        assertApplied(tools.call(McpGlueDomain.GLUES_WRITE, Map.of(
            "operation", "set_drawable_b",
            "id", "GlueEyeLeft",
            "drawableId", "ArtHead",
            "expectedState", state
        )));

        final Map<String, Object> readback = object(output(tools.call(
            McpGlueDomain.GLUES_READ,
            Map.of("operation", "get", "id", "GlueEyeLeft")
        )).get("result"));
        assertEquals("Eye seam", readback.get("name"));
        assertEquals("ArtEyeHighlight", readback.get("drawableAId"));
        assertEquals("ArtHead", readback.get("drawableBId"));
    }

    @Test
    void exactActiveProviderVersionIsPublicAndInvalidatesOlderStateTokens() {
        for (String version : McpGlueDomain.SUPPORTED_VERSIONS) {
            final FakeFacade facade = new FakeFacade(List.of(new FakeGlue(
                "GlueA", "Glue", 0, 0.5F,
                "ArtA", "ArtB", List.of("ParamA")
            )), true, version);
            final McpToolCatalog tools = new McpGlueDomain(
                facade,
                new McpExecutionBridge(immediateScheduler())
            ).tools();

            final Map<String, Object> read = output(tools.call(
                McpGlueDomain.GLUES_READ,
                Map.of("operation", "get", "id", "GlueA")
            ));
            assertEquals(version, object(read.get("provider")).get("activeVersion"));
            assertEquals(version, object(read.get("stateToken")).get("providerVersion"));
        }

        final FakeGlue glue = new FakeGlue(
            "GlueA", "Glue", 0, 0.5F,
            "ArtA", "ArtB", List.of("ParamA")
        );
        final FakeFacade facade = new FakeFacade(List.of(glue), true, "5.3.02");
        final McpToolCatalog tools = new McpGlueDomain(
            facade,
            new McpExecutionBridge(immediateScheduler())
        ).tools();
        final Map<String, Object> oldState = state(tools, "GlueA");
        facade.providerVersion = "5.3.03";

        final Map<String, Object> stale = output(tools.call(
            McpGlueDomain.GLUES_WRITE,
            Map.of(
                "operation", "set_intensity",
                "id", "GlueA",
                "intensity", 0.75,
                "expectedState", oldState
            )
        ));
        assertFalse((Boolean) stale.get("ok"));
        assertEquals("REJECTED_STALE", stale.get("outcome"));
        assertEquals(0.5F, glue.intensity());
    }

    @Test
    void capabilityLedgerPublishesExactGlueProviderVersionsWithoutRangeInference() {
        final McpExecutionBridge execution = new McpExecutionBridge(immediateScheduler());
        final McpToolCatalog base = new McpGlueDomain(
            new FakeFacade(List.of(new FakeGlue(
                "GlueA", "Glue", 0, 0.5F,
                "ArtA", "ArtB", List.of("ParamA")
            ))),
            execution
        ).tools();
        final McpToolCatalog attached = McpTransactionDomain.attach(
            base,
            AuthoringTransactionService.unavailable(),
            execution,
            catalog -> catalog
        );

        final Map<String, Object> output = output(attached.call(
            McpCapabilitiesDomain.CAPABILITIES_READ,
            Map.of()
        ));
        final Map<String, Object> glueWrite = array(output.get("operations")).stream()
            .map(McpGlueDomainTest::object)
            .filter(operation -> McpGlueDomain.GLUES_WRITE.equals(operation.get("name")))
            .findFirst()
            .orElseThrow();

        assertEquals(McpGlueDomain.GLUE_CAPABILITY_ID, glueWrite.get("providerCapabilityId"));
        assertEquals(McpGlueDomain.SUPPORTED_VERSIONS, glueWrite.get("supportedVersions"));
        assertTrue(((List<?>) glueWrite.get("supportedVersions")).stream()
            .noneMatch(version -> ((String) version).contains("-") || ((String) version).contains("+")));
        final Map<String, Map<String, Object>> operationAvailability = array(
            glueWrite.get("operations")
        ).stream().map(McpGlueDomainTest::object).collect(
            java.util.stream.Collectors.toMap(
                operation -> (String) operation.get("operation"),
                operation -> operation
            )
        );
        assertEquals("AVAILABLE", operationAvailability.get("set_intensity").get("availability"));
        assertEquals(
            "RUNTIME_VERIFIED",
            operationAvailability.get("set_intensity").get("undoVerification")
        );
        assertEquals(true, operationAvailability.get("set_intensity").get("transactionEligible"));
        assertEquals(
            McpGlueDomain.SUPPORTED_VERSIONS,
            operationAvailability.get("set_intensity").get("supportedVersions")
        );
        assertEquals(
            "RUNTIME_UNAVAILABLE",
            operationAvailability.get("create").get("availability")
        );
        assertEquals(false, operationAvailability.get("create").get("transactionEligible"));
        assertTrue(((String) operationAvailability.get("create").get("reason"))
            .contains("verified Editor provider"));
    }

    @Test
    void transactionCanReferenceGlueReadAndWriteOutputsAndCommitsOneHistoryEntry() {
        final FakeGlue glue = new FakeGlue(
            "GlueA", "Glue", 0, 0.5F,
            "ArtA", "ArtB", List.of("ParamA")
        );
        final FakeFacade facade = new FakeFacade(List.of(glue));
        final McpExecutionBridge execution = new McpExecutionBridge(immediateScheduler());
        final McpToolCatalog tools = McpTransactionDomain.attach(
            new McpGlueDomain(facade, execution).tools(),
            new GroupingTransactionService(facade),
            execution,
            catalog -> catalog
        );

        final Map<String, Object> output = output(tools.call(
            McpTransactionDomain.TRANSACTION_EXECUTE,
            Map.of(
                "label", "Adjust GlueA",
                "steps", List.of(
                    Map.of(
                        "id", "read",
                        "tool", McpGlueDomain.GLUES_READ,
                        "arguments", Map.of("operation", "get", "id", "GlueA")
                    ),
                    Map.of(
                        "id", "rename",
                        "tool", McpGlueDomain.GLUES_WRITE,
                        "arguments", Map.of(
                            "operation", "set_name",
                            "id", ref("read", "/result/id"),
                            "name", "Adjusted glue"
                        )
                    ),
                    Map.of(
                        "id", "intensity",
                        "tool", McpGlueDomain.GLUES_WRITE,
                        "arguments", Map.of(
                            "operation", "set_intensity",
                            "id", ref("rename", "/result/readback/id"),
                            "intensity", 0.8
                        )
                    ),
                    Map.of(
                        "id", "drawableA",
                        "tool", McpGlueDomain.GLUES_WRITE,
                        "arguments", Map.of(
                            "operation", "set_drawable_a",
                            "id", ref("intensity", "/result/readback/id"),
                            "drawableId", "ArtB"
                        )
                    ),
                    Map.of(
                        "id", "drawableB",
                        "tool", McpGlueDomain.GLUES_WRITE,
                        "arguments", Map.of(
                            "operation", "set_drawable_b",
                            "id", ref("drawableA", "/result/readback/id"),
                            "drawableId", "ArtA"
                        )
                    ),
                    Map.of(
                        "id", "identifier",
                        "tool", McpGlueDomain.GLUES_WRITE,
                        "arguments", Map.of(
                            "operation", "set_id",
                            "id", ref("drawableB", "/result/readback/id"),
                            "newId", "GlueRenamed"
                        )
                    ),
                    Map.of(
                        "id", "verify",
                        "tool", McpGlueDomain.GLUES_READ,
                        "arguments", Map.of(
                            "operation", "get",
                            "id", ref("identifier", "/result/readback/id")
                        )
                    )
                )
            )
        ));

        assertTrue((Boolean) output.get("ok"));
        assertEquals("COMMITTED", output.get("outcome"));
        assertEquals(7, array(output.get("steps")).size());
        final Map<String, Object> receipt = object(output.get("receipt"));
        assertEquals("Adjust GlueA", receipt.get("label"));
        assertEquals(
            11L,
            ((Number) object(receipt.get("historyBefore")).get("revision")).longValue()
        );
        assertEquals(
            12L,
            ((Number) object(receipt.get("historyAfter")).get("revision")).longValue()
        );
        assertEquals(new GlueId("GlueRenamed"), glue.id());
        assertEquals("Adjusted glue", glue.name());
        assertEquals(0.8F, glue.intensity());
        assertEquals(new ArtMeshId("ArtB"), glue.drawableAId());
        assertEquals(new ArtMeshId("ArtA"), glue.drawableBId());
        assertEquals(1, facade.history.entries().size());
    }

    private static Map<String, Object> ref(final String step, final String pointer) {
        return Map.of("$ref", Map.of("step", step, "pointer", pointer));
    }

    @Test
    void readFailuresDistinguishAbsentGlueAndAbsentActiveModel() {
        final McpExecutionBridge execution = new McpExecutionBridge(immediateScheduler());
        final McpToolCatalog available = new McpGlueDomain(
            new FakeFacade(List.of(new FakeGlue(
                "GlueA", "Glue", 0, 0.5F,
                "ArtA", "ArtB", List.of("ParamA")
            ))),
            execution
        ).tools();

        final Map<String, Object> missing = output(available.call(
            McpGlueDomain.GLUES_READ,
            Map.of("operation", "get", "id", "GlueMissing")
        ));
        assertFalse((Boolean) missing.get("ok"));
        assertEquals("AVAILABLE", missing.get("availability"));
        assertEquals("GLUE_NOT_FOUND", object(missing.get("error")).get("code"));
        assertTrue(missing.get("stateToken") instanceof Map<?, ?>);
        assertEquals("5.3.02", object(missing.get("provider")).get("activeVersion"));

        final McpToolCatalog absent = new McpGlueDomain(
            new FakeFacade(List.of(), false),
            execution
        ).tools();
        final Map<String, Object> noModel = output(absent.call(
            McpGlueDomain.GLUES_READ,
            Map.of("operation", "list")
        ));
        assertFalse((Boolean) noModel.get("ok"));
        assertEquals("UNAVAILABLE", noModel.get("availability"));
        assertEquals("NO_ACTIVE_MODEL", object(noModel.get("error")).get("code"));
        assertEquals(null, noModel.get("stateToken"));
    }

    @Test
    void unsupportedGlueProviderVersionFailsClosedBeforeReadingOrWriting() {
        final FakeGlue glue = new FakeGlue(
            "GlueA", "Glue", 0, 0.5F,
            "ArtA", "ArtB", List.of("ParamA")
        );
        final McpToolCatalog tools = new McpGlueDomain(
            new FakeFacade(List.of(glue), true, "5.4.00"),
            new McpExecutionBridge(immediateScheduler())
        ).tools();

        final Map<String, Object> read = output(tools.call(
            McpGlueDomain.GLUES_READ,
            Map.of("operation", "list")
        ));
        assertFalse((Boolean) read.get("ok"));
        assertEquals("UNAVAILABLE", read.get("availability"));
        assertEquals("NO_VERIFIED_PROVIDER", object(read.get("error")).get("code"));

        final Map<String, Object> write = output(tools.call(
            McpGlueDomain.GLUES_WRITE,
            Map.of("operation", "set_intensity", "id", "GlueA", "intensity", 0.8)
        ));
        assertFalse((Boolean) write.get("ok"));
        assertEquals("UNAVAILABLE", write.get("outcome"));
        assertEquals("NO_VERIFIED_PROVIDER", object(write.get("error")).get("code"));
        assertEquals(0.5F, glue.intensity());
    }

    @Test
    void noOpStaleAndUnavailableOperationsDoNotChangeGlueOrHistory() {
        final FakeGlue glue = new FakeGlue(
            "GlueA", "Glue", 0, 0.5F,
            "ArtA", "ArtB", List.of("ParamA")
        );
        final FakeFacade facade = new FakeFacade(List.of(glue));
        final McpToolCatalog tools = new McpGlueDomain(
            facade,
            new McpExecutionBridge(immediateScheduler())
        ).tools();

        final Map<String, Object> current = state(tools, "GlueA");
        final Map<String, Object> noChange = output(tools.call(
            McpGlueDomain.GLUES_WRITE,
            Map.of(
                "operation", "set_intensity",
                "id", "GlueA",
                "intensity", 0.5,
                "expectedState", current
            )
        ));
        assertEquals("NO_CHANGE", noChange.get("outcome"));
        assertEquals(0, object(noChange.get("history")).get("entriesAdded"));
        assertEquals(11L, ((Number) object(noChange.get("history")).get(
            "revisionAfter"
        )).longValue());

        final Map<String, Object> staleState = new java.util.LinkedHashMap<>(current);
        staleState.put("historyRevision", 10L);
        final Map<String, Object> stale = output(tools.call(
            McpGlueDomain.GLUES_WRITE,
            Map.of(
                "operation", "set_intensity",
                "id", "GlueA",
                "intensity", 0.75,
                "expectedState", staleState
            )
        ));
        assertFalse((Boolean) stale.get("ok"));
        assertEquals("REJECTED_STALE", stale.get("outcome"));
        assertEquals(0.5F, glue.intensity());
        assertEquals(11L, facade.history.revision());

        for (String operation : List.of("create", "delete")) {
            final Map<String, Object> arguments = "create".equals(operation)
                ? Map.of("operation", operation)
                : Map.of("operation", operation, "id", "GlueA");
            final Map<String, Object> unavailable = output(tools.call(
                McpGlueDomain.GLUES_WRITE,
                arguments
            ));
            assertFalse((Boolean) unavailable.get("ok"));
            assertEquals("UNAVAILABLE", unavailable.get("outcome"));
            assertEquals(
                "NO_VERIFIED_PROVIDER",
                object(unavailable.get("error")).get("code")
            );
        }
        assertEquals(0.5F, glue.intensity());
        assertEquals(11L, facade.history.revision());
    }

    @Test
    void writeFailuresDistinguishPermissionRollbackAndUnknownOutcome() {
        final McpExecutionBridge execution = new McpExecutionBridge(immediateScheduler());

        final FakeGlue deniedGlue = new FakeGlue(
            "GlueDenied", "Denied", 0, 0.5F,
            "ArtA", "ArtB", List.of("ParamA")
        );
        deniedGlue.failIntensityBefore(new SecurityException("denied"));
        final Map<String, Object> denied = output(new McpGlueDomain(
            new FakeFacade(List.of(deniedGlue)),
            execution
        ).tools().call(McpGlueDomain.GLUES_WRITE, Map.of(
            "operation", "set_intensity",
            "id", "GlueDenied",
            "intensity", 0.8
        )));
        assertEquals("NOT_APPLIED", denied.get("outcome"));
        assertEquals("PERMISSION_DENIED", object(denied.get("error")).get("code"));
        assertEquals(0.5F, deniedGlue.intensity());

        final FakeGlue restoredGlue = new FakeGlue(
            "GlueRestored", "Restored", 0, 0.5F,
            "ArtA", "ArtB", List.of("ParamA")
        );
        restoredGlue.failIntensityAfter(
            new IllegalStateException("native write failed after compensation"),
            true
        );
        final Map<String, Object> restored = output(new McpGlueDomain(
            new FakeFacade(List.of(restoredGlue)),
            execution
        ).tools().call(McpGlueDomain.GLUES_WRITE, Map.of(
            "operation", "set_intensity",
            "id", "GlueRestored",
            "intensity", 0.8
        )));
        assertEquals("ROLLED_BACK", restored.get("outcome"));
        assertEquals("WRITE_ROLLED_BACK", object(restored.get("error")).get("code"));
        assertEquals(0.5F, restoredGlue.intensity());

        final FakeGlue unknownGlue = new FakeGlue(
            "GlueUnknown", "Unknown", 0, 0.5F,
            "ArtA", "ArtB", List.of("ParamA")
        );
        unknownGlue.failIntensityAfter(
            new IllegalStateException("native write failed without recovery"),
            false
        );
        final Map<String, Object> unknown = output(new McpGlueDomain(
            new FakeFacade(List.of(unknownGlue)),
            execution
        ).tools().call(McpGlueDomain.GLUES_WRITE, Map.of(
            "operation", "set_intensity",
            "id", "GlueUnknown",
            "intensity", 0.8
        )));
        assertEquals("OUTCOME_UNKNOWN", unknown.get("outcome"));
        assertEquals("WRITE_FAILED", object(unknown.get("error")).get("code"));
        assertEquals(0.8F, unknownGlue.intensity());
    }

    private static Map<String, Object> state(
        final McpToolCatalog tools,
        final String id
    ) {
        return object(output(tools.call(
            McpGlueDomain.GLUES_READ,
            Map.of("operation", "get", "id", id)
        )).get("stateToken"));
    }

    private static void assertApplied(final Map<String, Object> envelope) {
        assertEquals("APPLIED", output(envelope).get("outcome"));
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

    private static final class FakeFacade implements CubismFacade {
        private final FakeModel model;
        private final boolean active;
        private String providerVersion;
        private final ModelSnapshot modelSnapshot = new ModelSnapshot(
            "model-1", "Model", List.of(), List.of(), List.of(), List.of()
        );
        private final DocumentSnapshot document = new DocumentSnapshot(
            "document-1", "Model", "model.cmo3", Optional.empty(), Optional.of(modelSnapshot)
        );
        private HistorySnapshot history = new HistorySnapshot(
            HistorySnapshot.Availability.AVAILABLE,
            7,
            11,
            0,
            List.of(),
            false,
            false
        );

        private FakeFacade(final List<FakeGlue> glues) {
            this(glues, true, "5.3.02");
        }

        private FakeFacade(final List<FakeGlue> glues, final boolean active) {
            this(glues, active, "5.3.02");
        }

        private FakeFacade(
            final List<FakeGlue> glues,
            final boolean active,
            final String providerVersion
        ) {
            this.providerVersion = providerVersion;
            this.model = new FakeModel(glues, () -> this.providerVersion);
            this.active = active;
            glues.forEach(glue -> glue.onChanged = this::recordHistoryChange);
        }

        private boolean transactionActive;
        private int pendingHistoryChanges;

        private void recordHistoryChange() {
            if (transactionActive) {
                pendingHistoryChanges++;
            } else {
                appendHistoryEntry("Glue change");
            }
        }

        private void appendHistoryEntry(final String label) {
            final List<HistoryEntry> entries = new ArrayList<>(history.entries());
            entries.add(new HistoryEntry(entries.size(), label, true));
            history = new HistorySnapshot(
                HistorySnapshot.Availability.AVAILABLE,
                history.generation(),
                history.revision() + 1,
                entries.size(),
                entries,
                true,
                false
            );
        }

        @Override public CubismRuntimeSnapshot runtime() { return null; }
        @Override public Optional<ProjectSnapshot> activeProject() { return Optional.empty(); }
        @Override public Optional<DocumentSnapshot> activeDocument() {
            return active ? Optional.of(document) : Optional.empty();
        }
        @Override public Optional<ModelSnapshot> activeModel() {
            return active ? Optional.of(modelSnapshot) : Optional.empty();
        }
        @Override public boolean isHostPresent() { return active; }
        @Override public CubismModelAccess model() {
            return () -> {
                if (!active) throw new IllegalStateException("No active Cubism model");
                return model;
            };
        }
        @Override public CubismHistory history() {
            return new CubismHistory() {
                @Override public HistorySnapshot snapshot() { return history; }
                @Override public HistoryMoveResult moveTo(
                    final long expectedGeneration,
                    final long expectedRevision,
                    final int position
                ) {
                    throw new UnsupportedOperationException();
                }
            };
        }
        @Override public TransactionManager transactionManager() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class GroupingTransactionService
        implements AuthoringTransactionService {

        private final FakeFacade facade;
        private int sequence;

        private GroupingTransactionService(final FakeFacade facade) {
            this.facade = facade;
        }

        @Override
        public <T> AuthoringTransactionResult<T> execute(
            final AuthoringTransactionOptions options,
            final AuthoringTransactionWork<T> work
        ) {
            final HistorySnapshot before = facade.history;
            facade.transactionActive = true;
            facade.pendingHistoryChanges = 0;
            try {
                final T value = work.run();
                final boolean changed = facade.pendingHistoryChanges > 0;
                if (changed) facade.appendHistoryEntry(options.label());
                final AuthoringTransactionReceipt receipt = new AuthoringTransactionReceipt(
                    "transaction-" + ++sequence,
                    options.label(),
                    before,
                    facade.history,
                    changed ? Optional.of("history-entry-" + sequence) : Optional.empty()
                );
                return changed
                    ? AuthoringTransactionResult.committed(value, receipt)
                    : AuthoringTransactionResult.noChange(value, receipt);
            } catch (Exception failure) {
                final AuthoringTransactionReceipt receipt = new AuthoringTransactionReceipt(
                    "transaction-" + ++sequence,
                    options.label(),
                    before,
                    facade.history,
                    Optional.empty()
                );
                return AuthoringTransactionResult.rolledBack(
                    receipt,
                    "mcp.glue.transaction.failed"
                );
            } finally {
                facade.transactionActive = false;
                facade.pendingHistoryChanges = 0;
            }
        }
    }

    private static final class FakeModel implements CubismModel {
        private final List<FakeGlue> glues;
        private final java.util.function.Supplier<String> providerVersion;

        private FakeModel(
            final List<FakeGlue> glues,
            final java.util.function.Supplier<String> providerVersion
        ) {
            this.glues = new ArrayList<>(glues);
            this.providerVersion = providerVersion;
        }

        @Override public ModelId id() { return new ModelId("model-1"); }
        @Override public Parameters parameters() { throw new UnsupportedOperationException(); }
        @Override public Parts parts() { throw new UnsupportedOperationException(); }
        @Override public Drawables drawables() { throw new UnsupportedOperationException(); }
        @Override public Deformers deformers() { throw new UnsupportedOperationException(); }
        @Override public Glues glues() {
            return new Glues() {
                @Override public List<Glue> all() { return List.copyOf(glues); }
                @Override public Glue find(final GlueId id) {
                    return glues.stream().filter(glue -> glue.id().equals(id)).findFirst().orElseThrow();
                }
                @Override public Optional<String> providerVersion() {
                    return Optional.of(providerVersion.get());
                }
            };
        }
        @Override public void update() { }
    }

    private static final class FakeGlue implements Glue {
        private GlueId id;
        private String name;
        private final int index;
        private float intensity;
        private ArtMeshId drawableAId;
        private ArtMeshId drawableBId;
        private final List<ParameterId> parameterIds;
        private Runnable onChanged = () -> { };
        private RuntimeException intensityFailureBefore;
        private RuntimeException intensityFailureAfter;
        private boolean restoreIntensityAfterFailure;

        private FakeGlue(
            final String id,
            final String name,
            final int index,
            final float intensity,
            final String drawableAId,
            final String drawableBId,
            final List<String> parameterIds
        ) {
            this.id = new GlueId(id);
            this.name = name;
            this.index = index;
            this.intensity = intensity;
            this.drawableAId = new ArtMeshId(drawableAId);
            this.drawableBId = new ArtMeshId(drawableBId);
            this.parameterIds = parameterIds.stream().map(ParameterId::new).toList();
        }

        @Override public GlueId id() { return id; }
        @Override public String name() { return name; }
        @Override public void setName(final String value) {
            if (!name.equals(value)) {
                name = value;
                onChanged.run();
            }
        }
        @Override public void setId(final GlueId value) {
            if (!id.equals(value)) {
                id = value;
                onChanged.run();
            }
        }
        private void failIntensityBefore(final RuntimeException failure) {
            intensityFailureBefore = failure;
        }

        private void failIntensityAfter(
            final RuntimeException failure,
            final boolean restore
        ) {
            intensityFailureAfter = failure;
            restoreIntensityAfterFailure = restore;
        }

        @Override public float intensity() { return intensity; }
        @Override public void setIntensity(final float value) {
            if (intensityFailureBefore != null) throw intensityFailureBefore;
            if (Float.compare(intensity, value) != 0) {
                final float original = intensity;
                intensity = value;
                if (intensityFailureAfter != null) {
                    if (restoreIntensityAfterFailure) intensity = original;
                    throw intensityFailureAfter;
                }
                onChanged.run();
            }
        }
        @Override public void setDrawableA(final ArtMeshId value) {
            if (!drawableAId.equals(value)) {
                drawableAId = value;
                onChanged.run();
            }
        }
        @Override public void setDrawableB(final ArtMeshId value) {
            if (!drawableBId.equals(value)) {
                drawableBId = value;
                onChanged.run();
            }
        }
        @Override public int index() { return index; }
        @Override public int drawableA() { return 0; }
        @Override public int drawableB() { return 1; }
        @Override public IntSequence parameters() {
            return new IntSequence() {
                @Override public int size() { return parameterIds.size(); }
                @Override public int get(final int index) { return index; }
            };
        }
        @Override public ArtMeshId drawableAId() { return drawableAId; }
        @Override public ArtMeshId drawableBId() { return drawableBId; }
        @Override public List<ParameterId> parameterIds() { return parameterIds; }
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
