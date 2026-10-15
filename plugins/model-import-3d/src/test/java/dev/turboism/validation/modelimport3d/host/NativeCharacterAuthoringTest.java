package dev.turboism.validation.modelimport3d.host;

import static dev.turboism.validation.modelimport3d.host.SurfaceWarpAuthoringTest.proxy;
import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.plugin.modelimport3d.viewer.ViewerScene;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

public class NativeCharacterAuthoringTest {

    private static SurfaceImport.Request request(String name) {
        return new SurfaceImport.Request(
                name,
                new ParameterId(name + "_Yaw"),
                new ParameterId(name + "_Pitch"),
                new SurfaceProjection.Camera(512, 512, 400.0, new SurfaceProjection.Vertex(0, 0, 0)),
                180,
                180,
                20_000_000,
                Optional.of(new ParameterId(name + "_Roll")));
    }

    private static ViewerScene.Mesh simpleMesh(String name, int matIdx) {
        float[] pos = {-10f, -10f, 0f, 10f, -10f, 0f, 0f, 10f, 0f};
        float[] uvs = {0f, 0f, 1f, 0f, 0.5f, 1f};
        int[] ind = {0, 1, 2};
        return new ViewerScene.Mesh(name, pos, new float[9], uvs, null, null, ind, matIdx);
    }

    private static NativeCharacterPlan.Plan planFor(ViewerScene.Mesh... meshes) {
        var scene = new ViewerScene(
                List.of(meshes),
                List.of(),
                List.of(),
                new ViewerScene.Bounds(-10f, -10f, 0f, 10f, 10f, 0f),
                new ViewerScene.Stats(100, meshes.length, meshes.length, 1, 0, 0, 0));
        return NativeCharacterPlan.prepare(scene, request("Character"));
    }

    static final class TestModelHost {
        final List<ParameterDefinition> parameters = new ArrayList<>();
        final Map<ArtMeshId, MeshRecord> meshes = new LinkedHashMap<>();
        final Map<PartId, PartRecord> parts = new LinkedHashMap<>();
        final Map<DeformerId, RotationRecord> rotations = new LinkedHashMap<>();
        final Map<ParameterId, Map<ParameterBindingTarget, List<ParameterBindingPoint>>> bindings = new HashMap<>();
        final Map<ParameterId, Map<ParameterBindingTarget, ParameterInterpolation>> interpolations = new HashMap<>();

        int partCounter = 0;
        int meshCounter = 0;
        int deformerCounter = 0;

        record PartRecord(PartId id, String name, Part parent) {}

        record RotationRecord(DeformerId id, String name, Part parent) {
            static RotationAuthoringSnapshot currentSnapshot(
                    RotationRecord r, List<Float> keys, RotationDeformerForm form) {
                var axis = new RotationAuthoringSnapshot.Axis(
                        "guid-roll", new ParameterId("Character_Roll"), -180, 0, 180, keys);
                var cells = new ArrayList<RotationAuthoringSnapshot.Cell>();
                var forms = new ArrayList<RotationAuthoringSnapshot.Form>();
                for (int i = 0; i < keys.size(); i++) {
                    String guid = "roll-f" + i;
                    cells.add(new RotationAuthoringSnapshot.Cell(
                            guid, List.of(new RotationAuthoringSnapshot.Coordinate("guid-roll", keys.get(i)))));
                    forms.add(new RotationAuthoringSnapshot.Form(guid, form));
                }
                return new RotationAuthoringSnapshot(r.id, "source-" + r.id.value(), 0, List.of(axis), cells, forms);
            }
        }

        static final class MeshRecord {
            final ArtMeshId id;
            final String name;
            final Part parentPart;
            final ArtMeshGeometry geometry;
            final List<Point2> defaultLocal;
            int drawOrder = 500;
            boolean culling = true;
            Optional<DeformerId> targetDeformer = Optional.empty();

            final List<ArtMeshKeyformPositions> positionEdits = new ArrayList<>();
            final List<ArtMeshKeyformDrawOrder> orderEdits = new ArrayList<>();
            final Map<String, List<Point2>> keyformPositions = new HashMap<>();
            final Map<String, Integer> keyformOrders = new HashMap<>();

