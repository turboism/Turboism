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
        var mat0 = new ViewerScene.Material(
                "DoubleSidedMat", new float[4], -1, -1, new float[3], "OPAQUE", 0.5f, true, true);
        var mat1 = new ViewerScene.Material(
                "SingleSidedMat", new float[4], -1, -1, new float[3], "OPAQUE", 0.5f, false, true);
        var scene = new ViewerScene(
                List.of(meshes),
                List.of(mat0, mat1),
                List.of(),
                new ViewerScene.Bounds(-10f, -10f, 0f, 10f, 10f, 0f),
                new ViewerScene.Stats(100, meshes.length, meshes.length, 2, 0, 0, 0));
        return NativeCharacterPlan.prepare(scene, request("Character"));
    }

    static final class TestModelHost {
        final List<ParameterDefinition> parameters = new ArrayList<>();
        final Map<ParameterId, Optional<ParameterGroupId>> parameterFolders = new LinkedHashMap<>();
        final Map<ArtMeshId, MeshRecord> meshes = new LinkedHashMap<>();
        final Map<PartId, PartRecord> parts = new LinkedHashMap<>();
        final Map<DeformerId, RotationRecord> rotations = new LinkedHashMap<>();
        final Map<DeformerId, WarpRecord> warps = new LinkedHashMap<>();

        static final class WarpRecord {
            final DeformerId id;
            final String name;
            WarpGrid grid;
            float opacity = 1;
            int instanceGeneration;
            final List<WarpMorphSnapshot.Key> morphKeys = new ArrayList<>();
            Optional<DeformerId> targetDeformer = Optional.empty();

            WarpRecord(DeformerId id, String name) {
                this.id = id;
                this.name = name;
            }
        }

        final Map<ParameterId, Map<ParameterBindingTarget, List<ParameterBindingPoint>>> bindings = new HashMap<>();
        final Map<ParameterId, Map<ParameterBindingTarget, ParameterInterpolation>> interpolations = new HashMap<>();

        boolean simulateLocalParentConversion;
        boolean simulateWarpParentInstanceReplacement;
        int localParentConversions;
        int warpCreationsWithMeshes;
        int partCreationsWithMeshes;
        boolean simulateCompleteParentConversion;
        float nativeCanvasDrift;
        boolean requireDrawOrderBatch;
        boolean sourceBatchActive;
        int hierarchyChangesAfterMeshKeyforms;
        int rotationBindingsAfterSourceMorphs;
        int parameterListReads;

        int partCounter = 0;
        int meshCounter = 0;
        int deformerCounter = 0;

        record PartRecord(PartId id, String name, Part parent) {}

        static final class RotationRecord {
            final DeformerId id;
            final String name;
            final Part parent;
            ParameterId rollAxisId;
            RotationDeformerForm neutral = new RotationDeformerForm(0, 0, 0, 1, false, false);
            float opacity = 1;
            Optional<DeformerId> targetDeformer = Optional.empty();

            RotationRecord(DeformerId id, String name, Part parent) {
                this.id = id;
                this.name = name;
                this.parent = parent;
            }
        }

        static final class MeshRecord {
            final ArtMeshId id;
            final String name;
            final Part parentPart;
            final ArtMeshGeometry geometry;
            final List<Point2> defaultLocal;
            int drawOrder = 500;
            int authoringReads;
            boolean culling = true;
            boolean visible = true;
            Optional<DeformerId> targetDeformer = Optional.empty();

            final LinkedHashMap<ParameterId, List<Float>> boundAxes = new LinkedHashMap<>();
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
                var axisList = new ArrayList<ArtMeshAuthoringSnapshot.Axis>();
                for (var entry : boundAxes.entrySet()) {
                    axisList.add(new ArtMeshAuthoringSnapshot.Axis(
                            "guid-" + entry.getKey().value(), entry.getKey(), -180, 0, 180, entry.getValue()));
                }
                if (axisList.isEmpty()) {
                    return new ArtMeshAuthoringSnapshot(
                            id,
                            "source-" + id.value(),
                            geometry,
                            List.of(),
                            List.of(new ArtMeshAuthoringSnapshot.Cell("default", List.of())),
                            List.of(new ArtMeshAuthoringSnapshot.Form(
                                    "default", keyformPositions.getOrDefault("default", defaultLocal), drawOrder)),
                            List.of());
                }

                ParameterId yawAxisId = axisList.get(0).parameterId();
                ParameterId pitchAxisId = axisList.size() > 1 ? axisList.get(1).parameterId() : yawAxisId;

                var cells = new ArrayList<ArtMeshAuthoringSnapshot.Cell>();
                var forms = new ArrayList<ArtMeshAuthoringSnapshot.Form>();

                for (float y : NativeCharacterPlan.KEYS) {
                    for (float p : NativeCharacterPlan.KEYS) {
                        String guid = id.value() + "-f-" + y + "-" + p;
                        var coords = List.of(
                                new ArtMeshAuthoringSnapshot.Coordinate("guid-" + yawAxisId.value(), y),
                                new ArtMeshAuthoringSnapshot.Coordinate("guid-" + pitchAxisId.value(), p));
                        cells.add(new ArtMeshAuthoringSnapshot.Cell(guid, coords));
                        var pos = keyformPositions.getOrDefault(
                                guid, keyformPositions.getOrDefault("default", defaultLocal));
                        int order = keyformOrders.getOrDefault(guid, drawOrder);
                        forms.add(new ArtMeshAuthoringSnapshot.Form(guid, pos, order));
                    }
                }

                return new ArtMeshAuthoringSnapshot(
                        id, "source-" + id.value(), geometry, axisList, cells, forms, List.of());
            }
        }

        private WarpDeformer warp(WarpRecord r) {
            int generation = r.instanceGeneration;
            return proxy(WarpDeformer.class, (wm, wa) -> {
                if (generation != r.instanceGeneration)
                    throw new IllegalStateException("Deformer reference is stale: " + r.id.value());
                return switch (wm.getName()) {
                    case "id" -> r.id;
                    case "replaceGrid" -> {
                        r.grid = (WarpGrid) wa[0];
                        yield null;
                    }
                    case "grid" -> r.grid;
                    case "setOpacity" -> {
                        r.opacity = (Float) wa[0];
                        yield null;
                    }
                    case "getOpacity" -> r.opacity;
                    case "setTargetDeformer" -> {
                        r.targetDeformer = (Optional<DeformerId>) wa[0];
                        if (simulateWarpParentInstanceReplacement) r.instanceGeneration++;
                        yield null;
                    }
                    case "parentDeformerId" -> r.targetDeformer;
                    case "morphSnapshot" ->
                        new WarpMorphSnapshot(
                                r.id,
                                "source-" + r.id.value(),
                                new WarpMorphSnapshot.Form("base", r.grid.controlPoints(), r.opacity),
                                r.morphKeys);
                    case "createMorphKeyform" -> {
                        r.morphKeys.add(new WarpMorphSnapshot.Key(
                                ((ParameterId) wa[0]).value(),
                                (Float) wa[1],
                                new WarpMorphSnapshot.Form(
                                        "key-" + r.morphKeys.size(), (List<Point2>) wa[2], r.opacity)));
                        yield null;
                    }
                    case "createOpacityMorphKeyform" -> {
                        r.morphKeys.add(new WarpMorphSnapshot.Key(
                                ((ParameterId) wa[0]).value(),
                                (Float) wa[1],
                                new WarpMorphSnapshot.Form(
                                        "key-" + r.morphKeys.size(), r.grid.controlPoints(), (Float) wa[2])));
                        yield null;
                    }
                    default -> throw new AssertionError(wm.toString());
                };
            });
        }

        CubismModel model() {
            return proxy(CubismModel.class, (m, a) -> switch (m.getName()) {
                case "parameters" ->
                    proxy(Parameters.class, (pm, pa) -> switch (pm.getName()) {
                        case "all" -> {
                            parameterListReads++;
                            yield parameters.stream()
                                    .map(p -> proxy(Parameter.class, (m1, a1) -> p.id()))
                                    .toList();
                        }
                        case "find" -> {
                            var definition = parameters.stream()
                                    .filter(p -> p.id().equals(pa[0]))
                                    .findFirst()
                                    .orElseThrow();
                            yield proxy(Parameter.class, (method, args) -> switch (method.getName()) {
                                case "id" -> definition.id();
                                case "type" -> definition.type();
                                case "getValue" -> definition.defaultValue();
                                default -> throw new AssertionError(method.toString());
                            });
                        }
                        case "createMany" -> {
                            var definitions = (List<ParameterDefinition>) pa[0];
                            parameters.addAll(definitions);
                            for (var definition : definitions)
                                parameterFolders.put(
                                        definition.id(),
                                        pa.length == 2 ? (Optional<ParameterGroupId>) pa[1] : Optional.empty());
                            yield List.of();
                        }
                        default -> throw new AssertionError(pm.toString());
                    });
                case "parts" ->
                    proxy(Parts.class, (pm, pa) -> switch (pm.getName()) {
                        case "find" ->
                            proxy(Part.class, (method, args) -> {
                                if (method.getName().equals("id")) return pa[0];
                                throw new AssertionError(method.toString());
                            });
                        case "create" -> {
                            if (!meshes.isEmpty()) partCreationsWithMeshes++;
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
                case "warpDeformers" ->
                    proxy(WarpDeformers.class, (method, args) -> {
                        if (method.getName().equals("find")) return warp(warps.get(args[0]));
                        throw new AssertionError(method.toString());
                    });
                case "deformers" ->
                    proxy(Deformers.class, (dm, da) -> switch (dm.getName()) {
                        case "createWarp" -> {
                            if (!meshes.isEmpty()) warpCreationsWithMeshes++;
                            var id = new DeformerId("warp_" + (++deformerCounter));
                            var r = new WarpRecord(id, (String) da[0]);
                            warps.put(id, r);
                            yield warp(r);
                        }
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
                                case "setOpacity" -> {
                                    r.opacity = (Float) ra[0];
                                    yield null;
                                }
                                case "getOpacity" -> r.opacity;
                                case "replaceForm" -> {
                                    formRef[0] = (RotationDeformerForm) ra[0];
                                    r.neutral = formRef[0];
                                    yield null;
                                }
                                case "authoringSnapshot" -> {
                                    if (keysRef[0] != null && !keysRef[0].isEmpty() && r.name.contains("Z 旋转")) {
                                        ParameterId rollParamId = r.rollAxisId != null
                                                ? r.rollAxisId
                                                : new ParameterId(r.name.replace(" Z 旋转", "_Roll"));
                                        var axis = new RotationAuthoringSnapshot.Axis(
                                                "guid-roll", rollParamId, -180, 0, 180, keysRef[0]);
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
                                    r.targetDeformer = (Optional<DeformerId>) ra[0];
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
                            if (warps.values().stream().anyMatch(w -> !w.morphKeys.isEmpty()))
                                rotationBindingsAfterSourceMorphs++;
                            var axis = (ParameterId) a[0];
                            var target = (ParameterBindingTarget) ba[0];
                            var points = (List<ParameterBindingPoint>) ba[1];
                            bindings.computeIfAbsent(axis, k -> new HashMap<>()).put(target, points);
                            for (var r : rotations.values()) {
                                if (target.equals(ParameterBindingTarget.rotationDeformer(r.id))) {
                                    r.rollAxisId = axis;
                                }
                            }
                            for (var mr : meshes.values()) {
                                if (target.equals(ParameterBindingTarget.artMesh(mr.id))) {
                                    mr.boundAxes.put(
                                            axis,
                                            points.stream()
                                                    .map(ParameterBindingPoint::value)
                                                    .toList());
                                }
                            }
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

        private Point2 toCanvas(Point2 point, Optional<DeformerId> parent) {
            if (parent.isEmpty()) return point;
            var warp = warps.get(parent.get());
            if (warp != null) {
                var a = warp.grid.controlPoints().get(0);
                var b = warp.grid.controlPoints().get(warp.grid.controlPoints().size() - 1);
                return toCanvas(
                        new Point2(a.x() + point.x() * (b.x() - a.x()), a.y() + point.y() * (b.y() - a.y())),
                        warp.targetDeformer);
            }
            var rotation = rotations.get(parent.get());
            return toCanvas(
                    new Point2(point.x() + rotation.neutral.originX(), point.y() + rotation.neutral.originY()),
                    rotation.targetDeformer);
        }

        private Point2 fromCanvas(Point2 point, Optional<DeformerId> parent) {
            if (parent.isEmpty()) return point;
            var warp = warps.get(parent.get());
            if (warp != null) {
                var outer = fromCanvas(point, warp.targetDeformer);
                var a = warp.grid.controlPoints().get(0);
                var b = warp.grid.controlPoints().get(warp.grid.controlPoints().size() - 1);
                return new Point2((outer.x() - a.x()) / (b.x() - a.x()), (outer.y() - a.y()) / (b.y() - a.y()));
            }
            var rotation = rotations.get(parent.get());
            var outer = fromCanvas(point, rotation.targetDeformer);
            return new Point2(outer.x() - rotation.neutral.originX(), outer.y() - rotation.neutral.originY());
        }

        private Drawable createDrawableProxy(MeshRecord r) {
            return proxy(Drawable.class, (m, a) -> switch (m.getName()) {
                case "id" -> r.id;
                case "name" -> r.name;
                case "setCulling" -> {
                    r.culling = (Boolean) a[0];
                    yield null;
                }
                case "setVisible" -> {
                    r.visible = (Boolean) a[0];
                    yield null;
                }
                case "setDrawOrder" -> {
                    if (requireDrawOrderBatch)
                        assertTrue(sourceBatchActive, "Initial draw orders must use the explicit stored-source batch");
                    r.drawOrder = (Integer) a[0];
                    yield null;
                }
                case "setTargetDeformer" -> {
                    if (meshes.values().stream().anyMatch(mesh -> !mesh.boundAxes.isEmpty()))
                        hierarchyChangesAfterMeshKeyforms++;
                    var target = (Optional<DeformerId>) a[0];
                    var warp = target.map(warps::get).orElse(null);
                    if (simulateCompleteParentConversion && warp != null) {
                        var before = r.snapshot();
                        for (var form : before.forms())
                            r.keyformPositions.put(
                                    form.formGuid(),
                                    form.positions().stream()
                                            .map(point -> {
                                                var canvas = toCanvas(point, r.targetDeformer);
                                                return fromCanvas(
                                                        new Point2(canvas.x() + nativeCanvasDrift, canvas.y()), target);
                                            })
                                            .toList());
                        localParentConversions++;
                    } else if (simulateLocalParentConversion && warp != null) {
                        var before = r.snapshot();
                        var origin = warp.grid.controlPoints().get(0);
                        var end = warp.grid
                                .controlPoints()
                                .get(warp.grid.controlPoints().size() - 1);
                        for (var form : before.forms()) {
                            r.keyformPositions.put(
                                    form.formGuid(),
                                    form.positions().stream()
                                            .map(p -> new Point2(
                                                    (p.x() - origin.x()) / (end.x() - origin.x()) + 1e-6f,
                                                    (p.y() - origin.y()) / (end.y() - origin.y()) + 1e-6f))
                                            .toList());
                        }
                        localParentConversions++;
                    }
                    r.targetDeformer = target;
                    yield null;
                }
                case "parentDeformerId" -> r.targetDeformer;
                case "parentPartId" -> Optional.of(r.parentPart.id());
                case "authoringSnapshot" -> {
                    r.authoringReads++;
                    yield r.snapshot();
                }
                case "setOrdinaryKeyformPositions" -> {
                    var edits = (List<ArtMeshKeyformPositions>) a[1];
                    var expected = (ArtMeshAuthoringSnapshot) a[0];
                    assertEquals(r.snapshot(), expected, "Require fresh post-reparent snapshot");
                    for (var edit : edits) {
                        if (edit.coordinates().stream()
                                .allMatch(c -> expected.axes().stream()
                                        .filter(axis -> axis.parameterGuid().equals(c.parameterGuid()))
                                        .allMatch(axis -> axis.defaultValue() == c.value())))
                            throw new UnsupportedOperationException(
                                    "default keyform position editing is not available");
                    }
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
    void sourceGeometryAndMultipleOpacityFactorsReacquireRebuiltWarpParents() {
        exerciseSourceControls(false);
    }

    @Test
    void completeControlHierarchyExistsBeforeImportedArtMeshes() {
        exerciseSourceControls(true);
    }

    @Test
    void directParentConversionRejectsMeasuredCanvasDrift() {
        var failure = assertThrows(IllegalStateException.class, () -> exerciseSourceControls(true, .01f));
        assertTrue(failure.getMessage().contains("canvasError="));
    }

    private void exerciseSourceControls(boolean prebuild) {
        exerciseSourceControls(prebuild, 0);
    }

    private void exerciseSourceControls(boolean prebuild, float canvasDrift) {
        var base = simpleMesh("Arbitrary source", 0);
        var sample = new ViewerScene.ControlSample("shape", 1, new float[] {.01f, 0, 0, .01f, 0, 0, .01f, 0, 0});
        var mesh = new ViewerScene.Mesh(
                base.name(),
                base.positions(),
                base.normals(),
                base.uvs(),
                null,
                null,
                base.indices(),
                0,
                true,
                "Source",
                "Segment",
                List.of(sample),
                List.of(
                        new ViewerScene.OpacityControl("opacity", 1, .5f),
                        new ViewerScene.OpacityControl("opacity2", 1, .25f)));
        var material = new ViewerScene.Material(
                "Flat", new float[] {1, 1, 1, 1}, -1, -1, new float[3], "OPAQUE", .5f, true, true);
        var scene = new ViewerScene(
                List.of(mesh),
                List.of(material),
                List.of(),
                new ViewerScene.Bounds(-10, -10, 0, 10, 10, 0),
                new ViewerScene.Stats(0, 1, 1, 1, 0, 0, 0),
                List.of(
                        new ViewerScene.Control("shape", "Source shape", 0, 1),
                        new ViewerScene.Control("opacity", "Source visibility", 0, 1),
                        new ViewerScene.Control("opacity2", "Second visibility factor", 0, 1)));
        var geometry = NativeCharacterPlan.prepare(scene, request("Generic"));
        var controls = NativeControlWarpPlan.prepare(scene, geometry);
        var host = new TestModelHost();
        var model = host.model();
        host.simulateCompleteParentConversion = prebuild;
        host.nativeCanvasDrift = canvasDrift;
        host.simulateWarpParentInstanceReplacement = prebuild;
        var folder = Optional.of(new ParameterGroupId("ImportedFile"));
        var finishControls = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        var created = NativeCharacterAuthoring.assemble(
                model,
                geometry,
                (id, index) -> assertTrue(
                        host.meshes.get(id).targetDeformer.isEmpty(),
                        "Native texture binding requires the new mesh to remain unparented"),
                index -> new NativeCharacterAuthoring.ImageDimensions(16, 16),
                index -> "Source",
                index -> "Segment",
                geometryCreated -> finishControls.get().run(),
                folder,
                geometryCreated -> {
                    if (prebuild) return;
                    assertTrue(
                            host.interpolations.isEmpty(),
                            "Source control writes must precede every expensive interpolation cache");
                    assertTrue(
                            host.meshes.values().stream()
                                    .allMatch(m -> m.snapshot().forms().size() == 1),
                            "Source hierarchy must be created before expanding rotation banks");
                    host.simulateLocalParentConversion = true;
                    host.simulateWarpParentInstanceReplacement = true;
                    finishControls.set(NativeControlAuthoring.prepare(
                            model,
                            geometryCreated,
                            scene,
                            controls,
                            stage -> {},
                            folder,
                            "Param3D_Generic_unique_Control_"));
                },
                NativeControlAuthoring.rotationLocalizer(controls),
                work -> {
                    if (prebuild && host.meshes.values().stream().allMatch(m -> m.boundAxes.isEmpty()))
                        assertTrue(
                                host.meshes.values().stream().allMatch(m -> m.authoringReads == 1),
                                "Share one post-conversion snapshot between neutral and geometry verification");
                    work.run();
                },
                hierarchy -> {
                    if (!prebuild) return hierarchy.parents();
                    assertTrue(host.meshes.isEmpty(), "Build all source parents before materializing meshes");
                    int parameterReadsBefore = host.parameterListReads;
                    var prepared = NativeControlAuthoring.prepareHierarchy(
                            model, hierarchy, scene, controls, stage -> {}, folder, "Param3D_Generic_unique_Control_");
                    assertEquals(
                            parameterReadsBefore + 1,
                            host.parameterListReads,
                            "Check all source parameter conflicts with a single collection read");
                    finishControls.set(prepared.finish());
                    return prepared.parents();
                },
                prebuild ? NativeControlAuthoring.rotationRestorer(controls) : null);
        if (prebuild) {
            assertEquals(
                    0,
                    host.warpCreationsWithMeshes,
                    "Source Warp creation must not repeatedly evaluate populated character geometry");
            assertEquals(
                    created.meshIds().size(),
                    host.localParentConversions,
                    "Each mesh converts directly into its final parent exactly once");
        }
        assertEquals(
                0,
                host.hierarchyChangesAfterMeshKeyforms,
                "Local controls must not trigger hierarchy conversion of expanded rotation banks");
        assertEquals(
                0,
                host.rotationBindingsAfterSourceMorphs,
                "Dense source morph forms must follow all ordinary rotation bindings");
        assertEquals(host.parameters.size(), host.parameterFolders.size());
        assertTrue(host.parameterFolders.values().stream().allMatch(folder::equals));
        assertTrue(host.parameters.stream()
                .filter(p -> p.type() == ParameterType.BLEND_SHAPE)
                .allMatch(p -> p.id().value().startsWith("Param3D_Generic_unique_Control_")));
        assertEquals(2, host.interpolations.size());
        for (var axis : List.of(geometry.request().yaw(), geometry.request().pitch()))
            assertEquals(created.meshIds().size(), host.interpolations.get(axis).size());
        assertTrue(host.localParentConversions > 0, "Exercise native eager coordinate conversion");
        assertEquals(
                3,
                host.parameters.stream()
                        .filter(p -> p.type() == ParameterType.BLEND_SHAPE)
                        .count());
        assertTrue(host.parameters.stream()
                .filter(p -> p.type() == ParameterType.BLEND_SHAPE)
                .allMatch(p -> p.defaultValue() == 0));
        assertEquals(
                3,
                host.warps.values().stream().mapToInt(w -> w.morphKeys.size()).sum());
        assertEquals(
                25, host.meshes.values().iterator().next().snapshot().forms().size());
        var opacityForms = host.warps.values().stream()
                .flatMap(w -> w.morphKeys.stream())
                .map(WarpMorphSnapshot.Key::form)
                .map(WarpMorphSnapshot.Form::opacity)
                .sorted()
                .toList();
        assertEquals(List.of(.25f, .5f, 1f), opacityForms);
        var local = host.warps.get(
                host.meshes.values().iterator().next().targetDeformer.orElseThrow());
        var secondFactor = host.warps.get(local.targetDeformer.orElseThrow());
        var firstFactor = host.warps.get(secondFactor.targetDeformer.orElseThrow());
        assertEquals(.25f, secondFactor.morphKeys.get(0).form().opacity());
        assertEquals(.5f, firstFactor.morphKeys.get(0).form().opacity());
        // Every non-neutral rotation must be authored in the new local Warp's space.
        var camera = geometry.camera();
        float ox = (float) (camera.centerX() + camera.scale() * camera.pivot().x());
        float oy = (float) (camera.centerY() - camera.scale() * camera.pivot().y());
        var partition = geometry.partitions().get(0);
        var frame = new NativeCharacterAuthoring.Frame();
        var neutral = SurfaceProjection.project(partition.positions(), camera, new SurfaceProjection.Pose(0, 0));
        for (var point : neutral.positions()) frame.include(point.x() - ox, point.y() - oy);
        frame.pad();
        var stored = host.meshes.values().iterator().next().snapshot();
        for (var cell : stored.cells()) {
            float yaw = cell.coordinates().get(0).value();
            float pitch = cell.coordinates().get(1).value();
            if (yaw == 0 && pitch == 0 && !prebuild) continue;
            var projected =
                    SurfaceProjection.project(partition.positions(), camera, new SurfaceProjection.Pose(yaw, pitch));
            var bodyLocal = frame.normalize(projected.positions().stream()
                    .map(point -> new Point2(point.x() - ox, point.y() - oy))
                    .toList());
            var expected = NativeControlAuthoring.rotationLocalizer(controls).apply(0, bodyLocal);
            var actual = stored.forms().stream()
                    .filter(form -> form.formGuid().equals(cell.formGuid()))
                    .findFirst()
                    .orElseThrow()
                    .positions();
            for (int i = 0; i < expected.size(); i++) {
                assertEquals(expected.get(i).x(), actual.get(i).x(), 1e-5);
                assertEquals(expected.get(i).y(), actual.get(i).y(), 1e-5);
            }
        }
    }

    @Test
    void initialDrawOrdersUseExplicitSourceBatchBeforeRotationBanks() {
        var host = new TestModelHost();
        host.requireDrawOrderBatch = true;
        var batches = new int[1];
        var geometry = planFor(simpleMesh("Left", 0), simpleMesh("Right", 1));
        NativeCharacterAuthoring.assemble(
                host.model(),
                geometry,
                (id, source) -> {},
                source -> new NativeCharacterAuthoring.ImageDimensions(16, 16),
                source -> "Group",
                source -> "Body",
                created -> {},
                Optional.empty(),
                created -> {},
                (partition, points) -> points,
                work -> {
                    assertFalse(host.sourceBatchActive);
                    host.sourceBatchActive = true;
                    try {
                        work.run();
                    } finally {
                        host.sourceBatchActive = false;
                    }
                    batches[0]++;
                });
        assertEquals(2, batches[0], "Initial scalar orders and rotation banks use separate evaluation barriers");
    }

    @Test
    void incompleteVisibilityPlanIsRejectedBeforeCreatingNativeObjects() {
        var host = new TestModelHost();
        var p = planFor(simpleMesh("Source", 0));
        var unfinished = new NativeCharacterPlan.Plan(
                p.request(),
                p.camera(),
                p.keys(),
                p.partitions(),
                List.of(),
                p.sampling(),
                p.visibilityLimit(),
                p.totalSourceSlots(),
                p.totalPartitionSlots(),
                p.totalTriangles(),
                p.totalKeyformSlots(),
                p.totalCacheSlots(),
                p.maxInterpolationErrorPixels());
        assertThrows(
                IllegalArgumentException.class,
                () -> NativeCharacterAuthoring.assemble(host.model(), unfinished, (id, source) -> {}));
        assertTrue(host.parts.isEmpty());
        assertTrue(host.meshes.isEmpty());
        assertTrue(host.warps.isEmpty());
    }

    @Test
    void anatomicalHierarchyIsCompleteBeforeExpandingAnyArtMeshTo25Forms() {
        var host = new TestModelHost();
        var plan = planFor(simpleMesh("Left", 0), simpleMesh("Right", 1));
        NativeCharacterAuthoring.assemble(host.model(), plan, (id, source) -> {});
        assertEquals(
                0, host.warpCreationsWithMeshes, "Anatomical Warp parents must be created before importing ArtMeshes");
        assertEquals(0, host.partCreationsWithMeshes, "Create partition Parts before populated geometry is evaluated");
        assertEquals(
                0,
                host.hierarchyChangesAfterMeshKeyforms,
                "Native parent changes must not repeatedly rebuild earlier meshes' 25-form banks");
        assertTrue(host.meshes.values().stream()
                .allMatch(mesh -> mesh.snapshot().forms().size() == 25));
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

        NativeCharacterAuthoring.ImageDimensionProvider dimProvider = (srcIdx) -> srcIdx == 0
                ? new NativeCharacterAuthoring.ImageDimensions(2048, 1024)
                : new NativeCharacterAuthoring.ImageDimensions(512, 512);

        var created = NativeCharacterAuthoring.assemble(host.model(), plan, binder, dimProvider);

        assertNotNull(created);
        assertNotNull(created.rootPartId());
        assertNotNull(created.rollDeformerId());
        assertEquals(2, created.meshIds().size());

        // Verify binder callbacks
        assertEquals(2, boundMeshes.size());
        assertEquals(List.of(0, 1), boundSourceIndices);
        assertEquals(created.meshIds(), boundMeshes);

        // Verify ArtMesh 0 (double-sided, 2048x1024 texture dimensions mapped)
        var r0 = host.meshes.get(created.meshIds().get(0));
        assertNotNull(r0);
        assertFalse(r0.culling, "Double-sided material must have culling false");
        assertEquals(new Point2(0f, 0f), r0.geometry.positions().get(0));
        assertEquals(new Point2(2048f, 0f), r0.geometry.positions().get(1));
        assertEquals(new Point2(1024f, 1024f), r0.geometry.positions().get(2));

        // Verify ArtMesh 1 (single-sided, 512x512 texture dimensions mapped)
        var r1 = host.meshes.get(created.meshIds().get(1));
        assertNotNull(r1);
        assertTrue(r1.culling, "Single-sided material must have culling true");
        assertEquals(new Point2(512f, 0f), r1.geometry.positions().get(1));

        // Verify ArtMeshes properties
        for (var meshId : created.meshIds()) {
            var r = host.meshes.get(meshId);
            assertNotNull(r);
            var opacityParent = host.warps.get(r.targetDeformer.orElseThrow());
            assertNotNull(opacityParent);
            assertEquals(1f, opacityParent.opacity);
            assertEquals(Optional.of(created.rollDeformerId()), opacityParent.targetDeformer);
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
    void partitionsFromOneOutfitShareAnOpacityParent() {
        var host = new TestModelHost();
        var plan = planFor(simpleMesh("SleeveA", 0), simpleMesh("SleeveB", 1), simpleMesh("Body", 0));
        var created = NativeCharacterAuthoring.assemble(
                host.model(),
                plan,
                (m, s) -> {},
                idx -> new NativeCharacterAuthoring.ImageDimensions(16, 16),
                idx -> idx < 2 ? "Sleeves" : "Body");
        var first = host.meshes.get(created.meshIds().get(0)).targetDeformer;
        assertEquals(first, host.meshes.get(created.meshIds().get(1)).targetDeformer);
        assertNotEquals(first, host.meshes.get(created.meshIds().get(2)).targetDeformer);
        assertEquals("Sleeves", host.warps.get(first.orElseThrow()).name);
        assertEquals(Optional.of(created.rollDeformerId()), host.warps.get(first.orElseThrow()).targetDeformer);
        assertEquals(3, host.parameters.size(), "Visibility grouping must not multiply the XYZ grid");
    }

    @Test
    void bodyPartWarpContainsAllRelatedOutfitMeshes() {
        var host = new TestModelHost();
        var plan = planFor(simpleMesh("Skin", 0), simpleMesh("Shoe", 1), simpleMesh("OtherLeg", 0));
        var created = NativeCharacterAuthoring.assemble(
                host.model(),
                plan,
                (m, s) -> {},
                idx -> new NativeCharacterAuthoring.ImageDimensions(16, 16),
                idx -> idx == 1 ? "Shoes" : "Skin",
                idx -> idx < 2 ? "Left leg" : "Right leg");
        var parents = created.meshIds().stream()
                .map(id -> host.warps.get(host.meshes.get(id).targetDeformer.orElseThrow()))
                .toList();
        assertNotEquals(parents.get(0).id, parents.get(1).id, "Skin and shoe retain separate opacity parents");
        assertEquals(parents.get(0).targetDeformer, parents.get(1).targetDeformer, "Both belong to the left-leg Warp");
        assertNotEquals(parents.get(0).targetDeformer, parents.get(2).targetDeformer);
        var leg = host.warps.get(parents.get(0).targetDeformer.orElseThrow());
        assertEquals("Left leg", leg.name);
        assertEquals(9, leg.grid.controlPoints().size());
        assertEquals(Optional.of(created.rollDeformerId()), leg.targetDeformer);
        assertEquals(3, host.parameters.size());
        for (var mesh : host.meshes.values())
            assertEquals(25, mesh.snapshot().forms().size());
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
                validPlan.totalCacheSlots(),
                validPlan.maxInterpolationErrorPixels());

        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> NativeCharacterAuthoring.assemble(host.model(), illegalPlan, (m, s) -> {}));
        assertTrue(ex.getMessage().contains("6M save budget"));
    }

    @Test
    void assembleRejectsPlanExceedingCacheSlotBudget() {
        var host = new TestModelHost();
        var validPlan = planFor(simpleMesh("Body", 0));

        // Create an illegal plan with totalCacheSlots > 64,000,000
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
                validPlan.totalKeyformSlots(),
                70_000_000L, // Exceeds 64M cache budget
                validPlan.maxInterpolationErrorPixels());

        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> NativeCharacterAuthoring.assemble(host.model(), illegalPlan, (m, s) -> {}));
        assertTrue(ex.getMessage().contains("64M cache budget"));
    }

    @Test
    void hiddenExtremeHelperDoesNotDistortFramingAndRetainsTriangles() {
        // Main visible character mesh centered at (0, 0, 0) with radius ~10
        float[] posMain = {-10f, -10f, 0f, 10f, -10f, 0f, 0f, 10f, 0f};
        float[] uvs = {0f, 0f, 1f, 0f, 0.5f, 1f};
        int[] ind = {0, 1, 2};
        var mainMesh = new ViewerScene.Mesh("BodyMesh", posMain, new float[9], uvs, null, null, ind, 0, true);

        // Extreme invisible helper/collider mesh far away at (5000, 5000, 5000)
        float[] posHelper = {4990f, 4990f, 5000f, 5010f, 4990f, 5000f, 5000f, 5010f, 5000f};
        var helperMesh =
                new ViewerScene.Mesh("HelperCollider", posHelper, new float[9], uvs, null, null, ind, -1, false);

        var mat0 = new ViewerScene.Material("BodyMat", new float[4], -1, -1, new float[3], "OPAQUE", 0.5f, true, true);
        var scene = new ViewerScene(
                List.of(mainMesh, helperMesh),
                List.of(mat0),
                List.of(),
                new ViewerScene.Bounds(-10f, -10f, 0f, 5010f, 5010f, 5000f),
                new ViewerScene.Stats(100, 2, 2, 1, 0, 0, 0));

        var plan = NativeCharacterPlan.prepare(scene, request("FramingCheck"));

        // 1. Camera framing must be centered around (0, 0, 0), NOT distorted by (5000, 5000, 5000)
        assertEquals(0.0, plan.camera().pivot().x(), 1e-4);
        assertEquals(0.0, plan.camera().pivot().y(), 1e-4);
        assertEquals(0.0, plan.camera().pivot().z(), 1e-4);

        // Scale is 400.0 / ~10.0 = ~40.0, NOT 400.0 / ~8600.0 = ~0.046
        assertTrue(plan.camera().scale() > 20.0, "Scale must frame the visible body mesh, not extreme helper");

        // 2. Both partitions must be preserved in the plan (zero dropped triangles)
        assertEquals(2, plan.partitions().size());
        assertEquals(2, plan.totalTriangles());
        assertEquals(6, plan.totalSourceSlots());

        var mainPart = plan.partitions().get(0);
        var helperPart = plan.partitions().get(1);
        assertTrue(mainPart.visible(), "Main partition must be visible");
        assertFalse(helperPart.visible(), "Helper partition must be hidden");

        // 3. Assemble and verify ArtMesh visibility setting
        var host = new TestModelHost();
        var created = NativeCharacterAuthoring.assemble(host.model(), plan, (m, s) -> {});

        var mainDrawable = host.meshes.get(created.meshIds().get(0));
        var helperDrawable = host.meshes.get(created.meshIds().get(1));

        assertTrue(mainDrawable.visible, "Main ArtMesh must be set visible true");
        assertFalse(helperDrawable.visible, "Helper ArtMesh must be set visible false");
    }
}
