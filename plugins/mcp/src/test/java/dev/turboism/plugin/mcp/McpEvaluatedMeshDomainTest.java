package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpEvaluatedMeshDomainTest {
    @Test
    void pageByteBudgetShrinksRowsWithoutTruncatingTheSample() {
        var f = new Fixture();
        var b = f.sample;
        var params = new ArrayList<ArtMeshEvaluationSnapshot.ParameterValue>();
        for (int i = 0; i < 400; i++) params.add(param("g".repeat(2000) + i, i));
        f.sample = new ArtMeshEvaluationSnapshot(
                b.artMeshId(),
                b.sourceGuid(),
                b.calculatedForm(),
                new ArtMeshEvaluationSnapshot.ParameterState(-7, params),
                b.consumedParameters(),
                12);
        var output = f.read(Map.of("limit", 512));
        assertEquals(true, output.get("ok"), output.toString());
        assertTrue(dev.turboism.sdk.json.Json.stringify(output).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= 240 * 1024);
        assertNotNull(output.get("nextOffset"));
        assertTrue((Integer) output.get("totalRows") > rows(output).size());
    }

    @Test
    void preservesNativeCoordinatesGuidParametersIndependentCountersAndUnprovenFreshness() {
        var f = new Fixture();
        var output = f.read(Map.of("limit", 512));
        assertEquals(true, output.get("ok"), output.toString());
        assertEquals("UNPROVEN", output.get("freshness"));
        var rows = rows(output);
        var metadata = kind(rows, "observation");
        assertEquals(true, metadata.get("liveConsumedValuesMatch"));
        assertEquals(12L, metadata.get("passEntryTimeMillis"));
        assertEquals(Integer.MIN_VALUE, kind(rows, "live_state").get("updateVersion"));
        assertEquals(91, kind(rows, "consumed_state").get("updateVersion"));
        assertEquals("native-calculated", kind(rows, "calculated_form").get("coordinateType"));
        assertEquals(true, kind(rows, "calculated_form").get("interpolatedIdentity"));
        assertEquals(500, kind(rows, "calculated_form").get("drawOrder"));
        assertEquals(.25F, kind(rows, "calculated_position").get("x"));
        assertEquals(
                2,
                rows.stream()
                        .filter(r ->
                                "live_parameter".equals(r.get("kind")) && "same-display".equals(r.get("parameterId")))
                        .count());
        assertFalse(f.tools.registration(McpEvaluatedMeshDomain.READ).transactionEligible());
    }

    @Test
    void absentCalculatedFormNeverSubstitutesStoredPositionsOrClaimsFreshness() {
        var f = new Fixture();
        var b = f.sample;
        f.sample = new ArtMeshEvaluationSnapshot(
                b.artMeshId(),
                b.sourceGuid(),
                Optional.empty(),
                b.liveParameters(),
                new ArtMeshEvaluationSnapshot.ParameterState(-1, List.of()),
                -1);
        var output = f.read(Map.of());
        assertEquals(true, output.get("ok"), output.toString());
        assertEquals("UNPROVEN", output.get("freshness"));
        var rows = rows(output);
        assertEquals(false, kind(rows, "observation").get("calculatedFormPresent"));
        assertEquals(false, kind(rows, "observation").get("liveConsumedValuesMatch"));
        assertTrue(rows.stream().noneMatch(r -> "calculated_position".equals(r.get("kind"))));
    }

    @Test
    void pagesOneImmutableSampleWithStableMetadataAndCompleteRows() {
        var f = new Fixture();
        var first = f.read(Map.of("limit", 2));
        assertEquals(true, first.get("ok"), first.toString());
        var rows = new ArrayList<>(rows(first));
        var page = first;
        while (page.get("nextOffset") != null) {
            page = f.read(Map.of("snapshotId", first.get("snapshotId"), "offset", page.get("nextOffset"), "limit", 2));
            assertEquals(true, page.get("ok"), page.toString());
            assertEquals(first.get("snapshotId"), page.get("snapshotId"));
            rows.addAll(rows(page));
        }
        assertEquals(first.get("totalRows"), rows.size());
        assertEquals(
                3,
                rows.stream()
                        .filter(r -> "calculated_position".equals(r.get("kind")))
                        .count());
        assertThrows(
                UnsupportedOperationException.class,
                () -> f.store
                        .get((String) first.get("snapshotId"), McpRequestRegistry.currentSessionId())
                        .rows()
                        .get(0)
                        .put("kind", "changed"));
    }

    @Test
    void poseAndCalculatedChangesRejectSubsequentPagesEvenAtSameHistoryRevision() {
        for (boolean pose : List.of(false, true)) {
            var f = new Fixture();
            var first = f.read(Map.of("limit", 1));
            var b = f.sample;
            if (pose)
                f.sample = new ArtMeshEvaluationSnapshot(
                        b.artMeshId(),
                        b.sourceGuid(),
                        b.calculatedForm(),
                        new ArtMeshEvaluationSnapshot.ParameterState(12, List.of(param("x", .75F), param("y", 1))),
                        b.consumedParameters(),
                        b.passEntryTimeMillis());
            else
                f.sample = new ArtMeshEvaluationSnapshot(
                        b.artMeshId(),
                        b.sourceGuid(),
                        b.calculatedForm(),
                        b.liveParameters(),
                        b.consumedParameters(),
                        13);
            error(f.read(Map.of("snapshotId", first.get("snapshotId"), "offset", 1)), "STALE_STATE");
        }
    }

    @Test
    void changedSourceTopologyStoredFormParentHistoryAndScopeReject() {
        for (String what : List.of("stored", "parent", "revision", "document", "source", "identity", "count")) {
            var f = new Fixture();
            var b = f.sample;
            switch (what) {
                case "stored" ->
                    f.stored = McpGeometryQualityTest.mesh(
                            McpGeometryQualityTest.UNIT,
                            McpGeometryQualityTest.TRI,
                            McpGeometryQualityTest.UNIT,
                            McpGeometryQualityTest.points(0, 0, 2, 0, 0, 1));
                case "parent" -> f.parent = Optional.of(new DeformerId("other"));
                case "revision" -> f.revision++;
                case "document" -> f.document = "other";
                case "source" ->
                    f.sample = new ArtMeshEvaluationSnapshot(
                            b.artMeshId(),
                            "recreated",
                            b.calculatedForm(),
                            b.liveParameters(),
                            b.consumedParameters(),
                            12);
                case "identity" ->
                    f.sample = new ArtMeshEvaluationSnapshot(
                            new ArtMeshId("other"),
                            b.sourceGuid(),
                            b.calculatedForm(),
                            b.liveParameters(),
                            b.consumedParameters(),
                            12);
                default ->
                    f.sample = new ArtMeshEvaluationSnapshot(
                            b.artMeshId(),
                            b.sourceGuid(),
                            Optional.of(new ArtMeshEvaluationSnapshot.Form(
                                    List.of(new Point2(1, 1)), 500, "native", false)),
                            b.liveParameters(),
                            b.consumedParameters(),
                            12);
            }
            error(f.read(Map.of()), "STALE_STATE");
        }
    }

    @Test
    void midReadSampleChangeCannotProduceStableObservation() {
        var f = new Fixture();
        f.changeOnRead = 2;
        error(f.read(Map.of()), "STALE_STATE");
    }

    @Test
    void sessionsTtlAndCrossObjectOrAuthoringTokenReuseAreRejected() {
        var f = new Fixture();
        error(McpRequestRegistry.inSession("other", () -> f.read(Map.of())), "NOT_FOUND");
        var first = f.read(Map.of());
        f.now = McpAuthoringSnapshotStore.TTL_NANOS;
        error(f.read(Map.of("snapshotId", first.get("snapshotId"))), "NOT_FOUND");
        var g = new Fixture();
        error(g.read(Map.of("snapshotId", g.authoringId)), "STALE_STATE");
        error(g.read(Map.of("authoringSnapshotId", "missing")), "NOT_FOUND");
    }

    @Test
    void dependentCacheInsertionPreservesAuthoringParentWithoutExtendingTtl() {
        var f = new Fixture();
        for (int i = 0; i < 15; i++)
            f.store.put(
                    McpRequestRegistry.currentSessionId(),
                    f.binding(),
                    "art_mesh",
                    "other" + i,
                    "source",
                    f.stored,
                    List.of(),
                    100);
        f.now = 1000;
        var output = f.read(Map.of("limit", 1));
        assertEquals(true, output.get("ok"), output.toString());
        assertNotNull(f.store.get(f.authoringId, McpRequestRegistry.currentSessionId()));
        assertEquals(
                true,
                f.read(Map.of("snapshotId", output.get("snapshotId"), "offset", 1))
                        .get("ok"));
        f.now = McpAuthoringSnapshotStore.TTL_NANOS;
        error(f.read(Map.of("snapshotId", output.get("snapshotId"), "offset", 1)), "NOT_FOUND");
    }

    @Test
    void countsAndEncodedSampleHaveBoundedBudgets() {
        var f = new Fixture();
        var b = f.sample;
        var parameters = new ArrayList<ArtMeshEvaluationSnapshot.ParameterValue>();
        for (int i = 0; i < 2049; i++) parameters.add(param("p" + i, 0));
        f.sample = new ArtMeshEvaluationSnapshot(
                b.artMeshId(),
                b.sourceGuid(),
                b.calculatedForm(),
                new ArtMeshEvaluationSnapshot.ParameterState(1, parameters),
                b.consumedParameters(),
                12);
        error(f.read(Map.of()), "BUDGET_EXCEEDED");
        var g = new Fixture();
        b = g.sample;
        var longParameters = new ArrayList<ArtMeshEvaluationSnapshot.ParameterValue>();
        for (int i = 0; i < 1100; i++) longParameters.add(param("x".repeat(4090) + i, 0));
        g.sample = new ArtMeshEvaluationSnapshot(
                b.artMeshId(),
                b.sourceGuid(),
                b.calculatedForm(),
                new ArtMeshEvaluationSnapshot.ParameterState(1, longParameters),
                b.consumedParameters(),
                12);
        error(g.read(Map.of()), "BUDGET_EXCEEDED");
    }

    @Test
    void readFailuresAreRedactedAndNoOtherSdkMethodsAreCalled() {
        for (RuntimeException failure : List.of(
                new SecurityException("private path"),
                new UnsupportedOperationException("private path"),
                new IllegalStateException("private path"))) {
            var f = new Fixture();
            f.failure = failure;
            var output = f.read(Map.of());
            assertEquals(false, output.get("ok"));
            assertFalse(output.toString().contains("private path"));
        }
    }

    @Test
    void validatesClosedSchemaAndPageOffsets() {
        var f = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> f.read(Map.of("unknown", true)));
        assertThrows(IllegalArgumentException.class, () -> f.read(Map.of("limit", 513)));
        error(f.read(Map.of("offset", 1)), "INVALID_ARGUMENT");
        var first = f.read(Map.of());
        error(f.read(Map.of("snapshotId", first.get("snapshotId"), "offset", 1000)), "INVALID_ARGUMENT");
    }

    static ArtMeshEvaluationSnapshot.ParameterValue param(String guid, float value) {
        return new ArtMeshEvaluationSnapshot.ParameterValue(guid, new ParameterId("same-display"), value);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> output) {
        return (List<Map<String, Object>>) output.get("rows");
    }

    static Map<String, Object> kind(List<Map<String, Object>> rows, String kind) {
        return rows.stream().filter(r -> kind.equals(r.get("kind"))).findFirst().orElseThrow();
    }

    static void error(Map<String, Object> result, String code) {
        assertEquals(false, result.get("ok"), result.toString());
        assertEquals(code, ((Map<?, ?>) result.get("error")).get("code"));
        assertFalse(result.containsKey("rows"));
    }

    private static final class Fixture {
        long now, revision = 1;
        String document = "doc";
        int reads, changeOnRead = -1;
        RuntimeException failure;
        Optional<DeformerId> parent = Optional.empty();
        ArtMeshAuthoringSnapshot stored = McpGeometryQualityTest.mesh(
                McpGeometryQualityTest.UNIT,
                McpGeometryQualityTest.TRI,
                McpGeometryQualityTest.UNIT,
                McpGeometryQualityTest.UNIT);
        ArtMeshEvaluationSnapshot sample = new ArtMeshEvaluationSnapshot(
                new ArtMeshId("mesh"),
                "source",
                Optional.of(new ArtMeshEvaluationSnapshot.Form(
                        McpGeometryQualityTest.points(.25F, 0, 1, 0, 0, 1), 500, "native-calculated", true)),
                new ArtMeshEvaluationSnapshot.ParameterState(Integer.MIN_VALUE, List.of(param("x", 0), param("y", 1))),
                new ArtMeshEvaluationSnapshot.ParameterState(91, List.of(param("y", 1), param("x", 0))),
                12);
        final Drawable drawable = proxy(Drawable.class, (name, a) -> switch (name) {
            case "authoringSnapshot" -> stored;
            case "parentPartId" -> Optional.empty();
            case "parentDeformerId" -> parent;
            case "evaluationSnapshot" -> {
                reads++;
                if (failure != null) throw failure;
                if (reads == changeOnRead) {
                    var b = sample;
                    sample = new ArtMeshEvaluationSnapshot(
                            b.artMeshId(),
                            b.sourceGuid(),
                            b.calculatedForm(),
                            b.liveParameters(),
                            b.consumedParameters(),
                            13);
                }
                yield sample;
            }
            default -> throw new AssertionError("Unexpected SDK mutation or fallback: " + name);
        });
        final CubismModel model = proxy(CubismModel.class, (name, a) -> {
            if (name.equals("drawables"))
                return proxy(Drawables.class, (n, v) -> {
                    if (n.equals("find")) return drawable;
                    throw new AssertionError(n);
                });
            throw new AssertionError(name);
        });
        final McpAuthoringSnapshotStore store = new McpAuthoringSnapshotStore(() -> now);
        final McpExecutionBridge execution = new McpExecutionBridge(new UiScheduler() {
            public Registration runOnUiThread(Runnable r) {
                r.run();
                return () -> {};
            }

            public Registration runOnUiThreadLater(Runnable r, Duration d) {
                throw new AssertionError();
            }
        });
        final McpToolCatalog tools = new McpEvaluatedMeshDomain(
                        store, execution, () -> new McpStoredAuthoringDomain.Context(model, binding()))
                .tools();
        final String authoringId;

        Fixture() {
            var row = new LinkedHashMap<String, Object>();
            row.put("kind", "parent_space");
            row.put("parentPartId", null);
            row.put("parentDeformerId", null);
            authoringId = store.put(
                            McpRequestRegistry.currentSessionId(),
                            binding(),
                            "art_mesh",
                            "mesh",
                            "source",
                            stored,
                            List.of(row),
                            1000)
                    .snapshotId();
        }

        Map<String, Object> binding() {
            return Map.of(
                    "documentId",
                    document,
                    "modelId",
                    "model",
                    "historyGeneration",
                    1L,
                    "historyRevision",
                    revision,
                    "historyDocumentBinding",
                    "hd",
                    "historyManagerBinding",
                    "hm");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> read(Map<String, Object> extra) {
            var args = new LinkedHashMap<String, Object>(Map.of(
                    "documentId", "doc", "modelId", "model", "objectId", "mesh", "authoringSnapshotId", authoringId));
            args.putAll(extra);
            return (Map<String, Object>)
                    tools.call(McpEvaluatedMeshDomain.READ, args).get("structuredContent");
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, java.util.function.BiFunction<String, Object[], Object> f) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> f.apply(m.getName(), a));
    }
}