            MeshRecord(
                    ArtMeshId id, String name, Part parentPart, ArtMeshGeometry geometry, List<Point2> defaultLocal) {
                this.id = id;
                this.name = name;
                this.parentPart = parentPart;
                this.geometry = geometry;
                this.defaultLocal = List.copyOf(defaultLocal);
            }

            ArtMeshAuthoringSnapshot snapshot() {
                var yawAxis = new ArtMeshAuthoringSnapshot.Axis(
                        "guid-yaw", new ParameterId("Character_Yaw"), -180, 0, 180, NativeCharacterPlan.KEYS);
                var pitchAxis = new ArtMeshAuthoringSnapshot.Axis(
                        "guid-pitch", new ParameterId("Character_Pitch"), -180, 0, 180, NativeCharacterPlan.KEYS);
                var cells = new ArrayList<ArtMeshAuthoringSnapshot.Cell>();
                var forms = new ArrayList<ArtMeshAuthoringSnapshot.Form>();

                for (float y : NativeCharacterPlan.KEYS) {
                    for (float p : NativeCharacterPlan.KEYS) {
                        String guid = id.value() + "-f-" + y + "-" + p;
                        var coords = List.of(
                                new ArtMeshAuthoringSnapshot.Coordinate("guid-yaw", y),
                                new ArtMeshAuthoringSnapshot.Coordinate("guid-pitch", p));
                        cells.add(new ArtMeshAuthoringSnapshot.Cell(guid, coords));
                        var pos = keyformPositions.getOrDefault(guid, defaultLocal);
                        int order = keyformOrders.getOrDefault(guid, drawOrder);
                        forms.add(new ArtMeshAuthoringSnapshot.Form(guid, pos, order));
                    }
                }

                return new ArtMeshAuthoringSnapshot(
                        id, "source-" + id.value(), geometry, List.of(yawAxis, pitchAxis), cells, forms, List.of());
            }
        }

