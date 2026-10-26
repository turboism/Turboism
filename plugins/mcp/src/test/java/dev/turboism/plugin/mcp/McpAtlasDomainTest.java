package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.textureatlas.*;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpAtlasDomainTest {
    @Test
    void readAndPlanArePureAndApplyUsesOriginalIssuedTargetWithExactReadback() {
        var f = new Fixture();
        var request = f.plan("apply");
        assertEquals(0, f.writes);
        assertEquals("QUEUED", f.call(McpAtlasDomain.APPLY, request).get("phase"));
        f.worker.runNext();
        var result = f.result("apply");
        assertEquals("APPLIED", result.get("outcome"), result.toString());
        assertEquals(true, result.get("readbackVerified"));
        assertEquals(result.get("expectedLayoutHash"), result.get("actualLayoutHash"));
        assertSame(f.issued, f.applied);
        assertEquals(false, result.get("nativeUndoReceiptAvailable"));
        assertEquals(false, result.get("textureBindingsVerified"));
        f.forbidHost = true;
        assertEquals(result, f.result("apply"));
        assertEquals(result, f.call(McpAtlasDomain.APPLY, request));
        assertEquals(1, f.writes);
        var conflict = new LinkedHashMap<>(request);
        conflict.put("snapshotId", "different");
        assertEquals(
                "OPERATION_ID_CONFLICT", f.call(McpAtlasDomain.APPLY, conflict).get("error"));
    }

    @Test
    void rejectsHistoryTabGenerationNativeMetadataAndExpiredSnapshotsBeforeWrite() {
        for (String change : List.of("revision", "generation", "tab", "items", "constraints", "ttl", "session")) {
            var f = new Fixture();
            var request = f.plan(change);
            switch (change) {
                case "revision" -> f.model.revision++;
                case "generation" -> f.model.generation++;
                case "tab" -> f.model.document = "other";
                case "items" -> f.items = List.of(new TextureAtlasLayoutItem("a", 2, 3));
                case "constraints" -> f.constraints = new TextureAtlasLayoutConstraints(16, 16, 2, 1, 4, false, false);
                case "ttl" -> f.now = McpAtlasDomain.TTL;
                case "session" -> f.session = "foreign";
            }
            f.call(McpAtlasDomain.APPLY, request);
            f.worker.runNext();
            assertEquals("NOT_APPLIED", f.result(change).get("outcome"), change);
            assertEquals(0, f.writes);
        }
    }

    @Test
    void nativeFailuresAreConservativeAndSanitized() {
        for (var failure : TextureAtlasLayoutFailureCode.values()) {
            var f = new Fixture();
            var request = f.plan("failure");
            f.failure = failure;
            f.call(McpAtlasDomain.APPLY, request);
            f.worker.runNext();
            var result = f.result("failure");
            assertEquals(
                    Set.of(
                                            TextureAtlasLayoutFailureCode.PROVIDER_FAILED,
                                            TextureAtlasLayoutFailureCode.PROVIDER_REJECTED)
                                    .contains(failure)
                            ? "OUTCOME_UNKNOWN"
                            : "NOT_APPLIED",
                    result.get("outcome"));
            assertEquals(failure.name(), result.get("nativeFailureCode"));
            assertFalse(result.toString().contains("secret"));
        }
    }

    @Test
    void nativeResponseLossIsUnknownAndPostReadbackFailureWarnsWithoutRetry() {
        for (String kind : List.of("throw", "read", "wrongPlan", "changedItems")) {
            var f = new Fixture();
            var request = f.plan(kind);
            f.afterWrite = () -> {
                switch (kind) {
                    case "throw" -> throw new IllegalStateException("secret");
                    case "read" -> f.readFailure = true;
                    case "wrongPlan" -> f.layout = new TextureAtlasLayoutPlan(16, 16, 1, List.of());
                    case "changedItems" -> f.items = List.of();
                }
            };
            f.call(McpAtlasDomain.APPLY, request);
            f.worker.runNext();
            var result = f.result(kind);
            assertEquals(
                    kind.equals("throw") ? "OUTCOME_UNKNOWN" : "APPLIED_WITH_READBACK_WARNING", result.get("outcome"));
            f.forbidHost = true;
            assertEquals(result, f.call(McpAtlasDomain.APPLY, request));
            assertEquals(1, f.writes);
        }
    }

    @Test
    void cancellationBeforeAdmissionAndAfterAdmissionAreDistinct() {
        var f = new Fixture();
        var request = f.plan("cancel");
        f.call(McpAtlasDomain.APPLY, request);
        f.cancel("cancel");
        f.worker.runNext();
        assertEquals("CANCELLED", f.result("cancel").get("outcome"));
        assertEquals(0, f.writes);
        var g = new Fixture();
        var second = g.plan("admitted");
        g.afterWrite = () -> g.cancel("admitted");
        g.call(McpAtlasDomain.APPLY, second);
        g.worker.runNext();
        assertEquals("APPLIED", g.result("admitted").get("outcome"));
        assertEquals(true, g.result("admitted").get("cancelRequested"));
        assertEquals(false, g.result("admitted").get("hostCancellationConfirmed"));
    }

    @Test
    void sessionDropAndClosePreventQueuedWorkButNeverUndoAdmittedWork() {
        for (boolean close : List.of(false, true))
            for (boolean admitted : List.of(false, true)) {
                var f = new Fixture();
                var request = f.plan("close");
                Runnable stop = () -> {
                    if (close) f.domain.close();
                    else f.domain.dropSession("session");
                };
                if (admitted) f.afterWrite = stop;
                f.call(McpAtlasDomain.APPLY, request);
                if (!admitted) stop.run();
                f.worker.runNext();
                assertEquals(admitted ? 1 : 0, f.writes);
                assertEquals(
                        close ? "UNAVAILABLE" : "NOT_FOUND", f.result("close").get("error"));
            }
    }

    @Test
    void busyWorkerFailureRetentionAndUnknownResultsAreBounded() {
        var f = new Fixture();
        var request = f.plan("first");
        f.call(McpAtlasDomain.APPLY, request);
        var other = new LinkedHashMap<>(request);
        other.put("operationId", "other");
        assertEquals("ATLAS_BUSY", f.call(McpAtlasDomain.APPLY, other).get("error"));
        assertEquals("NOT_FOUND", f.result("missing").get("error"));
        f.worker.runNext();
        f.worker.reject = true;
        for (int i = 0; i < 255; i++) {
            other.put("operationId", "reject" + i);
            assertEquals("NOT_APPLIED", f.call(McpAtlasDomain.APPLY, other).get("outcome"));
        }
        other.put("operationId", "full");
        assertEquals("RESULT_STORE_FULL", f.call(McpAtlasDomain.APPLY, other).get("error"));
        assertEquals(1, f.writes);
    }

    @Test
    void noChangeUsesReadbackAndUnavailableAndInvalidPlansNeverWrite() {
        var f = new Fixture();
        var request = f.plan("same");
        f.layout = McpAtlasLayout.parse(request.get("layout"));
        request = f.plan("same");
        f.noChange = true;
        f.call(McpAtlasDomain.APPLY, request);
        f.worker.runNext();
        assertEquals("NO_CHANGE", f.result("same").get("outcome"));
        assertEquals(true, f.result("same").get("readbackVerified"));
        var g = new Fixture();
        var bad = g.plan("invalid");
        var layout = new LinkedHashMap<>(map(bad.get("layout")));
        layout.put("placements", List.of());
        bad.put("layout", layout);
        g.call(McpAtlasDomain.APPLY, bad);
        g.worker.runNext();
        assertEquals("INVALID_ARGUMENT", g.result("invalid").get("code"));
        assertEquals(0, g.writes);
        g.available = false;
        assertEquals("UNAVAILABLE", g.read().get("error"));
    }

    @Test
    void finalPreAdmissionCancellationAndSnapshotEvictionDoNotWrite() {
        var f = new Fixture();
        var request = f.plan("boundary");
        f.call(McpAtlasDomain.APPLY, request);
        f.onAvailable = () -> f.cancel("boundary");
        f.worker.runNext();
        assertEquals("CANCELLED", f.result("boundary").get("outcome"));
        assertEquals(0, f.writes);
        var g = new Fixture();
        var old = g.plan("evicted");
        for (int i = 0; i < 16; i++) assertEquals(true, g.read().get("ok"));
        g.call(McpAtlasDomain.APPLY, old);
        g.worker.runNext();
        assertEquals("NOT_FOUND", g.result("evicted").get("code"));
        assertEquals(0, g.writes);
    }

    @Test
    void multiplePagesUseHashedNativeIdentityAndAllowExpectedPageRenaming() {
        var f = new Fixture();
        f.items = List.of(new TextureAtlasLayoutItem("a", 14, 14), new TextureAtlasLayoutItem("b", 14, 14));
        var request = f.plan("pages");
        assertEquals(2, map(request.get("layout")).get("pageCount"));
        f.call(McpAtlasDomain.APPLY, request);
        f.worker.runNext();
        assertEquals("APPLIED", f.result("pages").get("outcome"));
        var read = f.read();
        assertEquals(true, read.get("ok"), read.toString());
        var atlas = map(read.get("atlas"));
        assertTrue(atlas.containsKey("atlasIdHash"));
        assertFalse(atlas.containsKey("atlasId"));
    }

    @Test
    void readbackCanonicalizesPlacementOrderAndImplicitUniformPageSizes() {
        var f = new Fixture();
        var request = f.plan("canonical");
        f.afterWrite = () -> {
            var rows = new ArrayList<>(f.layout.placements());
            Collections.reverse(rows);
            f.layout = new TextureAtlasLayoutPlan(
                    f.layout.pageWidth(), f.layout.pageHeight(), f.layout.pageCount(), f.layout.pageNames(), rows);
        };
        f.call(McpAtlasDomain.APPLY, request);
        f.worker.runNext();
        assertEquals("APPLIED", f.result("canonical").get("outcome"));
        assertEquals(true, f.result("canonical").get("readbackVerified"));
    }

    @Test
    void nativeOutlineAndCurrentLayoutAreComparedEvenWithoutHistoryChanges() {
        for (boolean outline : List.of(false, true)) {
            var f = new Fixture();
            f.items = List.of(new TextureAtlasLayoutItem("a", 3, 3, TextureAtlasOutline.rect(2, 2)));
            var request = f.plan("cas");
            if (outline) f.items = List.of(new TextureAtlasLayoutItem("a", 3, 3, TextureAtlasOutline.rect(3, 2)));
            else f.layout = McpAtlasLayout.parse(request.get("layout"));
            f.call(McpAtlasDomain.APPLY, request);
            f.worker.runNext();
            assertEquals("STALE_STATE", f.result("cas").get("code"));
            assertEquals(0, f.writes);
        }
    }

    @Test
    void schemasRejectOpaqueTargetsPathsPoliciesAndInvalidBudgets() {
        var f = new Fixture();
        var request = f.plan("schema");
        request.put("target", "opaque");
        assertThrows(IllegalArgumentException.class, () -> f.call(McpAtlasDomain.APPLY, request));
        assertThrows(
                IllegalArgumentException.class,
                () -> f.call(
                        McpAtlasDomain.READ,
                        Map.of("documentId", "doc", "modelId", "model", "maxTotalPixels", 67108865)));
        assertEquals(0, f.writes);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object v) {
        return (Map<String, Object>) v;
    }

    static class Target implements TextureAtlasLayoutTarget {}

    static class Fixture {
        final McpImageBindingDomainTest.Fixture model = new McpImageBindingDomainTest.Fixture();
        final McpFileInspectDomainTest.Worker worker = new McpFileInspectDomainTest.Worker();
        long now;
        int writes;
        String session = "session";
        boolean forbidHost, available = true, readFailure, noChange;
        Runnable afterWrite = () -> {}, onAvailable = () -> {};
        TextureAtlasLayoutFailureCode failure;
        TextureAtlasLayoutTarget issued, applied;
        List<TextureAtlasLayoutItem> items =
                List.of(new TextureAtlasLayoutItem("a", 3, 3), new TextureAtlasLayoutItem("b", 4, 4));
        TextureAtlasLayoutConstraints constraints = new TextureAtlasLayoutConstraints(16, 16, 1, 1, 4, false, false);
        TextureAtlasLayoutPlan layout = new TextureAtlasLayoutPlan(16, 16, 1, List.of());
        final TextureAtlasLayoutService service = new TextureAtlasLayoutService() {
            public boolean isAvailable() {
                check();
                onAvailable.run();
                return available;
            }

            public Optional<TextureAtlasLayoutSnapshot> current() {
                check();
                if (readFailure) throw new IllegalStateException("secret");
                var target = new Target();
                if (issued == null) issued = target;
                return Optional.of(new TextureAtlasLayoutSnapshot(
                        target, "doc", "model", String.join("\u001f", layout.pageNames()), constraints, items, layout));
            }

            public TextureAtlasLayoutApplyResult apply(TextureAtlasLayoutTarget target, TextureAtlasLayoutPlan plan) {
                check();
                writes++;
                applied = target;
                if (failure != null) return TextureAtlasLayoutApplyResult.failed(failure, "secret");
                layout = plan;
                if (!noChange) model.revision++;
                afterWrite.run();
                return noChange ? TextureAtlasLayoutApplyResult.noChange() : TextureAtlasLayoutApplyResult.applied();
            }
        };
        final CubismFacade facade = (CubismFacade) Proxy.newProxyInstance(
                CubismFacade.class.getClassLoader(), new Class<?>[] {CubismFacade.class}, (p, m, a) -> {
                    check();
                    assertEquals("textureAtlasLayouts", m.getName());
                    return service;
                });
        final McpAtlasDomain domain = new McpAtlasDomain(
                facade,
                model.execution,
                () -> {
                    check();
                    return new McpStoredAuthoringDomain.Context(model.model, model.binding());
                },
                () -> now,
                worker);
        final McpToolCatalog tools = domain.tools();

        void check() {
            if (forbidHost) throw new AssertionError("Historical replay/result must not read host");
        }

        Map<String, Object> read() {
            return call(McpAtlasDomain.READ, Map.of("documentId", "doc", "modelId", "model", "maxTotalPixels", 4096));
        }

        Map<String, Object> plan(String op) {
            var snapshot = read();
            assertEquals(true, snapshot.get("ok"), snapshot.toString());
            var result = call(
                    McpAtlasDomain.PLAN,
                    Map.of(
                            "documentId",
                            "doc",
                            "modelId",
                            "model",
                            "snapshotId",
                            snapshot.get("snapshotId"),
                            "operationId",
                            op,
                            "policy",
                            "SHELF_HEIGHT_DESCENDING"));
            assertEquals(true, result.get("ok"), result.toString());
            return new LinkedHashMap<>(map(result.get("applyRequest")));
        }

        Map<String, Object> result(String op) {
            return call(McpAtlasDomain.RESULT, Map.of("operationId", op));
        }

        Map<String, Object> cancel(String op) {
            return call(McpAtlasDomain.CANCEL, Map.of("operationId", op));
        }

        Map<String, Object> call(String name, Map<String, Object> args) {
            return map(McpRequestRegistry.inSession(session, () -> tools.call(name, args))
                    .get("structuredContent"));
        }
    }
}
