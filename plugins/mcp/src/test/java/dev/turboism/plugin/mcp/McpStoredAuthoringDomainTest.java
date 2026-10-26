package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.*;
import dev.turboism.sdk.cubism.history.*;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

final class McpStoredAuthoringDomainTest {
    private static final List<Point2> POINTS = List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1));
    private static final ArtMeshGeometry GEOMETRY = new ArtMeshGeometry(POINTS, POINTS, List.of(0, 1, 2));
    private static final List<Point2> CONTROLS =
            List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1), new Point2(1, 1));

    @Test
    void meshFormOpacityIsExplicitlyUnknownOrNativeWithoutClamping() {
        var unknown = mesh("source");
        var row = McpAuthoringProjection.mesh(unknown).stream()
                .filter(r -> "mesh_form".equals(r.get("kind")))
                .findFirst()
                .orElseThrow();
        assertTrue(row.containsKey("opacity"));
        assertNull(row.get("opacity"));
        for (float value : new float[] {0, .25F, 1, -.5F}) {
            var known = new ArtMeshAuthoringSnapshot(
                    unknown.artMeshId(),
                    unknown.sourceGuid(),
                    unknown.sourceGeometry(),
                    unknown.axes(),
                    unknown.cells(),
                    List.of(new ArtMeshAuthoringSnapshot.Form("stored-form-guid", POINTS, 500, Optional.of(value))),
                    unknown.morphKeys());
            var nativeRow = McpAuthoringProjection.mesh(known).stream()
                    .filter(r -> "mesh_form".equals(r.get("kind")))
                    .findFirst()
                    .orElseThrow();
            assertEquals(value, nativeRow.get("opacity"));
            assertThrows(UnsupportedOperationException.class, () -> nativeRow.put("opacity", 1));
        }
    }

    @Test
    void operationSelectorRowsPreserveTypedObservationsAndRemainBoundToIssuedSnapshot() {
        var fixture = new Fixture();
        fixture.writeSupport = new OrdinaryKeyformWriteSupport(
                "5.3.04",
                OrdinaryKeyformWriteSupport.Check.PRESENT,
                OrdinaryKeyformWriteSupport.Check.PRESENT,
                OrdinaryKeyformWriteSupport.Check.PRESENT,
                OrdinaryKeyformWriteSupport.Check.PRESENT,
                OrdinaryKeyformWriteSupport.Check.ABSENT,
                Map.of(
                        OrdinaryKeyformWriteSupport.Operation.ART_MESH_POSITIONS,
                                OrdinaryKeyformWriteSupport.Check.PRESENT,
                        OrdinaryKeyformWriteSupport.Operation.ART_MESH_VALUES_WITH_OPACITY,
                                OrdinaryKeyformWriteSupport.Check.ABSENT));
        var first = fixture.read(McpStoredAuthoringDomain.MESHES_READ, Map.of("limit", 512));
        assertEquals(true, first.get("ok"), first.toString());
        var row = ((List<?>) first.get("rows"))
                .stream()
                        .map(r -> (Map<?, ?>) r)
                        .filter(r -> "ordinary_write_support".equals(r.get("kind")))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                Map.of("ART_MESH_POSITIONS", "PRESENT", "ART_MESH_VALUES_WITH_OPACITY", "ABSENT"),
                row.get("operationSelectors"));
        assertEquals("ABSENT", row.get("writePermission"), "Selector presence does not grant write permission");
        fixture.writeSupport = OrdinaryKeyformWriteSupport.unknown();
        var cached = fixture.read(
                McpStoredAuthoringDomain.MESHES_READ,
                Map.of("snapshotId", first.get("snapshotId"), "offset", 0, "limit", 512));
        assertEquals(first.get("rows"), cached.get("rows"));
        assertEquals(1, fixture.nativeReads);
        var fresh = fixture.read(McpStoredAuthoringDomain.MESHES_READ, Map.of("limit", 512));
        var legacyRow = ((List<?>) fresh.get("rows"))
                .stream()
                        .map(r -> (Map<?, ?>) r)
                        .filter(r -> "ordinary_write_support".equals(r.get("kind")))
                        .findFirst()
                        .orElseThrow();
        assertEquals(Map.of(), legacyRow.get("operationSelectors"));
        assertEquals("UNKNOWN", legacyRow.get("writerSelectors"));
    }

    @Test
    void writeSupportObservationPreservesKnownAbsentAndLegacyUnknown() {
        for (String tool : List.of(
                McpStoredAuthoringDomain.MESHES_READ,
                McpStoredAuthoringDomain.WARPS_READ,
                McpStoredAuthoringDomain.ROTATIONS_READ)) {
            var fixture = new Fixture();
            fixture.writeSupport = new OrdinaryKeyformWriteSupport(
                    "5.3.04",
                    OrdinaryKeyformWriteSupport.Check.PRESENT,
                    OrdinaryKeyformWriteSupport.Check.ABSENT,
                    OrdinaryKeyformWriteSupport.Check.PRESENT,
                    OrdinaryKeyformWriteSupport.Check.ABSENT,
                    OrdinaryKeyformWriteSupport.Check.ABSENT);
            var result = fixture.read(tool, Map.of("limit", 512));
            assertEquals(true, result.get("ok"), result.toString());
            var row = ((List<?>) result.get("rows"))
                    .stream()
                            .map(r -> (Map<?, ?>) r)
                            .filter(r -> "ordinary_write_support".equals(r.get("kind")))
                            .findFirst()
                            .orElseThrow();
            assertEquals("5.3.04", row.get("hostVersion"));
            assertEquals("ABSENT", row.get("writerSelectors"));
            assertEquals("ABSENT", row.get("writePermission"));
            assertEquals(Map.of(), row.get("operationSelectors"));
            fixture.supportUnavailable = true;
            var unknown = fixture.read(tool, Map.of("limit", 512));
            assertEquals(true, unknown.get("ok"), unknown.toString());
            var old = ((List<?>) unknown.get("rows"))
                    .stream()
                            .map(r -> (Map<?, ?>) r)
                            .filter(r -> "ordinary_write_support".equals(r.get("kind")))
                            .findFirst()
                            .orElseThrow();
            assertEquals("UNKNOWN", old.get("writerSelectors"));
            assertEquals(Map.of(), old.get("operationSelectors"));
        }
    }

    @Test
    void pagesThreeNativeSnapshotTypesWithoutRereadingOrMovingPose() {
        for (String tool : List.of(
                McpStoredAuthoringDomain.MESHES_READ,
                McpStoredAuthoringDomain.WARPS_READ,
                McpStoredAuthoringDomain.ROTATIONS_READ)) {
            var fixture = new Fixture();
            var first = fixture.read(tool, Map.of("limit", 2));
            assertEquals(true, first.get("ok"), first.toString());
            String snapshot = (String) first.get("snapshotId");
            var rows = new ArrayList<Object>((List<?>) first.get("rows"));
            var page = first;
            while (page.get("nextOffset") != null) {
                page = fixture.read(tool, Map.of("snapshotId", snapshot, "offset", page.get("nextOffset"), "limit", 2));
                assertEquals(true, page.get("ok"), page.toString());
                assertEquals(snapshot, page.get("snapshotId"));
                rows.addAll((List<?>) page.get("rows"));
            }
            assertEquals(first.get("totalRows"), rows.size());
            assertEquals(1, fixture.nativeReads, "Pagination must reuse the immutable stored snapshot");
            var axis = rows.stream()
                    .map(value -> (Map<?, ?>) value)
                    .filter(row -> "axis".equals(row.get("kind")))
                    .findFirst()
                    .orElseThrow();
            assertEquals("native-parameter-guid", axis.get("parameterGuid"));
            var cell = rows.stream()
                    .map(value -> (Map<?, ?>) value)
                    .filter(row -> "cell".equals(row.get("kind")))
                    .findFirst()
                    .orElseThrow();
            assertEquals("stored-form-guid", cell.get("formGuid"));
            var eligibility = rows.stream()
                    .map(value -> (Map<?, ?>) value)
                    .filter(row -> "ordinary_write_eligibility".equals(row.get("kind")))
                    .toList();
            assertFalse(eligibility.isEmpty());
            var support = rows.stream()
                    .map(value -> (Map<?, ?>) value)
                    .filter(row -> "ordinary_write_support".equals(row.get("kind")))
                    .findFirst()
                    .orElseThrow();
            assertEquals("UNKNOWN", support.get("writerSelectors"));
            assertEquals("UNKNOWN", support.get("writePermission"));
            assertTrue(eligibility.stream().allMatch(row -> "UNPROVEN".equals(row.get("hostWriteAdmission"))));
            assertFalse(fixture.tools.registration(tool).transactionEligible());
        }
    }

    @Test
    void preservesDuplicateDisplayIdsCompleteTuplesSharedFormsAndNullMorphKeys() {
        var fixture = new Fixture();
        var axes = List.of(
                new ArtMeshAuthoringSnapshot.Axis("guid-x", new ParameterId("duplicate-id"), 0, 0, 1, List.of(0F, 1F)),
                new ArtMeshAuthoringSnapshot.Axis("guid-y", new ParameterId("duplicate-id"), 0, 0, 1, List.of(0F, 1F)));
        var cells = new ArrayList<ArtMeshAuthoringSnapshot.Cell>();
        for (float x : List.of(0F, 1F))
            for (float y : List.of(0F, 1F))
                cells.add(new ArtMeshAuthoringSnapshot.Cell(
                        x == 0 && y == 0 ? "neutral" : "shared",
                        List.of(
                                new ArtMeshAuthoringSnapshot.Coordinate("guid-x", x),
                                new ArtMeshAuthoringSnapshot.Coordinate("guid-y", y))));
        fixture.mesh = new ArtMeshAuthoringSnapshot(
                new ArtMeshId("object-1"),
                "source",
                GEOMETRY,
                axes,
                cells,
                List.of(
                        new ArtMeshAuthoringSnapshot.Form("neutral", POINTS, 500),
                        new ArtMeshAuthoringSnapshot.Form("shared", POINTS, 501)),
                List.of(new ArtMeshAuthoringSnapshot.MorphKey("morph-guid", "shared", Optional.empty())));
        var output = fixture.read(McpStoredAuthoringDomain.MESHES_READ, Map.of("limit", 512));
        assertEquals(true, output.get("ok"), output.toString());
        var rows = ((List<?>) output.get("rows"))
                .stream().map(value -> (Map<?, ?>) value).toList();
        assertEquals(
                2,
                rows.stream()
                        .filter(row -> "axis".equals(row.get("kind")) && "duplicate-id".equals(row.get("parameterId")))
                        .count());
        assertEquals(
                3,
                rows.stream()
                        .filter(row -> "cell".equals(row.get("kind")) && "shared".equals(row.get("formGuid")))
                        .count());
        assertTrue(rows.stream()
                .filter(row -> "cell".equals(row.get("kind")))
                .allMatch(row -> ((List<?>) row.get("coordinates")).size() == 2));
        var morph = rows.stream()
                .filter(row -> "morph_key".equals(row.get("kind")))
                .findFirst()
                .orElseThrow();
        assertTrue(morph.containsKey("keyValue"));
        assertNull(morph.get("keyValue"), "Native null must not be replaced with zero or classified as a basic key");
    }

    @Test
    void rejectsExcessiveProjectedRowsBeforeRetainingASnapshot() {
        var fixture = new Fixture();
        var points = Collections.nCopies(McpAuthoringProjection.MAX_ROWS + 1, new Point2(0, 0));
        fixture.mesh = new ArtMeshAuthoringSnapshot(
                new ArtMeshId("object-1"),
                "source",
                new ArtMeshGeometry(points, points, List.of()),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        assertError(fixture.read(McpStoredAuthoringDomain.MESHES_READ, Map.of()), "BUDGET_EXCEEDED");
    }

    @Test
    void sessionOrHistoryMismatchCannotReadAnIssuedSnapshot() {
        var fixture = new Fixture();
        var first = McpRequestRegistry.inSession(
                "session-a", () -> fixture.read(McpStoredAuthoringDomain.MESHES_READ, Map.of("limit", 1)));
        var page = Map.<String, Object>of("snapshotId", first.get("snapshotId"), "offset", 1);
        assertError(
                McpRequestRegistry.inSession(
                        "session-b", () -> fixture.read(McpStoredAuthoringDomain.MESHES_READ, page)),
                "NOT_FOUND");
        fixture.revision++;
        assertError(
                McpRequestRegistry.inSession(
                        "session-a", () -> fixture.read(McpStoredAuthoringDomain.MESHES_READ, page)),
                "STALE_STATE");
        assertEquals(1, fixture.nativeReads);
    }

    @Test
    void oldSnapshotRetainsFullStoredStateAndNewReadGetsNewSource() {
        var fixture = new Fixture();
        var first = fixture.read(McpStoredAuthoringDomain.MESHES_READ, Map.of("limit", 1));
        String token = (String) first.get("snapshotId");
        var original = fixture.store.get(token, "");
        fixture.mesh = mesh("replacement-source");
        var old = fixture.read(McpStoredAuthoringDomain.MESHES_READ, Map.of("snapshotId", token, "offset", 1));
        assertEquals("mesh-source", old.get("sourceGuid"));
        assertEquals("mesh-source", ((ArtMeshAuthoringSnapshot) original.nativeSnapshot()).sourceGuid());
        var fresh = fixture.read(McpStoredAuthoringDomain.MESHES_READ, Map.of());
        assertEquals("replacement-source", fresh.get("sourceGuid"));
        assertNotEquals(token, fresh.get("snapshotId"));
    }

    @Test
    void documentTypePermissionAndProviderErrorsFailClosed() {
        var fixture = new Fixture();
        assertError(fixture.read(McpStoredAuthoringDomain.MESHES_READ, Map.of("documentId", "other")), "STALE_STATE");
        assertError(fixture.read(McpStoredAuthoringDomain.MESHES_READ, Map.of("offset", 1)), "INVALID_ARGUMENT");
        fixture.present = false;
        assertError(fixture.read(McpStoredAuthoringDomain.MESHES_READ, Map.of()), "NO_ACTIVE_MODEL");
        fixture.present = true;
        fixture.readFailure = new UnsupportedOperationException("not admitted");
        assertError(fixture.read(McpStoredAuthoringDomain.MESHES_READ, Map.of()), "UNAVAILABLE");
        fixture.readFailure = new SecurityException("token=do-not-leak");
        var denied = fixture.read(McpStoredAuthoringDomain.MESHES_READ, Map.of());
        assertError(denied, "PERMISSION_DENIED");
        assertFalse(denied.toString().contains("do-not-leak"));
    }

    private static ArtMeshAuthoringSnapshot mesh(String source) {
        return new ArtMeshAuthoringSnapshot(
                new ArtMeshId("object-1"),
                source,
                GEOMETRY,
                List.of(new ArtMeshAuthoringSnapshot.Axis(
                        "native-parameter-guid", new ParameterId("duplicate-display-id"), -1, 0, 1, List.of(0F))),
                List.of(new ArtMeshAuthoringSnapshot.Cell(
                        "stored-form-guid",
                        List.of(new ArtMeshAuthoringSnapshot.Coordinate("native-parameter-guid", 0)))),
                List.of(new ArtMeshAuthoringSnapshot.Form("stored-form-guid", POINTS, 500)),
                List.of());
    }

    private static void assertError(Map<String, Object> output, String code) {
        assertEquals(false, output.get("ok"), output.toString());
        assertEquals(code, ((Map<?, ?>) output.get("error")).get("code"), output.toString());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.util.function.BiFunction<String, Object[], Object> call) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (instance, method, args) -> call.apply(method.getName(), args));
    }

    private static final class Fixture {
        boolean present = true;
        boolean requestingRotation;
        long revision = 2;
        int nativeReads;
        RuntimeException readFailure;
        ArtMeshAuthoringSnapshot mesh = mesh("mesh-source");
        final WarpAuthoringSnapshot warp = new WarpAuthoringSnapshot(
                new DeformerId("object-1"),
                "warp-source",
                1,
                1,
                false,
                List.of(new WarpAuthoringSnapshot.Axis(
                        "native-parameter-guid", new ParameterId("duplicate-display-id"), -1, 0, 1, List.of(0F))),
                List.of(new WarpAuthoringSnapshot.Cell(
                        "stored-form-guid", List.of(new WarpAuthoringSnapshot.Coordinate("native-parameter-guid", 0)))),
                List.of(new WarpAuthoringSnapshot.Form("stored-form-guid", CONTROLS)));
        final RotationAuthoringSnapshot rotation = new RotationAuthoringSnapshot(
                new DeformerId("object-1"),
                "rotation-source",
                35,
                List.of(new RotationAuthoringSnapshot.Axis(
                        "native-parameter-guid", new ParameterId("duplicate-display-id"), -1, 0, 1, List.of(0F))),
                List.of(new RotationAuthoringSnapshot.Cell(
                        "stored-form-guid",
                        List.of(new RotationAuthoringSnapshot.Coordinate("native-parameter-guid", 0)))),
                List.of(new RotationAuthoringSnapshot.Form(
                        "stored-form-guid", new RotationDeformerForm(12, 1, 2, 1.1F, false, true))));
        final Drawable drawable = proxy(Drawable.class, (method, args) -> readObject(method, mesh));
        final WarpDeformer warpHandle = proxy(WarpDeformer.class, (method, args) -> readObject(method, warp));
        final RotationDeformer rotationHandle =
                proxy(RotationDeformer.class, (method, args) -> readObject(method, rotation));
        final ModelSnapshot modelSnapshot =
                new ModelSnapshot("model-1", "Model", List.of(), List.of(), List.of(), List.of());
        final CubismModel model = proxy(CubismModel.class, (method, args) -> switch (method) {
            case "id" -> new ModelId("native-source-guid");
            case "drawables" ->
                proxy(Drawables.class, (m, a) -> {
                    if (m.equals("find")) return drawable;
                    throw new UnsupportedOperationException(m);
                });
            case "deformers" ->
                proxy(Deformers.class, (m, a) -> {
                    if (m.equals("find")) return requestingRotation ? rotationHandle : warpHandle;
                    throw new UnsupportedOperationException(m);
                });
            default -> throw new UnsupportedOperationException(method);
        });
        final CubismHistory history = proxy(CubismHistory.class, (method, args) -> {
            if (method.equals("snapshot"))
                return new HistorySnapshot(
                        HistorySnapshot.Availability.AVAILABLE, 1, revision, 0, List.of(), false, false);
            throw new UnsupportedOperationException(method);
        });
        final CubismFacade facade = proxy(CubismFacade.class, (method, args) -> switch (method) {
            case "activeModelDocument" ->
                present
                        ? Optional.of(new DocumentSnapshot(
                                "document-1", "Model", "model.cmo3", Optional.empty(), Optional.of(modelSnapshot)))
                        : Optional.empty();
            case "activeModel" -> present ? Optional.of(modelSnapshot) : Optional.empty();
            case "model" -> (CubismModelAccess) () -> model;
            case "history" -> history;
            default -> throw new UnsupportedOperationException(method);
        });
        final McpAuthoringSnapshotStore store = new McpAuthoringSnapshotStore();
        final McpToolCatalog tools = new McpStoredAuthoringDomain(
                        facade,
                        new McpExecutionBridge(new UiScheduler() {
                            @Override
                            public Registration runOnUiThread(Runnable work) {
                                work.run();
                                return () -> {};
                            }

                            @Override
                            public Registration runOnUiThreadLater(Runnable work, Duration delay) {
                                throw new UnsupportedOperationException();
                            }
                        }),
                        store)
                .tools();

        OrdinaryKeyformWriteSupport writeSupport = OrdinaryKeyformWriteSupport.unknown();
        boolean supportUnavailable;

        private Object readObject(String method, Object state) {
            return switch (method) {
                case "ordinaryKeyformWriteSupport" -> {
                    if (supportUnavailable) throw new UnsupportedOperationException();
                    yield writeSupport;
                }
                case "authoringSnapshot" -> {
                    nativeReads++;
                    if (readFailure != null) throw readFailure;
                    yield state;
                }
                case "parentPartId", "parentDeformerId" -> Optional.empty();
                default -> throw new UnsupportedOperationException(method);
            };
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> read(String tool, Map<String, Object> extra) {
            requestingRotation = tool.equals(McpStoredAuthoringDomain.ROTATIONS_READ);
            var args = new LinkedHashMap<String, Object>(
                    Map.of("documentId", "document-1", "modelId", "model-1", "objectId", "object-1"));
            args.putAll(extra);
            return (Map<String, Object>) tools.call(tool, args).get("structuredContent");
        }
    }
}