        CubismModel model() {
            return proxy(CubismModel.class, (m, a) -> switch (m.getName()) {
                case "parameters" ->
                    proxy(Parameters.class, (pm, pa) -> switch (pm.getName()) {
                        case "all" ->
                            parameters.stream()
                                    .map(p -> proxy(Parameter.class, (m1, a1) -> p.id()))
                                    .toList();
                        case "createMany" -> {
                            parameters.addAll((List<ParameterDefinition>) pa[0]);
                            yield List.of();
                        }
                        default -> throw new AssertionError(pm.toString());
                    });
                case "parts" ->
                    proxy(Parts.class, (pm, pa) -> switch (pm.getName()) {
                        case "create" -> {
                            var id = new PartId("part_" + (++partCounter));
                            var r = new PartRecord(id, (String) pa[0], (Part) pa[1]);
                            parts.put(id, r);
                            yield proxy(Part.class, (m1, a1) -> switch (m1.getName()) {
                                case "id" -> id;
                                case "name" -> r.name;
                                default -> throw new AssertionError(m1.toString());
                            });
                        }
                        default -> throw new AssertionError(pm.toString());
                    });
                case "drawables" ->
                    proxy(Drawables.class, (dm, da) -> switch (dm.getName()) {
                        case "createWithSourceGeometry" -> {
                            var id = new ArtMeshId("mesh_" + (++meshCounter));
                            var r = new MeshRecord(
                                    id, (String) da[0], (Part) da[1], (ArtMeshGeometry) da[3], (List<Point2>) da[4]);
                            meshes.put(id, r);
                            yield createDrawableProxy(r);
                        }
                        case "find" -> {
                            var id = (ArtMeshId) da[0];
                            var r = meshes.get(id);
                            if (r == null) throw new IllegalArgumentException("Drawable not found: " + id);
                            yield createDrawableProxy(r);
                        }
                        default -> throw new AssertionError(dm.toString());
                    });
                case "deformers" ->
                    proxy(Deformers.class, (dm, da) -> switch (dm.getName()) {
                        case "createRotation" -> {
                            var id = new DeformerId("rot_" + (++deformerCounter));
                            var r = new RotationRecord(id, (String) da[0], (Part) da[1]);
                            rotations.put(id, r);
                            var formRef = new RotationDeformerForm[1];
                            formRef[0] = new RotationDeformerForm(0, 0, 0, 1, false, false);
                            var parentDeformerRef = new Optional[1];
                            parentDeformerRef[0] = Optional.empty();
                            var keysRef = new List[1];
                            keysRef[0] = SurfaceRotationAuthoring.KEYS;
                            var values = new HashMap<String, RotationDeformerForm>();

                            yield proxy(RotationDeformer.class, (rm, ra) -> switch (rm.getName()) {
                                case "id" -> id;
                                case "replaceForm" -> {
                                    formRef[0] = (RotationDeformerForm) ra[0];
                                    yield null;
                                }
                                case "authoringSnapshot" -> {
                                    if (keysRef[0] != null && !keysRef[0].isEmpty() && r.name.contains("Z 旋转")) {
                                        var axis = new RotationAuthoringSnapshot.Axis(
                                                "guid-roll",
                                                new ParameterId("Character_Roll"),
                                                -180,
                                                0,
                                                180,
                                                keysRef[0]);
                                        var cells = new ArrayList<RotationAuthoringSnapshot.Cell>();
                                        var forms = new ArrayList<RotationAuthoringSnapshot.Form>();
                                        for (int i = 0; i < keysRef[0].size(); i++) {
                                            String guid = "roll-f" + i;
                                            cells.add(new RotationAuthoringSnapshot.Cell(
                                                    guid,
                                                    List.of(new RotationAuthoringSnapshot.Coordinate(
                                                            "guid-roll", (Float) keysRef[0].get(i)))));
                                            forms.add(new RotationAuthoringSnapshot.Form(
                                                    guid, values.getOrDefault(guid, formRef[0])));
                                        }
                                        yield new RotationAuthoringSnapshot(
                                                r.id, "source-" + r.id.value(), 0, List.of(axis), cells, forms);
                                    }
                                    var form = formRef[0];
                                    yield new RotationAuthoringSnapshot(
                                            id,
                                            "source-" + id.value(),
                                            0,
                                            List.of(),
                                            List.of(new RotationAuthoringSnapshot.Cell("f0", List.of())),
                                            List.of(new RotationAuthoringSnapshot.Form("f0", form)));
                                }
                                case "setOrdinaryKeyformForms" -> {
                                    var edits = (List<RotationKeyformForm>) ra[1];
                                    for (var edit : edits) values.put(edit.formGuid(), edit.value());
                                    yield null;
                                }
                                case "parentDeformerId" -> parentDeformerRef[0];
                                case "setTargetDeformer" -> {
                                    parentDeformerRef[0] = (Optional<DeformerId>) ra[0];
                                    yield null;
                                }
                                default -> throw new AssertionError(rm.toString());
                            });
                        }
                        default -> throw new AssertionError(dm.toString());
                    });
                case "parameterBindings" ->
                    proxy(ParameterBindingOperations.class, (bm, ba) -> switch (bm.getName()) {
                        case "bind" -> {
                            var axis = (ParameterId) a[0];
                            var target = (ParameterBindingTarget) ba[0];
                            var points = (List<ParameterBindingPoint>) ba[1];
                            bindings.computeIfAbsent(axis, k -> new HashMap<>()).put(target, points);
                            yield null;
                        }
                        case "setInterpolation" -> {
                            var axis = (ParameterId) a[0];
                            var target = (ParameterBindingTarget) ba[0];
                            var interp = (ParameterInterpolation) ba[1];
                            interpolations
                                    .computeIfAbsent(axis, k -> new HashMap<>())
                                    .put(target, interp);
                            yield null;
                        }
                        default -> throw new AssertionError(bm.toString());
                    });
                default -> throw new AssertionError(m.toString());
            });
        }

        private Drawable createDrawableProxy(MeshRecord r) {
            return proxy(Drawable.class, (m, a) -> switch (m.getName()) {
                case "id" -> r.id;
                case "name" -> r.name;
                case "setCulling" -> {
                    r.culling = (Boolean) a[0];
                    yield null;
                }
                case "setDrawOrder" -> {
                    r.drawOrder = (Integer) a[0];
                    yield null;
                }
                case "setTargetDeformer" -> {
                    r.targetDeformer = (Optional<DeformerId>) a[0];
                    yield null;
                }
                case "parentPartId" -> Optional.of(r.parentPart.id());
                case "authoringSnapshot" -> r.snapshot();
                case "setOrdinaryKeyformPositions" -> {
                    var edits = (List<ArtMeshKeyformPositions>) a[1];
                    r.positionEdits.addAll(edits);
                    for (var e : edits) r.keyformPositions.put(e.formGuid(), e.positions());
                    yield null;
                }
                case "setOrdinaryKeyformDrawOrders" -> {
                    var edits = (List<ArtMeshKeyformDrawOrder>) a[1];
                    r.orderEdits.addAll(edits);
                    for (var e : edits) r.keyformOrders.put(e.formGuid(), e.drawOrder());
                    yield null;
                }
                default -> throw new AssertionError(m.toString());
            });
        }
    }

