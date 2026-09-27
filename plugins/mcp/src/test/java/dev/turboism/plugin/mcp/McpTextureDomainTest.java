package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import dev.turboism.sdk.cubism.model.AtlasTexture;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.ModelImageEntry;
import dev.turboism.sdk.cubism.model.ModelImageGroup;
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.model.RawTexture;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpTextureDomainTest {

    // ---- catalog / schema --------------------------------------------------

    @Test
    void catalogsReadAndWriteToolsWithReadEffectAndUndoableWriteEffect() {
        final TextureHarness harness = TextureHarness.create();
        final McpToolCatalog tools = new McpTextureDomain(harness.facade()).tools(harness.execution());

        assertEquals(McpOperationEffect.READ, tools.registration(McpTextureDomain.TEXTURES_READ).effect());
        assertEquals(McpExecutionAffinity.UI_THREAD, tools.registration(McpTextureDomain.TEXTURES_READ).affinity());
        assertFalse(tools.registration(McpTextureDomain.TEXTURES_READ).transactionEligible());
        assertTrue(tools.registration(McpTextureDomain.TEXTURES_READ).exactOutputSchema());

        assertEquals(McpOperationEffect.UNDOABLE_WRITE, tools.registration(McpTextureDomain.TEXTURES_WRITE).effect());
        assertEquals(McpExecutionAffinity.UI_THREAD, tools.registration(McpTextureDomain.TEXTURES_WRITE).affinity());
        assertFalse(tools.registration(McpTextureDomain.TEXTURES_WRITE).transactionEligible(),
            "transaction grouping is not yet proven, so transactionEligible must remain false");
        assertTrue(tools.registration(McpTextureDomain.TEXTURES_WRITE).exactOutputSchema());

        final McpVersionSupport support = tools.registration(McpTextureDomain.TEXTURES_WRITE).versionSupport();
        assertTrue(support.scoped());
        assertEquals("cubism.editor-model.texture.write", support.providerCapabilityId());
        assertEquals(List.of("5.2.03", "5.3.02", "5.3.03"), support.supportedVersions());
        assertEquals(McpVersionSupport.UndoVerification.RUNTIME_VERIFIED,
            support.operations().get(0).undoVerification(),
            "exact-host verification is the parent's responsibility");
    }

    @Test
    void capabilityMetadataUsesNativeCapabilityIdsAndListsEachWriteOperation() {
        final TextureHarness harness = TextureHarness.create();
        final McpToolCatalog tools = new McpTextureDomain(harness.facade()).tools(harness.execution());
        assertEquals("cubism.editor-model.texture.read",
            tools.registration(McpTextureDomain.TEXTURES_READ).versionSupport().providerCapabilityId());
        final McpVersionSupport writes = tools.registration(McpTextureDomain.TEXTURES_WRITE).versionSupport();
        assertEquals("cubism.editor-model.texture.write", writes.providerCapabilityId());
        assertEquals(java.util.Set.of("add_model_image_group", "remove_model_image", "add_texture_atlas",
                "remove_texture_atlas", "remove_raw_image"),
            writes.operations().stream().map(McpVersionSupport.OperationSupport::operation)
                .collect(java.util.stream.Collectors.toSet()));
        for (McpVersionSupport.OperationSupport operation : writes.operations()) {
            assertFalse(operation.transactionEligible());
            assertEquals(List.of("5.2.03", "5.3.02", "5.3.03"), operation.supportedVersions());
        }
    }

    @Test
    void supplementaryUnicodeNameKeepsItsConfirmedWriteReceipt() {
        final TextureHarness harness = TextureHarness.create();
        final String name = "\uD83C\uDF19".repeat(256);
        final Map<String, Object> output = invokeWrite(harness, Map.of(
            "operation", "add_model_image_group", "expectedState", harness.state(), "name", name));
        assertEquals("APPLIED", output.get("outcome"));
        assertEquals(name, output.get("groupName"));
        assertEquals(1, harness.addModelImageGroupCalls());
        assertTrue(harness.groups.get().stream().anyMatch(group -> name.equals(group.groupName())));
    }

    @Test
    void writeInputSchemaIsClosedAndRejectsUnknownFields() {
        final TextureHarness harness = TextureHarness.create();
        final McpToolCatalog tools = new McpTextureDomain(harness.facade()).tools(harness.execution());
        assertThrows(
            IllegalArgumentException.class,
            () -> tools.call(
                McpTextureDomain.TEXTURES_WRITE,
                Map.of(
                    "operation", "add_model_image_group",
                    "expectedState", harness.state(),
                    "name", "GroupA",
                    "unexpected", "extra"
                )
            )
        );
    }

    @Test
    void writeInputSchemaRejectsUnknownOperationBeforeAnyDispatch() {
        final TextureHarness harness = TextureHarness.create();
        final McpToolCatalog tools = new McpTextureDomain(harness.facade()).tools(harness.execution());
        assertThrows(
            IllegalArgumentException.class,
            () -> tools.call(
                McpTextureDomain.TEXTURES_WRITE,
                Map.of(
                    "operation", "delete_everything",
                    "expectedState", harness.state(),
                    "name", "GroupA"
                )
            )
        );
    }

    @Test
    void readInputSchemaRejectsUnknownOperation() {
        final TextureHarness harness = TextureHarness.create();
        final McpToolCatalog tools = new McpTextureDomain(harness.facade()).tools(harness.execution());
        assertThrows(
            IllegalArgumentException.class,
            () -> tools.call(
                McpTextureDomain.TEXTURES_READ,
                Map.of("operation", "mutate")
            )
        );
    }

    // ---- read ---------------------------------------------------------------

    @Test
    void readReturnsFreshStateTokenAndPathFreeMetadata() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> envelope = new McpTextureDomain(harness.facade())
            .tools(harness.execution())
            .call(McpTextureDomain.TEXTURES_READ, Map.of("operation", "list"));
        assertFalse((Boolean) envelope.get("isError"));

        final Map<String, Object> output = structured(envelope);
        assertTrue((Boolean) output.get("ok"));
        assertEquals("list", output.get("operation"));
        final String token = (String) output.get("stateToken");
        assertNotNull(token);
        assertFalse(token.isBlank());

        final Map<String, Object> state = object(output.get("state"));
        assertEquals("doc-1", state.get("documentId"));
        assertEquals("model-1", state.get("modelId"));
        assertEquals(7L, ((Number) state.get("historyGeneration")).longValue());
        assertEquals(11L, ((Number) state.get("historyRevision")).longValue());

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> raws = (List<Map<String, Object>>) output.get("rawImages");
        assertEquals(1, raws.size());
        final Map<String, Object> raw = raws.get(0);
        assertEquals("raw-1", raw.get("id"));
        assertFalse(raw.containsKey("path"));
        assertFalse(raw.containsKey("filePath"));

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> groups = (List<Map<String, Object>>) output.get("modelImageGroups");
        assertEquals(1, groups.size());
        assertEquals("GroupA", groups.get(0).get("groupName"));
        assertFalse(groups.get(0).containsKey("id"), "model image groups expose no stable GUID");

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> atlases = (List<Map<String, Object>>) output.get("textureAtlases");
        assertEquals(1, atlases.size());
        assertEquals("atlas-1", atlases.get(0).get("id"));

        final Map<String, Object> counts = object(output.get("counts"));
        assertEquals(1, ((Number) counts.get("rawImages")).intValue());
        assertEquals(1, ((Number) counts.get("modelImageGroups")).intValue());
        assertEquals(1, ((Number) counts.get("textureAtlases")).intValue());
    }

    @Test
    void readFailsExplicitlyWhenItemCountExceedsTheBoundedLimit() {
        final TextureHarness harness = TextureHarness.create();
        harness.bypassRawImageOverflow();
        final Map<String, Object> output = structured(
            new McpTextureDomain(harness.facade())
                .tools(harness.execution())
                .call(McpTextureDomain.TEXTURES_READ, Map.of("operation", "list"))
        );
        assertFalse((Boolean) output.get("ok"));
        assertEquals("TEXTURE_LIST_OVERFLOW", output.get("code"));
    }

    @Test
    void readFailsWhenNoActiveModelDocumentIsAvailable() {
        final TextureHarness harness = TextureHarness.create();
        harness.disableModelDocument();
        final Map<String, Object> output = structured(
            new McpTextureDomain(harness.facade())
                .tools(harness.execution())
                .call(McpTextureDomain.TEXTURES_READ, Map.of("operation", "list"))
        );
        assertFalse((Boolean) output.get("ok"));
        assertEquals("NO_ACTIVE_MODEL", output.get("code"));
    }

    // ---- add_model_image_group ---------------------------------------------

    @Test
    void addModelImageGroupSucceedsAndReturnsGroupNameWithoutInjectedId() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> output = invokeWrite(harness, Map.of(
            "operation", "add_model_image_group",
            "expectedState", harness.state(),
            "name", "GroupB"
        ));
        assertTrue((Boolean) output.get("ok"));
        assertEquals("APPLIED", output.get("outcome"));
        assertEquals(false, output.get("retryable"));
        assertNotNull(output.get("receipt"));
        assertEquals("add_model_image_group", output.get("operation"));
        assertEquals("GroupB", output.get("groupName"));
        assertFalse(output.containsKey("createdGroupId"),
            "model image groups have no stable GUID; the domain must not invent one");
        assertEquals(1, harness.addModelImageGroupCalls());
    }

    @Test
    void addModelImageGroupRejectsDuplicateNameBeforeAnyDispatch() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> output = invokeWriteExpectingFailure(harness, Map.of(
            "operation", "add_model_image_group",
            "expectedState", harness.state(),
            "name", "GroupA"
        ));
        assertFalse((Boolean) output.get("ok"));
        assertEquals("NOT_APPLIED", output.get("outcome"));
        assertEquals(0, harness.addModelImageGroupCalls(),
            "duplicate names must be rejected before the native call");
        final Map<String, Object> error = object(object(output.get("error")));
        assertEquals("DUPLICATE_GROUP", error.get("code"));
    }

    @Test
    void addModelImageGroupRejectsBlankName() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> output = invokeWriteExpectingFailure(harness, Map.of(
            "operation", "add_model_image_group",
            "expectedState", harness.state(),
            "name", "   "
        ));
        assertFalse((Boolean) output.get("ok"));
        assertEquals(0, harness.addModelImageGroupCalls());
    }

    @Test
    void addModelImageGroupRejectsOversizedName() {
        final TextureHarness harness = TextureHarness.create();
        final String huge = "x".repeat(257);
        final Map<String, Object> output = invokeWriteExpectingFailure(harness, Map.of(
            "operation", "add_model_image_group",
            "expectedState", harness.state(),
            "name", huge
        ));
        assertFalse((Boolean) output.get("ok"));
        assertEquals(0, harness.addModelImageGroupCalls());
    }

    // ---- remove_model_image -------------------------------------------------

    @Test
    void removeModelImageSucceedsWhenTargetExistsAndConfirmIsSet() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> output = invokeWrite(harness, Map.of(
            "operation", "remove_model_image",
            "expectedState", harness.state(),
            "id", "model-image-1",
            "confirmDelete", true
        ));
        assertTrue((Boolean) output.get("ok"));
        assertEquals("APPLIED", output.get("outcome"));
        assertEquals("model-image-1", output.get("id"));
        assertEquals("GroupA", output.get("groupName"));
        assertEquals(1, harness.removeModelImageCalls());
    }

    @Test
    void removeModelImageRejectsDeletionWhenConfirmIsMissing() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> output = invokeWriteExpectingFailure(harness, Map.of(
            "operation", "remove_model_image",
            "expectedState", harness.state(),
            "id", "model-image-1"
        ));
        assertFalse((Boolean) output.get("ok"));
        assertEquals("CONFIRM_DELETE_REQUIRED", codeOf(output));
        assertEquals(0, harness.removeModelImageCalls());
    }

    @Test
    void removeModelImageRejectsDeletionWhenConfirmIsFalse() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> output = invokeWriteExpectingFailure(harness, Map.of(
            "operation", "remove_model_image",
            "expectedState", harness.state(),
            "id", "model-image-1",
            "confirmDelete", false
        ));
        assertFalse((Boolean) output.get("ok"));
        assertEquals("CONFIRM_DELETE_REQUIRED", codeOf(output));
        assertEquals(0, harness.removeModelImageCalls());
    }

    @Test
    void removeModelImageRejectsUnknownTarget() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> output = invokeWriteExpectingFailure(harness, Map.of(
            "operation", "remove_model_image",
            "expectedState", harness.state(),
            "id", "missing-model-image",
            "confirmDelete", true
        ));
        assertFalse((Boolean) output.get("ok"));
        assertEquals("UNKNOWN_TARGET", codeOf(output));
        assertEquals(0, harness.removeModelImageCalls());
    }

    // ---- add_texture_atlas --------------------------------------------------

    @Test
    void addTextureAtlasSucceedsAndReturnsGeneratedAtlasId() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> output = invokeWrite(harness, Map.of(
            "operation", "add_texture_atlas",
            "expectedState", harness.state(),
            "name", "AtlasA",
            "widthPixels", 512,
            "heightPixels", 1024
        ));
        assertTrue((Boolean) output.get("ok"));
        assertEquals("APPLIED", output.get("outcome"));
        assertEquals("generated-atlas-id", output.get("id"));
        assertEquals(512, output.get("widthPixels"));
        assertEquals(1024, output.get("heightPixels"));
        assertEquals(1, harness.addTextureAtlasCalls());
    }

    @Test
    void addTextureAtlasGeneratedIdSurvivesReadbackFailure() {
        final TextureHarness harness = TextureHarness.create();
        harness.failReadbackAfterNextWrite();
        final Map<String, Object> output = invokeWrite(harness, Map.of(
            "operation", "add_texture_atlas",
            "expectedState", harness.state(),
            "name", "AtlasSurvives",
            "widthPixels", 256,
            "heightPixels", 256
        ));
        assertTrue((Boolean) output.get("ok"));
        assertEquals("APPLIED_WITH_READBACK_WARNING", output.get("outcome"));
        assertEquals("generated-atlas-id", output.get("id"),
            "the generated atlas id must survive a readback failure");
        assertEquals(false, output.get("retryable"));
        assertNotNull(output.get("receipt"));
        assertNotNull(output.get("readbackWarning"));
    }

    @Test
    void addTextureAtlasRejectsZeroWidth() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> output = invokeWriteExpectingFailure(harness, Map.of(
            "operation", "add_texture_atlas",
            "expectedState", harness.state(),
            "name", "AtlasA",
            "widthPixels", 0,
            "heightPixels", 1024
        ));
        assertFalse((Boolean) output.get("ok"));
        assertEquals("INVALID_ARGUMENT", codeOf(output));
        assertEquals(0, harness.addTextureAtlasCalls());
    }

    @Test
    void addTextureAtlasRejectsOversizedDimension() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> output = invokeWriteExpectingFailure(harness, Map.of(
            "operation", "add_texture_atlas",
            "expectedState", harness.state(),
            "name", "AtlasA",
            "widthPixels", 16385,
            "heightPixels", 1024
        ));
        assertFalse((Boolean) output.get("ok"));
        assertEquals("INVALID_ARGUMENT", codeOf(output));
        assertEquals(0, harness.addTextureAtlasCalls());
    }

    // ---- remove_texture_atlas -----------------------------------------------

    @Test
    void removeTextureAtlasSucceedsWhenConfirmIsSet() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> output = invokeWrite(harness, Map.of(
            "operation", "remove_texture_atlas",
            "expectedState", harness.state(),
            "id", "atlas-1",
            "confirmDelete", true
        ));
        assertTrue((Boolean) output.get("ok"));
        assertEquals("APPLIED", output.get("outcome"));
        assertEquals("atlas-1", output.get("id"));
        assertEquals(1, harness.removeTextureAtlasCalls());
    }

    @Test
    void removeTextureAtlasRejectsUnknownTarget() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> output = invokeWriteExpectingFailure(harness, Map.of(
            "operation", "remove_texture_atlas",
            "expectedState", harness.state(),
            "id", "missing-atlas",
            "confirmDelete", true
        ));
        assertFalse((Boolean) output.get("ok"));
        assertEquals("UNKNOWN_TARGET", codeOf(output));
        assertEquals(0, harness.removeTextureAtlasCalls());
    }

    @Test
    void removeTextureAtlasRejectsDeletionWhenConfirmIsMissing() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> output = invokeWriteExpectingFailure(harness, Map.of(
            "operation", "remove_texture_atlas",
            "expectedState", harness.state(),
            "id", "atlas-1"
        ));
        assertFalse((Boolean) output.get("ok"));
        assertEquals("CONFIRM_DELETE_REQUIRED", codeOf(output));
        assertEquals(0, harness.removeTextureAtlasCalls());
    }

    // ---- remove_raw_image ---------------------------------------------------

    @Test
    void removeRawImageSucceedsWhenConfirmIsSet() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> output = invokeWrite(harness, Map.of(
            "operation", "remove_raw_image",
            "expectedState", harness.state(),
            "id", "raw-1",
            "confirmDelete", true
        ));
        assertTrue((Boolean) output.get("ok"));
        assertEquals("APPLIED", output.get("outcome"));
        assertEquals(1, harness.removeRawImageCalls());
    }

    @Test
    void removeRawImageRejectsUnknownTarget() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> output = invokeWriteExpectingFailure(harness, Map.of(
            "operation", "remove_raw_image",
            "expectedState", harness.state(),
            "id", "missing-raw",
            "confirmDelete", true
        ));
        assertFalse((Boolean) output.get("ok"));
        assertEquals("UNKNOWN_TARGET", codeOf(output));
        assertEquals(0, harness.removeRawImageCalls());
    }

    @Test
    void removeRawImageRejectsDeletionWhenConfirmIsMissing() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> output = invokeWriteExpectingFailure(harness, Map.of(
            "operation", "remove_raw_image",
            "expectedState", harness.state(),
            "id", "raw-1"
        ));
        assertFalse((Boolean) output.get("ok"));
        assertEquals("CONFIRM_DELETE_REQUIRED", codeOf(output));
        assertEquals(0, harness.removeRawImageCalls());
    }

    // ---- stale state / permissions ------------------------------------------

    @Test
    void writeRejectsStaleHistoryGenerationBeforeAnyDispatch() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> stale = new LinkedHashMap<>(harness.state());
        stale.put("historyGeneration", 6);
        final Map<String, Object> output = invokeWriteExpectingFailure(harness, Map.of(
            "operation", "add_model_image_group",
            "expectedState", stale,
            "name", "GroupB"
        ));
        assertFalse((Boolean) output.get("ok"));
        assertEquals("STALE_STATE", codeOf(output));
        assertEquals(0, harness.addModelImageGroupCalls());
    }

    @Test
    void writeRejectsStaleHistoryRevisionBeforeAnyDispatch() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> stale = new LinkedHashMap<>(harness.state());
        stale.put("historyRevision", 10);
        final Map<String, Object> output = invokeWriteExpectingFailure(harness, Map.of(
            "operation", "add_model_image_group",
            "expectedState", stale,
            "name", "GroupB"
        ));
        assertFalse((Boolean) output.get("ok"));
        assertEquals("STALE_STATE", codeOf(output));
        assertEquals(0, harness.addModelImageGroupCalls());
    }

    @Test
    void writeRejectsStaleDocumentIdentityBeforeAnyDispatch() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> stale = new LinkedHashMap<>(harness.state());
        stale.put("documentId", "different-doc");
        final Map<String, Object> output = invokeWriteExpectingFailure(harness, Map.of(
            "operation", "remove_raw_image",
            "expectedState", stale,
            "id", "raw-1",
            "confirmDelete", true
        ));
        assertFalse((Boolean) output.get("ok"));
        assertEquals("STALE_STATE", codeOf(output));
        assertEquals(0, harness.removeRawImageCalls());
    }

    @Test
    void writeRejectsWhenNoActiveModelDocumentIsAvailable() {
        final TextureHarness harness = TextureHarness.create();
        harness.disableModelDocument();
        final Map<String, Object> output = invokeWriteExpectingFailure(harness, Map.of(
            "operation", "add_model_image_group",
            "expectedState", harness.state(),
            "name", "GroupB"
        ));
        assertFalse((Boolean) output.get("ok"));
        assertEquals("NO_ACTIVE_MODEL", codeOf(output));
        assertEquals(0, harness.addModelImageGroupCalls());
    }

    @Test
    void nativeFailureAfterMutationIsNeverReportedAsNotApplied() {
        final TextureHarness harness = TextureHarness.create();
        ((ModelStub) harness.activeModel.get()).failureAfterWrite =
            new IllegalStateException("sensitive /private/model.cmo3");
        final Map<String, Object> output = invokeWriteExpectingFailure(harness, Map.of(
            "operation", "add_model_image_group", "expectedState", harness.state(), "name", "Committed"));
        assertTrue(harness.groups.get().stream().anyMatch(group -> group.groupName().equals("Committed")));
        assertEquals("OUTCOME_UNKNOWN", output.get("outcome"));
        assertEquals("OUTCOME_UNKNOWN", object(output.get("error")).get("outcome"));
        assertEquals(false, output.get("retryable"));
        assertFalse(Json.stringify(output).contains("/private/"));
    }

    @Test
    void failedPostStateReadDoesNotFabricateThePreWriteState() {
        final TextureHarness harness = TextureHarness.create();
        harness.failReadbackAfterNextWrite();
        final Map<String, Object> output = invokeWrite(harness, Map.of(
            "operation", "add_texture_atlas", "expectedState", harness.state(),
            "name", "Committed", "widthPixels", 64, "heightPixels", 64));
        assertEquals("APPLIED_WITH_READBACK_WARNING", output.get("outcome"));
        assertEquals(null, output.get("postState"), "unobserved post-state must be null, not the expected old state");
        assertEquals("generated-atlas-id", output.get("id"));
    }

    @Test
    void dimensionsAcceptIntegralJsonNumbersAfterWireRoundTrip() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> arguments = object(dev.turboism.protocol.json.StrictJson.parse(
            dev.turboism.protocol.json.StrictJson.bytes(Map.of(
                "operation", "add_texture_atlas", "expectedState", harness.state(),
                "name", "Wire", "widthPixels", 64L, "heightPixels", 128L))));
        final Map<String, Object> output = invokeWrite(harness, arguments);
        assertEquals("APPLIED", output.get("outcome"));
        assertEquals(1, harness.addTextureAtlasCalls());
    }

    @Test
    void overflowingExpectedRevisionCannotWrapAroundToTheCurrentRevision() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> state = new LinkedHashMap<>(harness.state());
        state.put("historyRevision", new java.math.BigInteger("18446744073709551627"));
        final Map<String, Object> output = structured(new McpTextureDomain(harness.facade()).call(
            McpTextureDomain.TEXTURES_WRITE, Map.of("operation", "add_model_image_group",
                "expectedState", state, "name", "Forbidden")));
        assertFalse((Boolean) output.get("ok"));
        assertEquals(0, harness.addModelImageGroupCalls());
    }

    @Test
    void unavailableNativeHistoryRejectsWritesBeforeDispatch() {
        final TextureHarness harness = TextureHarness.create();
        harness.history.set(new CubismHistoryStub(new HistorySnapshot(
            HistorySnapshot.Availability.UNAVAILABLE, 7L, 11L, 0, List.of(), false, false)));
        final Map<String, Object> output = invokeWriteExpectingFailure(harness, Map.of(
            "operation", "add_model_image_group", "expectedState", harness.state(), "name", "Forbidden"));
        assertEquals("HISTORY_UNAVAILABLE", codeOf(output));
        assertEquals(0, harness.addModelImageGroupCalls());
    }

    @Test
    void permissionsFailClosedWithSchemaValidErrorAndNoSensitivePath() {
        final TextureHarness harness = TextureHarness.create();
        harness.activeModel.set((CubismModel) Proxy.newProxyInstance(
            CubismModel.class.getClassLoader(), new Class<?>[] { CubismModel.class },
            (proxy, method, arguments) -> { throw new SecurityException("/private/path.cmo3"); }));
        final Map<String, Object> output = invokeWriteExpectingFailure(harness, Map.of(
            "operation", "add_model_image_group", "expectedState", harness.state(), "name", "Forbidden"));
        assertEquals("PERMISSION_DENIED", codeOf(output));
        assertEquals("NOT_APPLIED", output.get("outcome"));
        assertFalse(Json.stringify(output).contains("/private/"));
    }

    @Test
    void nestedImageOverflowFailsBeforeSerializingAnUnboundedGroup() {
        final TextureHarness harness = TextureHarness.create();
        final List<ModelImageEntry> images = new ArrayList<>();
        for (int index = 0; index < 1025; index++) {
            images.add(new ModelImageEntryStub(new ModelImageId("image-" + index), "Image", 16, 16));
        }
        harness.groups.set(List.of(new ModelImageGroupStub("Huge", "", List.copyOf(images))));
        final Map<String, Object> output = structured(new McpTextureDomain(harness.facade())
            .tools(harness.execution()).call(McpTextureDomain.TEXTURES_READ, Map.of("operation", "list")));
        assertEquals(false, output.get("ok"));
        assertEquals("TEXTURE_LIST_OVERFLOW", output.get("code"));
    }

    @Test
    void unknownDirectOperationCannotAcquireAnAppliedOutcome() {
        final TextureHarness harness = TextureHarness.create();
        final Map<String, Object> output = structured(new McpTextureDomain(harness.facade()).call(
            McpTextureDomain.TEXTURES_WRITE,
            Map.of("operation", "unknown", "expectedState", harness.state())));
        assertEquals(false, output.get("ok"));
        assertEquals("NOT_APPLIED", output.get("outcome"));
    }

    // ---- helpers ------------------------------------------------------------

    private static Map<String, Object> invokeWrite(
        final TextureHarness harness,
        final Map<String, Object> arguments
    ) {
        final Map<String, Object> envelope = new McpTextureDomain(harness.facade())
            .tools(harness.execution())
            .call(McpTextureDomain.TEXTURES_WRITE, arguments);
        assertFalse((Boolean) envelope.get("isError"),
            "Unexpected isError: " + envelope);
        return structured(envelope);
    }

    private static Map<String, Object> invokeWriteExpectingFailure(
        final TextureHarness harness,
        final Map<String, Object> arguments
    ) {
        final Map<String, Object> envelope = new McpTextureDomain(harness.facade())
            .tools(harness.execution())
            .call(McpTextureDomain.TEXTURES_WRITE, arguments);
        assertTrue((Boolean) envelope.get("isError"),
            "Expected isError: " + envelope);
        return structured(envelope);
    }

    private static Map<String, Object> structured(final Map<String, Object> envelope) {
        final Object structured = envelope.get("structuredContent");
        assertTrue(structured instanceof Map, "structuredContent missing: " + envelope);
        final Map<?, ?> raw = (Map<?, ?>) structured;
        final Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            assertTrue(entry.getKey() instanceof String);
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object value) {
        return (Map<String, Object>) value;
    }

    private static String codeOf(final Map<String, Object> output) {
        final Object error = output.get("error");
        if (error instanceof Map<?, ?>) {
            return (String) ((Map<?, ?>) error).get("code");
        }
        return (String) output.get("code");
    }

    // ---- harness ------------------------------------------------------------

    /**
     * Tiny fakes for {@link CubismFacade}, {@link CubismHistory}, and
     * {@link ModelTextures} that record invocation counts so tests can assert
     * the dispatch order. Uses {@link ModelSnapshot} and {@link DocumentSnapshot}
     * records and a {@link CubismModelAccess} lambda that returns the test stub
     * directly. No reflection on production code, no arbitrary paths.
     */
    private static final class TextureHarness {

        private final AtomicInteger documentCalls = new AtomicInteger();
        private final AtomicInteger addModelImageGroupCalls = new AtomicInteger();
        private final AtomicInteger removeModelImageCalls = new AtomicInteger();
        private final AtomicInteger addTextureAtlasCalls = new AtomicInteger();
        private final AtomicInteger removeTextureAtlasCalls = new AtomicInteger();
        private final AtomicInteger removeRawImageCalls = new AtomicInteger();
        private final AtomicReference<Throwable> readbackFailure = new AtomicReference<>();
        private final AtomicReference<DocumentSnapshot> documentSnapshot = new AtomicReference<>();
        private final AtomicReference<CubismModel> activeModel = new AtomicReference<>();
        private final AtomicReference<CubismHistory> history = new AtomicReference<>();
        private final AtomicReference<List<RawTexture>> rawImages = new AtomicReference<>();
        private final AtomicReference<List<ModelImageGroup>> groups = new AtomicReference<>();
        private final AtomicReference<List<AtlasTexture>> atlases = new AtomicReference<>();
        private final AtomicReference<Boolean> modelDocumentEnabled = new AtomicReference<>(true);

        static TextureHarness create() {
            final TextureHarness harness = new TextureHarness();
            harness.installDefaults();
            return harness;
        }

        void installDefaults() {
            rawImages.set(List.of(new RawTextureStub(new RawImageId("raw-1"),
                "Raw One", 64, 64)));
            final ModelImageEntryStub entry = new ModelImageEntryStub(new ModelImageId("model-image-1"),
                "ModelImage One", 32, 32);
            final ModelImageGroupStub group = new ModelImageGroupStub("GroupA", "", List.of(entry));
            groups.set(List.of(group));
            atlases.set(List.of(new AtlasTextureStub(new TextureAtlasId("atlas-1"),
                "Atlas One", 1024, 1024, 3, 0)));
            history.set(new CubismHistoryStub(new HistorySnapshot(
                HistorySnapshot.Availability.AVAILABLE,
                7L, 11L, 0, List.of(), false, false
            )));
            final ModelSnapshot modelSnapshot = new ModelSnapshot(
                "model-1", "Model One",
                List.of(), List.of(), List.of(), List.of()
            );
            activeModel.set(new ModelStub(
                "model-1", "Model One",
                rawImages, groups, atlases,
                addModelImageGroupCalls, removeModelImageCalls,
                addTextureAtlasCalls, removeTextureAtlasCalls, removeRawImageCalls
            ));
            documentSnapshot.set(new DocumentSnapshot(
                "doc-1", "Doc One", "model.cmo3",
                Optional.empty(),
                Optional.of(modelSnapshot),
                DocumentKind.MODEL,
                Optional.empty(),
                Optional.empty()
            ));
        }

        CubismFacade facade() {
            final TextureHarness self = this;
            return (CubismFacade) Proxy.newProxyInstance(
                CubismFacade.class.getClassLoader(),
                new Class<?>[] { CubismFacade.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "activeDocument":
                            documentCalls.incrementAndGet();
                            if (!self.modelDocumentEnabled.get()) {
                                return Optional.of(new DocumentSnapshot(
                                    "doc-2", "Doc Two", "other.cmo3",
                                    Optional.empty(),
                                    Optional.empty(),
                                    DocumentKind.OTHER,
                                    Optional.empty(),
                                    Optional.empty()
                                ));
                            }
                            // Simulate readback failure on the post-write call (the
                            // second invocation per write request).
                            final int count = documentCalls.get();
                            if (count > 1 && self.readbackFailure.get() != null) {
                                throw self.readbackFailure.get();
                            }
                            return Optional.of(self.documentSnapshot.get());
                        case "model":
                            return (CubismModelAccess) () -> self.activeModel.get();
                        case "history":
                            return self.history.get();
                        case "activeModel":
                            return Optional.ofNullable(self.activeModel.get());
                        case "hasActiveDocument":
                            return self.documentSnapshot.get() != null;
                        case "hasActiveModel":
                            return self.activeModel.get() != null;
                        case "toString":
                            return "FakeCubismFacade";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                }
            );
        }

        McpExecutionBridge execution() {
            return new McpExecutionBridge(new UiScheduler() {
                @Override
                public Registration runOnUiThread(final Runnable work) {
                    work.run();
                    return () -> { };
                }

                @Override
                public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
                    throw new UnsupportedOperationException();
                }
            });
        }

        Map<String, Object> state() {
            final Map<String, Object> state = new LinkedHashMap<>();
            state.put("documentId", "doc-1");
            state.put("modelId", "model-1");
            state.put("historyGeneration", 7);
            state.put("historyRevision", 11);
            return state;
        }

        int addModelImageGroupCalls() {
            return addModelImageGroupCalls.get();
        }

        int removeModelImageCalls() {
            return removeModelImageCalls.get();
        }

        int addTextureAtlasCalls() {
            return addTextureAtlasCalls.get();
        }

        int removeTextureAtlasCalls() {
            return removeTextureAtlasCalls.get();
        }

        int removeRawImageCalls() {
            return removeRawImageCalls.get();
        }

        void bypassRawImageOverflow() {
            final List<RawTexture> huge = new ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                huge.add(new RawTextureStub(new RawImageId("raw-" + i),
                    "Raw " + i, 16, 16));
            }
            rawImages.set(huge);
        }

        void disableModelDocument() {
            modelDocumentEnabled.set(false);
        }

        void failReadbackAfterNextWrite() {
            readbackFailure.set(new RuntimeException("synthetic readback failure"));
        }
    }

    // ---- fake stubs ---------------------------------------------------------

    private static final class RawTextureStub implements RawTexture {
        private final RawImageId id;
        private final String name;
        private final int width;
        private final int height;

        RawTextureStub(final RawImageId id, final String name,
                       final int width, final int height) {
            this.id = id;
            this.name = name;
            this.width = width;
            this.height = height;
        }

        @Override public RawImageId id() { return id; }
        @Override public String name() { return name; }
        @Override public int width() { return width; }
        @Override public int height() { return height; }
    }

    private static final class ModelImageEntryStub implements ModelImageEntry {
        private final ModelImageId id;
        private final String name;
        private final int width;
        private final int height;

        ModelImageEntryStub(final ModelImageId id, final String name,
                            final int width, final int height) {
            this.id = id;
            this.name = name;
            this.width = width;
            this.height = height;
        }

        @Override public ModelImageId id() { return id; }
        @Override public String name() { return name; }
        @Override public int width() { return width; }
        @Override public int height() { return height; }
    }

    private static final class ModelImageGroupStub implements ModelImageGroup {
        private final String groupName;
        private final String memo;
        private final List<ModelImageEntry> modelImages;

        ModelImageGroupStub(final String groupName, final String memo,
                            final List<ModelImageEntry> modelImages) {
            this.groupName = groupName;
            this.memo = memo;
            this.modelImages = modelImages;
        }

        @Override public String groupName() { return groupName; }
        @Override public String memo() { return memo; }
        @Override public List<ModelImageEntry> modelImages() { return modelImages; }
    }

    private static final class AtlasTextureStub implements AtlasTexture {
        private final TextureAtlasId id;
        private final String name;
        private final int width;
        private final int height;
        private final int atlasVersion;
        private final int modelImageCount;

        AtlasTextureStub(final TextureAtlasId id, final String name,
                         final int width, final int height,
                         final int atlasVersion, final int modelImageCount) {
            this.id = id;
            this.name = name;
            this.width = width;
            this.height = height;
            this.atlasVersion = atlasVersion;
            this.modelImageCount = modelImageCount;
        }

        @Override public TextureAtlasId id() { return id; }
        @Override public String name() { return name; }
        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public int atlasVersion() { return atlasVersion; }
        @Override public int modelImageCount() { return modelImageCount; }
    }

    private static final class ModelStub implements CubismModel, ModelTextures {
        private RuntimeException failureAfterWrite;
        private final String modelId;
        private final String name;
        private final AtomicReference<List<RawTexture>> rawImages;
        private final AtomicReference<List<ModelImageGroup>> groups;
        private final AtomicReference<List<AtlasTexture>> atlases;
        private final AtomicInteger addModelImageGroupCalls;
        private final AtomicInteger removeModelImageCalls;
        private final AtomicInteger addTextureAtlasCalls;
        private final AtomicInteger removeTextureAtlasCalls;
        private final AtomicInteger removeRawImageCalls;

        ModelStub(
            final String modelId,
            final String name,
            final AtomicReference<List<RawTexture>> rawImages,
            final AtomicReference<List<ModelImageGroup>> groups,
            final AtomicReference<List<AtlasTexture>> atlases,
            final AtomicInteger addModelImageGroupCalls,
            final AtomicInteger removeModelImageCalls,
            final AtomicInteger addTextureAtlasCalls,
            final AtomicInteger removeTextureAtlasCalls,
            final AtomicInteger removeRawImageCalls
        ) {
            this.modelId = modelId;
            this.name = name;
            this.rawImages = rawImages;
            this.groups = groups;
            this.atlases = atlases;
            this.addModelImageGroupCalls = addModelImageGroupCalls;
            this.removeModelImageCalls = removeModelImageCalls;
            this.addTextureAtlasCalls = addTextureAtlasCalls;
            this.removeTextureAtlasCalls = removeTextureAtlasCalls;
            this.removeRawImageCalls = removeRawImageCalls;
        }

        // CubismModel — only textures() is reachable from the domain.
        @Override public ModelId id() { return new ModelId(modelId); }
        @Override public String name() { return name; }
        @Override public ModelTextures textures() { return this; }
        @Override public dev.turboism.sdk.cubism.model.Parameters parameters() {
            throw new UnsupportedOperationException();
        }
        @Override public dev.turboism.sdk.cubism.model.Parts parts() {
            throw new UnsupportedOperationException();
        }
        @Override public dev.turboism.sdk.cubism.model.Drawables drawables() {
            throw new UnsupportedOperationException();
        }
        @Override public dev.turboism.sdk.cubism.model.Deformers deformers() {
            throw new UnsupportedOperationException();
        }
        @Override public void update() { /* no-op for tests */ }
        @Override public dev.turboism.sdk.cubism.model.Glues glues() {
            throw new UnsupportedOperationException();
        }

        // ModelTextures
        @Override public List<RawTexture> rawImages() { return rawImages.get(); }
        @Override public List<ModelImageGroup> modelImageGroups() { return groups.get(); }
        @Override public List<AtlasTexture> textureAtlases() { return atlases.get(); }

        @Override public void addModelImageGroup(final String name) {
            addModelImageGroupCalls.incrementAndGet();
            final List<ModelImageGroup> current = groups.get();
            final List<ModelImageGroup> updated = new ArrayList<>(current);
            updated.add(new ModelImageGroupStub(name, "", List.of()));
            groups.set(List.copyOf(updated));
            if (failureAfterWrite != null) throw failureAfterWrite;
        }

        @Override public void removeModelImage(final ModelImageId id) {
            removeModelImageCalls.incrementAndGet();
        }

        @Override public TextureAtlasId addTextureAtlas(
            final String name, final int widthPixels, final int heightPixels
        ) {
            addTextureAtlasCalls.incrementAndGet();
            return new TextureAtlasId("generated-atlas-id");
        }

        @Override public void removeTextureAtlas(final TextureAtlasId id) {
            removeTextureAtlasCalls.incrementAndGet();
        }

        @Override public void removeRawImage(final RawImageId id) {
            removeRawImageCalls.incrementAndGet();
        }
    }

    private static final class CubismHistoryStub implements CubismHistory {
        private final AtomicReference<HistorySnapshot> snapshot;

        CubismHistoryStub(final HistorySnapshot initial) {
            this.snapshot = new AtomicReference<>(initial);
        }

        @Override public HistorySnapshot snapshot() { return snapshot.get(); }

        @Override public dev.turboism.sdk.cubism.history.HistoryMoveResult moveTo(
            final long expectedGeneration, final long expectedRevision, final int position
        ) {
            throw new UnsupportedOperationException();
        }
    }
}
