package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.*;
import dev.turboism.sdk.cubism.history.*;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpMeshCreationDomainTest {
    static final List<Point2> SOURCE = List.of(new Point2(0, 0), new Point2(10, 0), new Point2(0, 10));
    static final List<Point2> DEFAULT = List.of(new Point2(20, 30), new Point2(32, 30), new Point2(20, 42));
    static final ArtMeshGeometry GEOMETRY = new ArtMeshGeometry(
            SOURCE, List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1)), List.of(0, 1, 2));

    static Map<String, Object> request(String name, String image) {
        var m = new LinkedHashMap<String, Object>();
        m.put("name", name);
        m.put("parentPartId", "part");
        m.put("modelImageId", image);
        m.put("sourceGeometry", McpMeshCreationDomain.geometryJson(GEOMETRY));
        m.put("defaultPositions", McpMeshCreationDomain.pointsJson(DEFAULT));
        return m;
    }

    @Test
    void createsDistinctSourceAndDefaultBatchAndBindsInOneTransaction() {
        var f = new Fixture();
        var original = f.meshes.get("old");
        var plan = f.plan(request(" Face ", "image"), request("Hair", null));
        assertEquals(true, plan.get("ok"), plan.toString());
        assertEquals(0, f.writes);
        assertEquals(0, f.transactions);
        var out = f.create("create", plan);
        assertEquals("APPLIED", out.get("outcome"), out.toString());
        assertEquals(true, out.get("readbackVerified"));
        assertEquals(false, out.get("renderVerified"));
        assertEquals(1, f.transactions);
        assertEquals(1, f.batches);
        assertEquals(1, f.binds);
        assertEquals(original, f.meshes.get("old"));
        var face = f.meshes.get("new1");
        assertEquals("Face", face.name);
        assertEquals(GEOMETRY, face.snapshot.sourceGeometry());
        assertEquals(DEFAULT, face.snapshot.forms().get(0).positions());
        assertEquals("image", face.image);
        var rows = (List<?>) out.get("createdMeshes");
        assertEquals(true, ((Map<?, ?>) rows.get(0)).get("imageBound"));
        assertEquals(false, ((Map<?, ?>) rows.get(1)).get("imageBound"));
        assertEquals("form-new1", ((Map<?, ?>) rows.get(0)).get("formGuid"));
        f.noReads = true;
        assertEquals(out, f.result("create"));
        assertEquals(out, f.create("create", plan));
        assertEquals(
                "OPERATION_ID_CONFLICT",
                f.create("create", Map.of("planId", "other")).get("code"));
    }

    @Test
    void triangulationReturnsChangedNativeOrderWithoutInventingUvsOrMutating() {
        var f = new Fixture();
        f.triangulated =
                new ArtMeshSourceTriangulation(List.of(SOURCE.get(2), SOURCE.get(0), SOURCE.get(1)), List.of(1, 2, 0));
        var out = f.call(
                McpMeshCreationDomain.TRIANGULATE,
                Map.of(
                        "documentId",
                        "doc",
                        "modelId",
                        "model",
                        "coordinateSpace",
                        "CANVAS",
                        "geometry",
                        McpMeshCreationDomain.geometryJson(GEOMETRY)));
        assertEquals(true, out.get("ok"), out.toString());
        assertEquals(false, out.get("positionsUnchanged"));
        assertEquals("NOT_RETURNED", out.get("uvMapping"));
        assertEquals(f.triangulated.triangleIndices(), out.get("triangleIndices"));
        assertFalse(out.containsKey("uvs"));
        assertEquals(0, f.writes);
        assertEquals(0, f.transactions);
    }

    @Test
    void staleDeclaredStateRejectsBeforeAnyWrite() {
        for (String change :
                List.of("revision", "generation", "document", "guid", "positions", "parent", "lock", "image")) {
            var f = new Fixture();
            var plan = f.plan(request("Face", null));
            switch (change) {
                case "revision" -> f.revision++;
                case "generation" -> f.generation++;
                case "document" -> f.document = "other";
                case "guid" -> f.meshes.put("old", mesh("old", "other-guid", "old", GEOMETRY, DEFAULT, null));
                case "positions" -> f.meshes.put("old", mesh("old", "guid-old", "old", GEOMETRY, DEFAULT, null));
                case "parent" -> f.partName = "Changed";
                case "lock" -> f.locked = true;
                case "image" -> f.imageName = "Changed";
            }
            var out = f.create("stale", plan);
            assertEquals("NOT_APPLIED", out.get("outcome"), change + out);
            assertEquals("STALE_STATE", out.get("code"), change + out);
            assertEquals(0, f.writes);
        }
    }

    @Test
    void checksAgainInsideTransaction() {
        var f = new Fixture();
        var p = f.plan(request("Face", null));
        f.before = () -> f.revision++;
        var out = f.create("race", p);
        assertEquals("ROLLED_BACK", out.get("outcome"), out.toString());
        assertEquals(0, f.writes);
    }

    @Test
    void partialCreationAndBindingFailureRollBackEntireBatch() {
        for (boolean bindFailure : List.of(false, true)) {
            var f = new Fixture();
            var p = f.plan(request("Face", "image"), request("Hair", null));
            f.failBatch = !bindFailure;
            f.failBind = bindFailure;
            var out = f.create("failure", p);
            assertEquals("ROLLED_BACK", out.get("outcome"), out.toString());
            assertEquals(true, out.get("rollbackStateVerified"));
            assertEquals(Set.of("old"), f.meshes.keySet());
            assertEquals(out, f.result("failure"));
        }
    }

    @Test
    void wrongSourceDefaultAndExistingMutationCauseRollback() {
        for (String fault : List.of("source", "default", "existing", "count")) {
            var f = new Fixture();
            var p = f.plan(request("Face", null));
            f.fault = fault;
            var out = f.create("wrong", p);
            assertEquals("ROLLED_BACK", out.get("outcome"), fault + out);
            assertEquals(true, out.get("rollbackStateVerified"));
            assertEquals(Set.of("old"), f.meshes.keySet());
        }
    }

    @Test
    void postCommitReadFailureIsWarningAndDoesNotRepeatWrites() {
        var f = new Fixture();
        var p = f.plan(request("Face", null));
        f.afterCommit = () -> f.readFailure = true;
        var out = f.create("warning", p);
        assertEquals("APPLIED_WITH_READBACK_WARNING", out.get("outcome"), out.toString());
        assertEquals(2, f.meshes.size());
        f.noReads = true;
        assertEquals(out, f.create("warning", p));
        assertEquals(out, f.result("warning"));
        assertEquals(1, f.batches);
    }

    @Test
    void lostResponseAndRecoveryFailureRemainUnknown() {
        for (boolean lost : List.of(false, true)) {
            var f = new Fixture();
            var p = f.plan(request("Face", null));
            f.loseResult = lost;
            f.failBatch = !lost;
            f.recoveryFailed = !lost;
            var out = f.create("unknown", p);
            assertEquals("OUTCOME_UNKNOWN", out.get("outcome"), out.toString());
            f.noReads = true;
            assertEquals(out, f.result("unknown"));
            assertEquals(out, f.create("unknown", p));
        }
    }

    @Test
    void expirySessionIsolationAndUnknownResultDoNotWrite() {
        var f = new Fixture();
        var p = McpRequestRegistry.inSession("owner", () -> f.plan(request("Face", null)));
        assertEquals("NOT_FOUND", f.create("foreign", p).get("code"));
        f.now = McpMeshCreationDomain.TTL;
        assertEquals(
                "NOT_FOUND",
                McpRequestRegistry.inSession("owner", () -> f.create("expired", p))
                        .get("code"));
        f.noReads = true;
        assertEquals("OUTCOME_UNKNOWN", f.result("missing").get("outcome"));
        assertEquals(0, f.writes);
    }

    @Test
    void invalidGeometryAndUnsupportedParentsFailClosed() {
        var f = new Fixture();
        for (String field : List.of("defaultPositions", "sourceGeometry", "parentPartId", "modelImageId")) {
            var r = new LinkedHashMap<>(request("Face", null));
            if (field.equals("defaultPositions"))
                r.put(field, McpMeshCreationDomain.pointsJson(List.of(SOURCE.get(0), SOURCE.get(0), SOURCE.get(0))));
            if (field.equals("sourceGeometry")) {
                var g = new LinkedHashMap<>(McpMeshCreationDomain.geometryJson(GEOMETRY));
                g.put("triangleIndices", List.of(0, 1, 2, 2, 1, 0));
                r.put(field, g);
            }
            if (field.equals("parentPartId")) r.put(field, "missing");
            if (field.equals("modelImageId")) r.put(field, "missing");
            assertEquals(false, f.plan(r).get("ok"), field);
        }
        f.locked = true;
        assertEquals("UNAVAILABLE", f.plan(request("Face", null)).get("error"));
        f.locked = false;
        f.visible = false;
        assertEquals("UNAVAILABLE", f.plan(request("Face", null)).get("error"));
        assertEquals(0, f.writes);
    }

    @Test
    void missingBatchSdkMethodRollsBackWithoutLeavingRunning() {
        var f = new Fixture();
        var p = f.plan(request("Face", null));
        f.missingBatch = true;
        var out = f.create("old-runtime", p);
        assertEquals("ROLLED_BACK", out.get("outcome"));
        assertEquals(true, out.get("rollbackStateVerified"));
        f.noReads = true;
        assertEquals(out, f.result("old-runtime"));
    }

    @Test
    void schemasAndTransactionMetadataAreClosed() {
        var f = new Fixture();
        for (String name : List.of(
                McpMeshCreationDomain.TRIANGULATE,
                McpMeshCreationDomain.PLAN,
                McpMeshCreationDomain.CREATE,
                McpMeshCreationDomain.RESULT)) {
            assertTrue(f.tools.registration(name).exactOutputSchema());
            assertFalse(f.tools.registration(name).transactionEligible());
        }
        assertEquals(
                McpOperationEffect.TRANSACTION_CONTROL,
                f.tools.registration(McpMeshCreationDomain.CREATE).effect());
        var bad = new LinkedHashMap<>(request("Face", null));
        bad.put("path", "/tmp/arbitrary");
        assertThrows(IllegalArgumentException.class, () -> f.plan(bad));
        assertEquals(0, f.writes);
    }

    @Test
    void geometryLimitsRejectBadCountsNonfiniteUvsAndDuplicateFaces() {
        var r = request("Face", null);
        assertThrows(
                IllegalArgumentException.class, () -> McpMeshCreationDomain.parseRequests(Collections.nCopies(9, r)));
        for (String kind : List.of("nan", "uv", "count", "index", "face")) {
            var g = new LinkedHashMap<>(McpMeshCreationDomain.geometryJson(GEOMETRY));
            switch (kind) {
                case "nan" ->
                    g.put(
                            "positions",
                            List.of(Map.of("x", Double.NaN, "y", 0), Map.of("x", 1, "y", 0), Map.of("x", 0, "y", 1)));
                case "uv" -> g.put("uvs", McpMeshCreationDomain.pointsJson(DEFAULT));
                case "count" -> g.put("uvs", List.of(Map.of("x", 0, "y", 0)));
                case "index" -> g.put("triangleIndices", List.of(0, 1, 3));
                case "face" -> g.put("triangleIndices", List.of(0, 1, 2, 0, 2, 1));
            }
            assertThrows(IllegalArgumentException.class, () -> McpMeshCreationDomain.geometry(g, true), kind);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> McpMeshCreationDomain.parseRequests(List.of(request("   ", null))));
    }

    @Test
    void modelSizeBudgetAndDuplicateNativeGuidFailBeforeWrite() {
        var f = new Fixture();
        for (int i = 0; i < 512; i++) f.meshes.put("m" + i, mesh("m" + i, "g" + i, "Mesh", GEOMETRY, SOURCE, null));
        assertEquals("BUDGET_EXCEEDED", f.plan(request("Face", null)).get("error"));
        assertEquals(0, f.writes);
        var g = new Fixture();
        g.meshes.put("collision", mesh("collision", "guid-old", "Other", GEOMETRY, SOURCE, null));
        assertEquals("STALE_STATE", g.plan(request("Face", null)).get("error"));
    }

    @Test
    void plansAreBoundedAndCrossSessionResultsRemainPrivate() {
        var f = new Fixture();
        var first = f.plan(request("Face", null));
        for (int i = 0; i < 16; i++)
            assertEquals(true, f.plan(request("Face", null)).get("ok"));
        assertEquals("NOT_FOUND", f.create("evicted", first).get("code"));
        var out = McpRequestRegistry.inSession("owner", () -> f.create("private", f.plan(request("Face", null))));
        assertEquals("APPLIED", out.get("outcome"));
        f.noReads = true;
        assertEquals("OUTCOME_UNKNOWN", f.result("private").get("outcome"));
        assertEquals(out, McpRequestRegistry.inSession("owner", () -> f.result("private")));
    }

    @Test
    void invalidNativeTriangulationNeverClaimsSuccessfulPreview() {
        var f = new Fixture();
        f.triangulated = new ArtMeshSourceTriangulation(SOURCE, List.of());
        var out = f.call(
                McpMeshCreationDomain.TRIANGULATE,
                Map.of(
                        "documentId",
                        "doc",
                        "modelId",
                        "model",
                        "coordinateSpace",
                        "CANVAS",
                        "geometry",
                        McpMeshCreationDomain.geometryJson(GEOMETRY)));
        assertEquals("UNAVAILABLE", out.get("error"));
        assertEquals(0, f.writes);
    }

    record Mesh(String id, String guid, String name, ArtMeshAuthoringSnapshot snapshot, String image) {}

    static Mesh mesh(String id, String guid, String name, ArtMeshGeometry source, List<Point2> defaults, String image) {
        return new Mesh(
                id,
                guid,
                name,
                new ArtMeshAuthoringSnapshot(
                        new ArtMeshId(id),
                        guid,
                        source,
                        List.of(),
                        List.of(new ArtMeshAuthoringSnapshot.Cell("form-" + id, List.of())),
                        List.of(new ArtMeshAuthoringSnapshot.Form("form-" + id, defaults, 500, Optional.of(1f))),
                        List.of()),
                image);
    }

    static final class Fixture {
        Map<String, Mesh> meshes = new LinkedHashMap<>();
        long revision, generation = 1, now;
        int writes, transactions, batches, binds;
        String document = "doc", partName = "Part", imageName = "Image", fault = "";
        boolean locked,
                visible = true,
                noReads,
                readFailure,
                failBatch,
                failBind,
                recoveryFailed,
                loseResult,
                missingBatch;
        Runnable before = () -> {}, afterCommit = () -> {};
        ArtMeshSourceTriangulation triangulated = new ArtMeshSourceTriangulation(SOURCE, List.of(0, 1, 2));
        final CubismModel model = McpParameterStructureDomainTest.proxy(CubismModel.class, (name, args) -> {
            guard();
            return switch (name) {
                case "drawables" -> McpParameterStructureDomainTest.proxy(Drawables.class, this::drawables);
                case "parts" ->
                    McpParameterStructureDomainTest.proxy(Parts.class, (n, a) -> {
                        guard();
                        if (n.equals("all")) return List.of(part());
                        throw new AssertionError(n);
                    });
                case "textures" -> McpParameterStructureDomainTest.proxy(ModelTextures.class, this::textures);
                default -> throw new AssertionError(name);
            };
        });
        final AuthoringTransactionService service = new AuthoringTransactionService() {
            public <T> AuthoringTransactionResult<T> execute(
                    AuthoringTransactionOptions options, AuthoringTransactionWork<T> work) {
                transactions++;
                var saved = new LinkedHashMap<>(meshes);
                var start = history();
                T value;
                try {
                    before.run();
                    value = work.run();
                } catch (Exception e) {
                    var receipt =
                            new AuthoringTransactionReceipt("tx", options.label(), start, history(), Optional.empty());
                    if (recoveryFailed) return AuthoringTransactionResult.recoveryFailed(receipt, "injected");
                    meshes = saved;
                    return AuthoringTransactionResult.rolledBack(receipt, "injected");
                }
                revision++;
                var receipt =
                        new AuthoringTransactionReceipt("tx", options.label(), start, history(), Optional.of("entry"));
                afterCommit.run();
                if (loseResult) throw new IllegalStateException("lost response");
                return AuthoringTransactionResult.committed(value, receipt);
            }
        };
        final CubismFacade facade = McpParameterStructureDomainTest.proxy(CubismFacade.class, (n, a) -> {
            if (n.equals("authoringTransactions")) return service;
            throw new AssertionError(n);
        });
        final McpToolCatalog tools = new McpMeshCreationDomain(
                        facade,
                        new McpParameterStructureDomainTest.Fixture().execution,
                        () -> {
                            guard();
                            return new McpStoredAuthoringDomain.Context(model, binding());
                        },
                        () -> now)
                .tools();

        Fixture() {
            meshes.put("old", mesh("old", "guid-old", "old", GEOMETRY, SOURCE, null));
        }

        void guard() {
            if (noReads) throw new AssertionError("unexpected host read");
            if (readFailure) throw new IllegalStateException("read failed");
        }

        Map<String, Object> binding() {
            return Map.of(
                    "documentId",
                    document,
                    "modelId",
                    "model",
                    "historyDocumentBinding",
                    "hd",
                    "historyManagerBinding",
                    "hm",
                    "historyGeneration",
                    generation,
                    "historyRevision",
                    revision);
        }

        HistorySnapshot history() {
            return new HistorySnapshot(
                    HistorySnapshot.Availability.AVAILABLE,
                    generation,
                    revision,
                    0,
                    List.of(),
                    false,
                    false,
                    "hd",
                    "hm");
        }

        Part part() {
            return McpParameterStructureDomainTest.proxy(Part.class, (n, a) -> {
                guard();
                return switch (n) {
                    case "id" -> new PartId("part");
                    case "name" -> partName;
                    case "index" -> 0;
                    case "parentId" -> Optional.empty();
                    case "visible" -> visible;
                    case "locked" -> locked;
                    default -> throw new AssertionError(n);
                };
            });
        }

        Drawable drawable(String id) {
            return McpParameterStructureDomainTest.proxy(Drawable.class, (n, a) -> {
                guard();
                var m = meshes.get(id);
                return switch (n) {
                    case "id" -> new ArtMeshId(id);
                    case "guid" -> m.guid;
                    case "name" -> m.name;
                    case "parentPartId" -> Optional.of(new PartId("part"));
                    case "parentDeformerId" -> Optional.empty();
                    case "locked" -> false;
                    case "authoringSnapshot" -> m.snapshot;
                    default -> throw new AssertionError(n);
                };
            });
        }

        Object drawables(String n, Object[] args) {
            guard();
            return switch (n) {
                case "all" -> meshes.keySet().stream().map(this::drawable).toList();
                case "previewSourceTriangulation" -> triangulated;
                case "createManyWithSourceGeometry" -> {
                    if (missingBatch) throw new NoSuchMethodError("batch");
                    batches++;
                    var requests = (List<?>) args[0];
                    var ids = new ArrayList<String>();
                    for (Object raw : requests) {
                        var r = (Drawables.SourceGeometryRequest) raw;
                        String id = "new" + (ids.size() + 1);
                        meshes.put(
                                id,
                                mesh(
                                        id,
                                        "guid-" + id,
                                        r.name(),
                                        fault.equals("source")
                                                ? new ArtMeshGeometry(
                                                        DEFAULT, GEOMETRY.uvs(), GEOMETRY.triangleIndices())
                                                : r.sourceGeometry(),
                                        fault.equals("default") ? SOURCE : r.defaultPositions(),
                                        null));
                        ids.add(id);
                        writes++;
                        if (failBatch) throw new IllegalStateException("partial batch");
                    }
                    if (fault.equals("existing")) meshes.remove("old");
                    yield fault.equals("count")
                            ? List.of()
                            : ids.stream().map(this::drawable).toList();
                }
                default -> throw new AssertionError(n);
            };
        }

        Object textures(String n, Object[] args) {
            guard();
            return switch (n) {
                case "modelImageGroups" ->
                    List.of(McpParameterStructureDomainTest.proxy(ModelImageGroup.class, (k, a) -> switch (k) {
                        case "groupName" -> "Images";
                        case "memo" -> "";
                        case "modelImages" ->
                            List.of(McpParameterStructureDomainTest.proxy(
                                    ModelImageEntry.class, (key, v) -> switch (key) {
                                        case "id" -> new ModelImageId("image");
                                        case "name" -> imageName;
                                        case "width", "height" -> 64;
                                        default -> throw new AssertionError(key);
                                    }));
                        default -> throw new AssertionError(k);
                    }));
                case "textureInput" -> {
                    String id = ((ArtMeshId) args[0]).value();
                    String image = meshes.get(id).image;
                    yield image == null
                            ? Optional.empty()
                            : Optional.of(new ArtMeshTextureInput(
                                    new ArtMeshId(id),
                                    new ModelId("model"),
                                    1,
                                    ArtMeshTextureInput.Kind.MODEL_IMAGE,
                                    Optional.of(new ModelImageId(image)),
                                    Optional.empty()));
                }
                case "bindModelImage" -> {
                    String id = ((ArtMeshId) args[1]).value();
                    var old = meshes.get(id);
                    String image = ((ModelImageId) args[0]).value();
                    meshes.put(id, new Mesh(id, old.guid, old.name, old.snapshot, image));
                    binds++;
                    writes++;
                    if (failBind) throw new IllegalStateException("binding failed");
                    yield new ModelImageBinding(
                            "scope",
                            new ModelId("model"),
                            1,
                            new RawImageId("raw"),
                            new ModelImageId(image),
                            Optional.of(new ArtMeshId(id)));
                }
                default -> throw new AssertionError(n);
            };
        }

        @SafeVarargs
        final Map<String, Object> plan(Map<String, Object>... requests) {
            return call(
                    McpMeshCreationDomain.PLAN,
                    Map.of(
                            "documentId",
                            "doc",
                            "modelId",
                            "model",
                            "coordinateSpace",
                            "CANVAS",
                            "meshes",
                            List.of(requests)));
        }

        Map<String, Object> create(String operation, Map<String, Object> p) {
            return call(
                    McpMeshCreationDomain.CREATE,
                    Map.of(
                            "documentId",
                            "doc",
                            "modelId",
                            "model",
                            "planId",
                            p.get("planId"),
                            "operationId",
                            operation));
        }

        Map<String, Object> result(String operation) {
            return call(McpMeshCreationDomain.RESULT, Map.of("operationId", operation));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> call(String name, Map<String, Object> args) {
            return (Map<String, Object>) tools.call(name, args).get("structuredContent");
        }
    }
}