    @Test
    void assembleCreatesValidArtMeshesWithExactSourceGeometryAnd25Keyforms() {
        var host = new TestModelHost();
        var plan = planFor(simpleMesh("Hair", 0), simpleMesh("Face", 1));

        var boundMeshes = new ArrayList<ArtMeshId>();
        var boundSourceIndices = new ArrayList<Integer>();

        NativeCharacterAuthoring.MeshMaterialBinder binder = (meshId, srcIdx) -> {
            boundMeshes.add(meshId);
            boundSourceIndices.add(srcIdx);
        };

        var created = NativeCharacterAuthoring.assemble(host.model(), plan, binder);

        assertNotNull(created);
        assertNotNull(created.rootPartId());
        assertNotNull(created.rollDeformerId());
        assertEquals(2, created.meshIds().size());

        // Verify binder callbacks
        assertEquals(2, boundMeshes.size());
        assertEquals(List.of(0, 1), boundSourceIndices);
        assertEquals(created.meshIds(), boundMeshes);

        // Verify ArtMeshes properties
        for (var meshId : created.meshIds()) {
            var r = host.meshes.get(meshId);
            assertNotNull(r);
            assertFalse(r.culling, "GLB primitive must be double-sided (culling false)");
            assertEquals(
                    Optional.of(created.rollDeformerId()), r.targetDeformer, "Target deformer must be Roll Deformer");
            assertEquals(24, r.positionEdits.size(), "24 non-neutral keyform positions edited");
            assertEquals(24, r.orderEdits.size(), "24 non-neutral keyform orders edited");

            // Snapshot readback
            var snap = r.snapshot();
            assertEquals(25, snap.forms().size(), "Must have exactly 25 forms");
            assertEquals(25, snap.cells().size(), "Must have exactly 25 cells");
            assertEquals(2, snap.axes().size(), "Must have exactly 2 axes (Yaw, Pitch)");
        }

        // Verify parameter registrations
        assertTrue(host.parameters.stream().anyMatch(p -> p.id().equals(new ParameterId("Character_Yaw"))));
        assertTrue(host.parameters.stream().anyMatch(p -> p.id().equals(new ParameterId("Character_Pitch"))));
        assertTrue(host.parameters.stream().anyMatch(p -> p.id().equals(new ParameterId("Character_Roll"))));
    }

    @Test
    void assembleRejectsConflictingParameterIds() {
        var host = new TestModelHost();
        host.parameters.add(new ParameterDefinition(
                new ParameterId("Character_Yaw"), "Existing", -180, 0, 180, ParameterType.NORMAL, false));

        var plan = planFor(simpleMesh("Body", 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> NativeCharacterAuthoring.assemble(host.model(), plan, (m, s) -> {}));
    }

    @Test
    void assembleRejectsPlanExceedingKeyformSlotBudget() {
        var host = new TestModelHost();
        var validPlan = planFor(simpleMesh("Body", 0));

        // Create an illegal plan with totalKeyformSlots > 6,000,000
        var illegalPlan = new NativeCharacterPlan.Plan(
                validPlan.request(),
                validPlan.camera(),
                validPlan.keys(),
                validPlan.partitions(),
                validPlan.orders(),
                validPlan.sampling(),
                validPlan.visibilityLimit(),
                validPlan.totalSourceSlots(),
                validPlan.totalPartitionSlots(),
                validPlan.totalTriangles(),
                7_000_000L, // Exceeds 6M budget
                validPlan.maxInterpolationErrorPixels());

        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> NativeCharacterAuthoring.assemble(host.model(), illegalPlan, (m, s) -> {}));
        assertTrue(ex.getMessage().contains("6M save budget"));
    }
}
