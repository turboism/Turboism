package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.*;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.*;
import dev.turboism.sdk.ui.*;
import java.io.InputStream;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ModelTextureAtlasCallsiteRegressionTest {

    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    static void edt(Runnable r) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeAndWait(r);
        }
    }

    static final class MockTextureAtlas implements AtlasTexture {
        final TextureAtlasId id;
        final String name;
        final int width;
        final int height;

        MockTextureAtlas(TextureAtlasId id, String name, int width, int height) {
            this.id = id;
            this.name = name;
            this.width = width;
            this.height = height;
        }

        @Override
        public TextureAtlasId id() {
            return id;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public int atlasVersion() {
            return 1;
        }

        @Override
        public int modelImageCount() {
            return 1;
        }
    }

    static final class TestWorld implements ModelImportPlugin.Ui {
        final ModelId modelId = new ModelId("test-model");
        final List<MockTextureAtlas> atlases = new ArrayList<>();
        final Map<ArtMeshId, ArtMeshTextureInput> meshInputs = new HashMap<>();
        final List<ModelImageBinding> registeredBindings = new ArrayList<>();
        final List<ParameterDefinition> parameters = new ArrayList<>();
        final List<ArtMeshId> drawables = new ArrayList<>();
        final Map<ArtMeshId, ArtMeshSourceTriangulation> geometries = new HashMap<>();
        final Map<ArtMeshId, Integer> meshDrawOrders = new HashMap<>();
        final Map<ArtMeshId, List<Point2>> defaultLocals = new HashMap<>();
        final Map<ArtMeshId, ArtMeshGeometry> sourceGeometries = new HashMap<>();
        final Map<ArtMeshId, ArtMeshAuthoringSnapshot> snapshots = new HashMap<>();
        final Map<ArtMeshId, List<ParameterBindingPoint>> meshYawPoints = new HashMap<>();
        final Map<ArtMeshId, List<ParameterBindingPoint>> meshPitchPoints = new HashMap<>();

        final Map<DeformerId, WarpGrid> warpGrids = new HashMap<>();
        final Map<DeformerId, WarpAuthoringSnapshot> warpSnapshots = new HashMap<>();
        final Map<DeformerId, List<ParameterBindingPoint>> warpYawPoints = new HashMap<>();
        final Map<DeformerId, List<ParameterBindingPoint>> warpPitchPoints = new HashMap<>();

        void updateWarpSnapshot(DeformerId warpId) {
            var grid = warpGrids.get(warpId);
            if (grid == null) return;
            var yawList = warpYawPoints.get(warpId);
            var pitchList = warpPitchPoints.get(warpId);
            var axisList = new ArrayList<WarpAuthoringSnapshot.Axis>();
            var ys = yawList != null
                    ? yawList.stream().map(ParameterBindingPoint::value).toList()
                    : List.of(0f);
            var ps = pitchList != null
                    ? pitchList.stream().map(ParameterBindingPoint::value).toList()
                    : List.of(0f);
            if (yawList != null) {
                var yawDef = parameters.stream()
                        .filter(p -> p.id().value().contains("Yaw"))
                        .findFirst()
                        .orElseThrow();
                axisList.add(new WarpAuthoringSnapshot.Axis("guid-Yaw", yawDef.id(), -180, 0, 180, ys));
            }
            if (pitchList != null) {
                var pitchDef = parameters.stream()
                        .filter(p -> p.id().value().contains("Pitch"))
                        .findFirst()
                        .orElseThrow();
                axisList.add(new WarpAuthoringSnapshot.Axis("guid-Pitch", pitchDef.id(), -180, 0, 180, ps));
            }
            var cells = new ArrayList<WarpAuthoringSnapshot.Cell>();
            var forms = new ArrayList<WarpAuthoringSnapshot.Form>();
            for (float y : ys) {
                for (float p : ps) {
                    var coords = new ArrayList<WarpAuthoringSnapshot.Coordinate>();
                    if (yawList != null) coords.add(new WarpAuthoringSnapshot.Coordinate("guid-Yaw", y));
                    if (pitchList != null) coords.add(new WarpAuthoringSnapshot.Coordinate("guid-Pitch", p));
                    String guid = "form-" + y + "-" + p;
                    cells.add(new WarpAuthoringSnapshot.Cell(guid, coords));
                    forms.add(new WarpAuthoringSnapshot.Form(guid, grid.controlPoints()));
                }
            }
            warpSnapshots.put(
                    warpId,
                    new WarpAuthoringSnapshot(
                            warpId,
                            "warp-source",
                            grid.rows(),
                            grid.columns(),
                            grid.quadTransform(),
                            axisList,
                            cells,
                            forms));
        }

        void updateMeshSnapshot(ArtMeshId id) {
            var sourceGeom = sourceGeometries.getOrDefault(id, new ArtMeshGeometry(List.of(), List.of(), List.of()));
            var locals = defaultLocals.getOrDefault(id, List.of(new Point2(0, 0)));
            var yawList = meshYawPoints.get(id);
            var pitchList = meshPitchPoints.get(id);
            if (yawList != null && pitchList != null) {
                var ys = yawList.stream().map(ParameterBindingPoint::value).toList();
                var ps = pitchList.stream().map(ParameterBindingPoint::value).toList();
                var yawDef = parameters.stream()
                        .filter(p -> p.id().value().contains("Yaw"))
                        .findFirst()
                        .orElseThrow();
                var pitchDef = parameters.stream()
                        .filter(p -> p.id().value().contains("Pitch"))
                        .findFirst()
                        .orElseThrow();
                var axes = List.of(
                        new ArtMeshAuthoringSnapshot.Axis(
                                "yaw-guid", yawDef.id(), ys.get(0), 0, ys.get(ys.size() - 1), ys),
                        new ArtMeshAuthoringSnapshot.Axis(
                                "pitch-guid", pitchDef.id(), ps.get(0), 0, ps.get(ps.size() - 1), ps));
                var cells = new ArrayList<ArtMeshAuthoringSnapshot.Cell>();
                var forms = new ArrayList<ArtMeshAuthoringSnapshot.Form>();
                int currentOrder = meshDrawOrders.getOrDefault(id, 500);
                for (float y : ys)
                    for (float p : ps) {
                        String guid = "form-" + forms.size();
                        cells.add(new ArtMeshAuthoringSnapshot.Cell(
                                guid,
                                List.of(
                                        new ArtMeshAuthoringSnapshot.Coordinate("yaw-guid", y),
                                        new ArtMeshAuthoringSnapshot.Coordinate("pitch-guid", p))));
                        forms.add(new ArtMeshAuthoringSnapshot.Form(guid, locals, currentOrder));
                    }
                snapshots.put(
                        id, new ArtMeshAuthoringSnapshot(id, "source", sourceGeom, axes, cells, forms, List.of()));
            } else {
                var forms = List.of(new ArtMeshAuthoringSnapshot.Form("form-0", locals, 0));
                snapshots.put(
                        id,
                        new ArtMeshAuthoringSnapshot(id, "source", sourceGeom, List.of(), List.of(), forms, List.of()));
            }
        }

        final Map<ArtMeshId, Optional<DeformerId>> meshDeformers = new HashMap<>();

        Drawable makeDrawable(ArtMeshId id) {
            return proxy(Drawable.class, (d, dm, da) -> switch (dm.getName()) {
                case "id" -> id;
                case "setCulling" -> null;
                case "culling" -> true;
                case "visible" -> true;
                case "isLocked" -> false;
                case "setDrawOrder" -> {
                    int drawOrder = (int) da[0];
                    meshDrawOrders.put(id, drawOrder);
                    var current = snapshots.get(id);
                    if (current != null) {
                        var updatedForms = current.forms().stream()
                                .map(f -> new ArtMeshAuthoringSnapshot.Form(f.formGuid(), f.positions(), drawOrder))
                                .toList();
                        snapshots.put(
                                id,
                                new ArtMeshAuthoringSnapshot(
                                        current.artMeshId(),
                                        current.sourceGuid(),
                                        current.sourceGeometry(),
                                        current.axes(),
                                        current.cells(),
                                        updatedForms,
                                        current.morphKeys()));
                    }
                    yield null;
                }
                case "setOrdinaryKeyformDrawOrders" -> {
                    var before = (ArtMeshAuthoringSnapshot) da[0];
                    @SuppressWarnings("unchecked")
                    var edits = (List<ArtMeshKeyformDrawOrder>) da[1];
                    var editMap = new HashMap<String, Integer>();
                    for (var edit : edits) editMap.put(edit.formGuid(), edit.drawOrder());
                    var updatedForms = before.forms().stream()
                            .map(f -> editMap.containsKey(f.formGuid())
                                    ? new ArtMeshAuthoringSnapshot.Form(
                                            f.formGuid(), f.positions(), editMap.get(f.formGuid()))
                                    : f)
                            .toList();
                    snapshots.put(
                            id,
                            new ArtMeshAuthoringSnapshot(
                                    before.artMeshId(),
                                    before.sourceGuid(),
                                    before.sourceGeometry(),
                                    before.axes(),
                                    before.cells(),
                                    updatedForms,
                                    before.morphKeys()));
                    yield null;
                }
                case "parentDeformerId", "targetDeformerId" -> {
                    var def = meshDeformers.getOrDefault(id, Optional.empty());
                    System.err.println("TRACE parentDeformerId for " + id + ": " + def);
                    yield def;
                }
                case "setTargetDeformer" -> {
                    @SuppressWarnings("unchecked")
                    var def = (Optional<DeformerId>) da[0];
                    System.err.println("TRACE setTargetDeformer for " + id + ": " + def);
                    meshDeformers.put(id, def);
                    yield null;
                }
                case "authoringSnapshot" -> snapshots.get(id);
                case "setOrdinaryKeyformPositions" -> {
                    var before = (ArtMeshAuthoringSnapshot) da[0];
                    @SuppressWarnings("unchecked")
                    var edits = (List<ArtMeshKeyformPositions>) da[1];
                    var editMap = new HashMap<String, List<Point2>>();
                    for (var edit : edits) editMap.put(edit.formGuid(), edit.positions());
                    var updatedForms = before.forms().stream()
                            .map(f -> editMap.containsKey(f.formGuid())
                                    ? new ArtMeshAuthoringSnapshot.Form(
                                            f.formGuid(), editMap.get(f.formGuid()), f.drawOrder())
                                    : f)
                            .toList();
                    snapshots.put(
                            id,
                            new ArtMeshAuthoringSnapshot(
                                    before.artMeshId(),
                                    before.sourceGuid(),
                                    before.sourceGeometry(),
                                    before.axes(),
                                    before.cells(),
                                    updatedForms,
                                    before.morphKeys()));
                    yield null;
                }
                default -> null;
            });
        }

        // Capture UI confirm info
        ModelImportPlugin.AtlasSummary capturedAtlasSummary;
        boolean confirmResult = true;
        int choice = 1; // default 1 = FACE_COLORS (Path 1 simple import)

        // Texture atlas append control
        boolean supportsAppend = true;
        boolean appendFails = false;
        AtlasPageAppendPlan lastAppendedPlan;
        int appendTextureAtlasPagesCalls = 0;
        int transactionCount = 0;
        boolean transactionRolledBack = false;

        String reportedMessage;
        boolean reportedIsError;

        final CompletableFuture<UserFileRequestResult> choose = new CompletableFuture<>();
        final CompletableFuture<UserFileReadResult<byte[]>> read = new CompletableFuture<>();
        final CountDownLatch outcome = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final DisposableScope scope = new DisposableScope();
        ActionRegistry.Action action;

        // Rollback state tracking
        final List<Runnable> rollbackActions = new ArrayList<>();

        static final class RotationState {
            final DeformerId id;
            RotationDeformerForm neutral = new RotationDeformerForm(0, 0, 0, 1, false, false);
            final List<ParameterBindingPoint> keys = new ArrayList<>();
            final Map<String, RotationDeformerForm> values = new HashMap<>();
            Optional<DeformerId> parent = Optional.empty();

            RotationState(DeformerId id) {
                this.id = id;
            }

            RotationAuthoringSnapshot snapshot(List<ParameterDefinition> parameters) {
                if (keys.isEmpty()) {
                    var forms = List.of(new RotationAuthoringSnapshot.Form("form-0", neutral));
                    return new RotationAuthoringSnapshot(id, "source", 0, List.of(), List.of(), forms);
                }
                var axis = new RotationAuthoringSnapshot.Axis(
                        "axis",
                        parameters.stream()
                                .map(ParameterDefinition::id)
                                .filter(p -> p.value().contains("Roll"))
                                .findFirst()
                                .orElse(new ParameterId("Param3DRoll")),
                        -180,
                        0,
                        180,
                        keys.stream().map(ParameterBindingPoint::value).toList());
                var cells = new ArrayList<RotationAuthoringSnapshot.Cell>();
                var forms = new ArrayList<RotationAuthoringSnapshot.Form>();
                for (int i = 0; i < keys.size(); i++) {
                    String guid = "f" + i;
                    cells.add(new RotationAuthoringSnapshot.Cell(
                            guid,
                            List.of(new RotationAuthoringSnapshot.Coordinate(
                                    "axis", keys.get(i).value()))));
                    forms.add(new RotationAuthoringSnapshot.Form(guid, values.getOrDefault(guid, neutral)));
                }
                return new RotationAuthoringSnapshot(id, "source", 0, List.of(axis), cells, forms);
            }
        }

        final Map<DeformerId, RotationState> rotationStates = new HashMap<>();

        final Map<ModelImageId, RawImageId> imageToRaw = new HashMap<>();

        final ModelTextures modelTextures = new ModelTextures() {
            @Override
            public List<RawTexture> rawImages() {
                return List.of();
            }

            @Override
            public List<ModelImageGroup> modelImageGroups() {
                return List.of();
            }

            @Override
            public List<AtlasTexture> textureAtlases() {
                return List.copyOf(atlases);
            }

            @Override
            public void addModelImageGroup(String name) {}

            @Override
            public void removeModelImage(ModelImageId id) {}

            @Override
            public TextureAtlasId addTextureAtlas(String name, int widthPixels, int heightPixels) {
                var id = new TextureAtlasId("atlas-" + atlases.size());
                var atlas = new MockTextureAtlas(id, name, widthPixels, heightPixels);
                atlases.add(atlas);
                rollbackActions.add(() -> atlases.remove(atlas));
                return id;
            }

            @Override
            public void removeTextureAtlas(TextureAtlasId id) {
                atlases.removeIf(a -> a.id().equals(id));
            }

            @Override
            public void removeRawImage(RawImageId id) {}

            @Override
            public boolean supportsAppendTextureAtlasPages() {
                return supportsAppend;
            }

            @Override
            public List<TextureAtlasId> appendTextureAtlasPages(AtlasPageAppendPlan plan) {
                appendTextureAtlasPagesCalls++;
                if (appendFails) {
                    throw new IllegalStateException("Simulated atlas append failure");
                }
                lastAppendedPlan = plan;
                var createdIds = new ArrayList<TextureAtlasId>();
                for (int i = 0; i < plan.pageCount(); i++) {
                    String name = i < plan.pageNames().size() ? plan.pageNames().get(i) : "Atlas " + atlases.size();
                    var id = new TextureAtlasId("atlas-" + atlases.size());
                    var atlas = new MockTextureAtlas(id, name, plan.pageWidth(), plan.pageHeight());
                    atlases.add(atlas);
                    createdIds.add(id);
                    rollbackActions.add(() -> atlases.remove(atlas));
                }

                // Emulate native relinking: meshes update to exact TEXTURE_ATLAS_REGION page
                var imageToPageId = new HashMap<ModelImageId, TextureAtlasId>();
                for (var p : plan.placements()) {
                    imageToPageId.put(p.modelImageId(), createdIds.get(p.pageIndex()));
                }
                for (var meshId : drawables) {
                    var bound = meshInputs.get(meshId);
                    if (bound != null && bound.modelImageId().isPresent()) {
                        var targetPage = imageToPageId.get(bound.modelImageId().get());
                        if (targetPage != null) {
                            meshInputs.put(
                                    meshId,
                                    new ArtMeshTextureInput(
                                            meshId,
                                            modelId,
                                            1L,
                                            ArtMeshTextureInput.Kind.TEXTURE_ATLAS_REGION,
                                            Optional.empty(),
                                            Optional.of(targetPage)));
                        }
                    }
                }
                return createdIds;
            }

            @Override
            public ModelImageBinding registerImageAndBind(AuthorizedImageContent image, ArtMeshId target) {
                var modelImageId = new ModelImageId("img-" + registeredBindings.size());
                var rawId = new RawImageId("raw-" + registeredBindings.size());
                imageToRaw.put(modelImageId, rawId);
                var binding = new ModelImageBinding("scope", modelId, 1L, rawId, modelImageId, Optional.of(target));
                registeredBindings.add(binding);
                meshInputs.put(
                        target,
                        new ArtMeshTextureInput(
                                target,
                                modelId,
                                1L,
                                ArtMeshTextureInput.Kind.MODEL_IMAGE,
                                Optional.of(modelImageId),
                                Optional.empty()));
                rollbackActions.add(() -> {
                    registeredBindings.remove(binding);
                    meshInputs.remove(target);
                });
                return binding;
            }

            @Override
            public ModelImageBinding bindModelImage(ModelImageId image, ArtMeshId target) {
                var rawId = imageToRaw.getOrDefault(image, new RawImageId("raw-shared"));
                var binding = new ModelImageBinding("scope", modelId, 1L, rawId, image, Optional.of(target));
                meshInputs.put(
                        target,
                        new ArtMeshTextureInput(
                                target,
                                modelId,
                                1L,
                                ArtMeshTextureInput.Kind.MODEL_IMAGE,
                                Optional.of(image),
                                Optional.empty()));
                rollbackActions.add(() -> meshInputs.remove(target));
                return binding;
            }

            @Override
            public Optional<ArtMeshTextureInput> textureInput(ArtMeshId target) {
                return Optional.ofNullable(meshInputs.get(target));
            }
        };

        final CubismModel model = proxy(CubismModel.class, (o, m, a) -> {
            if (m.getName().equals("id")) return modelId;
            if (m.getName().equals("canvas")) return proxy(Canvas.class, (x, n, v) -> 1024f);
            if (m.getName().equals("textures")) return modelTextures;
            if (m.getName().equals("parameterGroups"))
                return proxy(ParameterGroups.class, (x, n, v) -> {
                    if (n.getName().equals("addGroup")) {
                        assertFalse(((String) v[0]).isBlank());
                        return proxy(
                                ParameterGroup.class,
                                (g, gm, ga) ->
                                        gm.getName().equals("id") ? new ParameterGroupId("imported-group") : v[0]);
                    }
                    if (n.getName().equals("moveParameter")) return null;
                    throw new AssertionError(n);
                });
            if (m.getName().equals("parameters"))
                return proxy(Parameters.class, (x, n, v) -> {
                    if (n.getName().equals("all"))
                        return parameters.stream()
                                .map(def ->
                                        proxy(dev.turboism.sdk.cubism.model.Parameter.class, (p, pm, pa) -> def.id()))
                                .toList();
                    if (n.getName().equals("find")) {
                        var id = (ParameterId) v[0];
                        var def = parameters.stream()
                                .filter(p -> p.id().equals(id))
                                .findFirst()
                                .orElseThrow();
                        return proxy(
                                dev.turboism.sdk.cubism.model.Parameter.class, (p, pm, pa) -> switch (pm.getName()) {
                                    case "id" -> def.id();
                                    case "type" -> def.type();
                                    case "minimum" -> def.minimumValue();
                                    case "defaultValue" -> def.defaultValue();
                                    case "maximum" -> def.maximumValue();
                                    case "name" -> def.name();
                                    case "repeat" -> Optional.of(false);
                                    case "getDefaultValue" -> def.defaultValue();
                                    case "getValue" -> 0f;
                                    case "getMinimumValue" -> def.minimumValue();
                                    case "getMaximumValue" -> def.maximumValue();
                                    case "decimalPlaces" -> 2;
                                    default -> null;
                                });
                    }
                    if (n.getName().equals("createMany")) {
                        @SuppressWarnings("unchecked")
                        var list = (List<ParameterDefinition>) v[0];
                        parameters.addAll(list);
                        rollbackActions.add(() -> parameters.removeAll(list));
                        return null;
                    }
                    return null;
                });
            if (m.getName().equals("deformers"))
                return proxy(Deformers.class, (x, n, v) -> {
                    if (n.getName().equals("createWarp")) {
                        var warpId = new DeformerId("warp-" + UUID.randomUUID());
                        var warpParent =
                                new java.util.concurrent.atomic.AtomicReference<>(Optional.<DeformerId>empty());
                        return proxy(WarpDeformer.class, (d, dm, da) -> switch (dm.getName()) {
                            case "id" -> warpId;
                            case "replaceGrid" -> {
                                var g = (WarpGrid) da[0];
                                warpGrids.put(warpId, g);
                                updateWarpSnapshot(warpId);
                                yield null;
                            }
                            case "authoringSnapshot" -> warpSnapshots.get(warpId);
                            case "setOrdinaryKeyformPositions" -> {
                                var before = (WarpAuthoringSnapshot) da[0];
                                @SuppressWarnings("unchecked")
                                var edits = (List<WarpKeyformPositions>) da[1];
                                var byGuid = new HashMap<String, List<Point2>>();
                                edits.forEach(e -> byGuid.put(e.formGuid(), e.controlPoints()));
                                var updated = new WarpAuthoringSnapshot(
                                        warpId,
                                        before.sourceGuid(),
                                        before.rows(),
                                        before.columns(),
                                        before.quadTransform(),
                                        before.axes(),
                                        before.cells(),
                                        before.forms().stream()
                                                .map(f -> new WarpAuthoringSnapshot.Form(
                                                        f.formGuid(),
                                                        byGuid.getOrDefault(f.formGuid(), f.controlPoints())))
                                                .toList());
                                warpSnapshots.put(warpId, updated);
                                yield null;
                            }
                            case "parentDeformerId", "targetDeformerId" -> warpParent.get();
                            case "setTargetDeformer" -> {
                                @SuppressWarnings("unchecked")
                                var p = (Optional<DeformerId>) da[0];
                                warpParent.set(p);
                                yield null;
                            }
                            default -> null;
                        });
                    }
                    if (n.getName().equals("createRotation")) {
                        var id = new DeformerId("rotation-" + UUID.randomUUID());
                        var st = rotationStates.computeIfAbsent(id, RotationState::new);
                        return proxy(RotationDeformer.class, (d, dm, da) -> switch (dm.getName()) {
                            case "id" -> id;
                            case "parentDeformerId", "targetDeformerId" -> st.parent;
                            case "setTargetDeformer" -> {
                                @SuppressWarnings("unchecked")
                                var p = (Optional<DeformerId>) da[0];
                                st.parent = p;
                                yield null;
                            }
                            case "replaceForm" -> {
                                st.neutral = (RotationDeformerForm) da[0];
                                yield null;
                            }
                            case "authoringSnapshot" -> st.snapshot(parameters);
                            case "setOrdinaryKeyformForms" -> {
                                if (da[1] instanceof List<?> edits) {
                                    for (var item : edits) {
                                        if (item instanceof RotationKeyformForm kf) {
                                            st.values.put(kf.formGuid(), kf.value());
                                        }
                                    }
                                }
                                yield null;
                            }
                            default -> null;
                        });
                    }
                    return null;
                });
            if (m.getName().equals("parameterBindings")) {
                var paramId = (ParameterId) a[0];
                return proxy(ParameterBindingOperations.class, (b, bm, ba) -> {
                    if (bm.getName().equals("bind")) {
                        var target = (ParameterBindingTarget) ba[0];
                        if (ba[1] instanceof List<?> list
                                && !list.isEmpty()
                                && list.get(0) instanceof ParameterBindingPoint) {
                            @SuppressWarnings("unchecked")
                            var points = (List<ParameterBindingPoint>) list;
                            if (target.type() == ParameterBindingTargetType.ART_MESH) {
                                var meshId = new ArtMeshId(target.id());
                                if (paramId.value().contains("Yaw")) meshYawPoints.put(meshId, points);
                                if (paramId.value().contains("Pitch")) meshPitchPoints.put(meshId, points);
                                updateMeshSnapshot(meshId);
                            } else if (target.type() == ParameterBindingTargetType.ROTATION_DEFORMER) {
                                var rotId = new DeformerId(target.id());
                                var st = rotationStates.computeIfAbsent(rotId, RotationState::new);
                                st.keys.clear();
                                st.keys.addAll(points);
                            } else if (target.type() == ParameterBindingTargetType.WARP_DEFORMER) {
                                var wid = new DeformerId(target.id());
                                if (paramId.value().contains("Yaw")) warpYawPoints.put(wid, points);
                                if (paramId.value().contains("Pitch")) warpPitchPoints.put(wid, points);
                                updateWarpSnapshot(wid);
                            }
                        }
                    }
                    return null;
                });
            }
            if (m.getName().equals("parts"))
                return proxy(Parts.class, (x, n, v) -> {
                    return proxy(Part.class, (p, pm, pa) -> {
                        if (pm.getName().equals("id")) return new PartId("part-" + UUID.randomUUID());
                        return null;
                    });
                });
            if (m.getName().equals("drawables"))
                return proxy(Drawables.class, (x, n, v) -> switch (n.getName()) {
                    case "all" -> drawables.stream().map(this::makeDrawable).toList();
                    case "find" -> makeDrawable((ArtMeshId) v[0]);
                    case "create", "createWithSourceGeometry" -> {
                        var id = new ArtMeshId("mesh-" + drawables.size());
                        drawables.add(id);
                        if (v.length > 3 && v[3] instanceof ArtMeshGeometry ag) {
                            sourceGeometries.put(id, ag);
                            geometries.put(id, new ArtMeshSourceTriangulation(ag.positions(), ag.triangleIndices()));
                        }
                        if (n.getName().equals("createWithSourceGeometry")) {
                            if (v.length > 4 && v[4] instanceof List<?> dl) {
                                @SuppressWarnings("unchecked")
                                var list = (List<Point2>) dl;
                                defaultLocals.put(id, list);
                            }
                        }
                        updateMeshSnapshot(id);
                        rollbackActions.add(() -> {
                            drawables.remove(id);
                            geometries.remove(id);
                            sourceGeometries.remove(id);
                            defaultLocals.remove(id);
                            snapshots.remove(id);
                        });
                        yield makeDrawable(id);
                    }
                    case "previewSourceTriangulation" -> {
                        var geom = (ArtMeshGeometry) v[0];
                        yield new ArtMeshSourceTriangulation(geom.positions(), geom.triangleIndices());
                    }
                    default -> null;
                });
            return null;
        });

        final AuthoringTransactionService transactionService = new AuthoringTransactionService() {
            @Override
            public <T> AuthoringTransactionResult<T> execute(
                    AuthoringTransactionOptions options, AuthoringTransactionWork<T> work) {
                transactionCount++;
                try {
                    T result = work.run();
                    return AuthoringTransactionResult.committed(
                            result,
                            new AuthoringTransactionReceipt(
                                    "tx-" + transactionCount,
                                    options.label(),
                                    HistorySnapshot.unavailable(),
                                    HistorySnapshot.unavailable(),
                                    Optional.of("entry-" + transactionCount)));
                } catch (Throwable t) {
                    transactionRolledBack = true;
                    var reverse = new ArrayList<>(rollbackActions);
                    Collections.reverse(reverse);
                    for (var r : reverse) {
                        try {
                            r.run();
                        } catch (Exception ignored) {
                        }
                    }
                    rollbackActions.clear();
                    if (t instanceof RuntimeException re) throw re;
                    if (t instanceof Error e) throw e;
                    throw new RuntimeException(t);
                }
            }
        };

        CubismFacade cubism() {
            return proxy(CubismFacade.class, (o, m, a) -> {
                if (m.getName().equals("activeDocument")) {
                    return Optional.of(
                            new DocumentSnapshot("doc-1", "model", "model.cmo3", Optional.empty(), Optional.empty()));
                }
                if (m.getName().equals("model")) return proxy(m.getReturnType(), (x, n, v) -> model);
                if (m.getName().equals("authoringTransactions")) return transactionService;
                return null;
            });
        }

        PluginContext context() {
            return proxy(PluginContext.class, (o, m, a) -> switch (m.getName()) {
                case "cubism" -> cubism();
                case "disposableScope" -> scope;
                case "actions" ->
                    proxy(m.getReturnType(), (x, n, v) -> {
                        action = (ActionRegistry.Action) v[1];
                        return (Registration) () -> {};
                    });
                case "menus" -> proxy(m.getReturnType(), (x, n, v) -> (Registration) () -> {});
                case "logger" -> proxy(m.getReturnType(), (x, n, v) -> null);
                case "localization" ->
                    proxy(PluginLocalization.class, (x, n, v) -> switch (n.getName()) {
                        case "text" -> v.length > 1 ? v[1] : v[0];
                        case "format" -> {
                            Object[] args = Arrays.copyOfRange(v, 1, v.length);
                            yield java.text.MessageFormat.format(String.valueOf(v[0]), args);
                        }
                        default -> "";
                    });
                case "userFiles" ->
                    proxy(UserFileAccessService.class, (x, n, v) -> {
                        if (n.getName().equals("request")) return choose;
                        if (n.getName().equals("readBytes") || n.getName().equals("readBytesBounded")) return read;
                        return null;
                    });
                default -> null;
            });
        }

        @Override
        public int appearance() {
            return choice;
        }

        @Override
        public boolean confirm(SurfaceImport.Prepared p) {
            outcome.countDown();
            return confirmResult;
        }

        @Override
        public boolean confirm(SurfaceImport.Prepared p, ModelImportPlugin.AtlasSummary summary) {
            capturedAtlasSummary = summary;
            return confirm(p);
        }

        @Override
        public boolean confirmLayers(SurfaceWarpLayerAuthoring.Prepared p) {
            outcome.countDown();
            return confirmResult;
        }

        @Override
        public boolean confirmLayers(SurfaceWarpLayerAuthoring.Prepared p, ModelImportPlugin.AtlasSummary summary) {
            capturedAtlasSummary = summary;
            return confirmLayers(p);
        }

        @Override
        public boolean confirmVertices(SurfaceVertexAuthoring.Prepared p) {
            outcome.countDown();
            return confirmResult;
        }

        @Override
        public boolean confirmVertices(SurfaceVertexAuthoring.Prepared p, ModelImportPlugin.AtlasSummary summary) {
            capturedAtlasSummary = summary;
            return confirmVertices(p);
        }

        @Override
        public boolean confirmVertices(
                SurfaceVertexAuthoring.Prepared p,
                ModelImportPlugin.OptimizationSummary optimization,
                ModelImportPlugin.AtlasSummary summary) {
            capturedAtlasSummary = summary;
            return confirmVertices(p);
        }

        @Override
        public void message(String text, boolean error) {
            reportedMessage = text;
            reportedIsError = error;
            finished.countDown();
            outcome.countDown();
        }

        ModelImportPlugin start() throws Exception {
            var plugin = new ModelImportPlugin(this);
            plugin.init(context());
            plugin.enable();
            action.handler().accept(null);
            edt(() -> {});
            return plugin;
        }

        void feedModel(byte[] glb) {
            choose.complete(new UserFileRequestResult(
                    UserFileRequestStatus.GRANTED,
                    Optional.of(proxy(UserFileHandle.class, (o, m, a) -> null)),
                    Optional.empty()));
            read.complete(new UserFileReadResult<>(Optional.of(glb), Optional.empty(), false));
        }
    }

    @Test
    void simpleImportCreatesNativeAtlasAndRemapsMeshInputs() throws Exception {
        var w = new TestWorld();
        w.choice = 1; // FACE_COLORS -> Path 1 (simple import)
        w.start();
        byte[] cubeGlb = ModelImportTestFixtures.readBytes("test-assets/model-import-3d/cube-985496727e50/cube.glb");
        w.feedModel(cubeGlb);
        assertTrue(w.finished.await(10, TimeUnit.SECONDS));
        edt(() -> {});

        // Confirmation dialog received atlas summary
        System.err.println("TEST DEBUG: reportedMessage=" + w.reportedMessage + ", isError=" + w.reportedIsError
                + ", txRolledBack=" + w.transactionRolledBack + ", txCount=" + w.transactionCount);
        assertNotNull(w.capturedAtlasSummary, "Confirmation dialog should receive AtlasSummary");
        assertEquals(2, w.capturedAtlasSummary.pageCount(), "6 face colors at 256x128 pack into 2 pages of 512x512");
        assertEquals(512, w.capturedAtlasSummary.width(), "Cube should plan 512x512 atlas");
        assertEquals(512, w.capturedAtlasSummary.height());

        // ModelTextures.appendTextureAtlasPages was called
        assertEquals(1, w.appendTextureAtlasPagesCalls);
        assertEquals(2, w.atlases.size(), "Two atlas pages should be created on model");
        assertNotNull(w.lastAppendedPlan);

        // Mesh texture inputs were migrated to TEXTURE_ATLAS_REGION
        assertFalse(w.drawables.isEmpty(), "Model should have created drawables");
        for (var meshId : w.drawables) {
            var input = w.meshInputs.get(meshId);
            assertNotNull(input, "Mesh should have a texture input: " + meshId);
            assertEquals(
                    ArtMeshTextureInput.Kind.TEXTURE_ATLAS_REGION,
                    input.kind(),
                    "Mesh input must be TEXTURE_ATLAS_REGION after import");
        }

        // Single transaction was used
        assertEquals(1, w.transactionCount, "Import should run within exactly one authoring transaction");
        assertFalse(w.transactionRolledBack, "Transaction should commit successfully");
    }

    @Test
    void layeredImportCreatesNativeAtlas() throws Exception {
        var w = new TestWorld();
        w.choice = 0; // SOURCE_MATERIAL -> Path 2 (layered import)
        w.start();
        byte[] cubeGlb = ModelImportTestFixtures.readBytes("test-assets/model-import-3d/cube-985496727e50/cube.glb");
        w.feedModel(cubeGlb);
        assertTrue(w.finished.await(10, TimeUnit.SECONDS));
        edt(() -> {});

        System.err.println("TEST DEBUG layered: reportedMessage=" + w.reportedMessage + ", isError=" + w.reportedIsError
                + ", txRolledBack=" + w.transactionRolledBack);
        assertNotNull(w.capturedAtlasSummary, "Confirmation dialog should receive AtlasSummary");
        // Layered charts are 576x576 -> plan 1024x1024
        assertEquals(1024, w.capturedAtlasSummary.width());
        assertEquals(1024, w.capturedAtlasSummary.height());
        assertEquals(1, w.capturedAtlasSummary.pageCount());

        assertEquals(1, w.appendTextureAtlasPagesCalls);
        assertEquals(1, w.atlases.size());
        assertFalse(w.drawables.isEmpty());
        for (var meshId : w.drawables) {
            assertEquals(
                    ArtMeshTextureInput.Kind.TEXTURE_ATLAS_REGION,
                    w.meshInputs.get(meshId).kind());
        }
    }

    @Test
    void texturedCubeCreatesCorrectAtlasDimensionsAndDeduplicates() throws Exception {
        var w = new TestWorld();
        w.choice = 0;
        w.start();
        byte[] texturedGlb =
                ModelImportTestFixtures.readBytes("test-assets/model-import-3d/cube-textured/cube-textured.glb");
        w.feedModel(texturedGlb);
        assertTrue(w.finished.await(10, TimeUnit.SECONDS));
        edt(() -> {});

        assertNotNull(w.capturedAtlasSummary);
        assertEquals(2048, w.capturedAtlasSummary.width());
        assertEquals(2048, w.capturedAtlasSummary.height());
        assertEquals(1, w.capturedAtlasSummary.pageCount());

        assertEquals(1, w.appendTextureAtlasPagesCalls);
        for (var meshId : w.drawables) {
            assertEquals(
                    ArtMeshTextureInput.Kind.TEXTURE_ATLAS_REGION,
                    w.meshInputs.get(meshId).kind());
        }
    }

    @Test
    void preExistingAtlasPreservedAndNewPageCreated() throws Exception {
        var w = new TestWorld();
        w.choice = 1;
        // Add pre-existing atlas of size 1024x1024
        var oldAtlasId = new TextureAtlasId("existing-atlas-1");
        w.atlases.add(new MockTextureAtlas(oldAtlasId, "Old Atlas", 1024, 1024));

        w.start();
        byte[] cubeGlb = ModelImportTestFixtures.readBytes("test-assets/model-import-3d/cube-985496727e50/cube.glb");
        w.feedModel(cubeGlb);
        assertTrue(w.finished.await(10, TimeUnit.SECONDS));
        edt(() -> {});

        // Pre-existing atlas must still exist!
        assertTrue(
                w.atlases.stream().anyMatch(a -> a.id().equals(oldAtlasId)),
                "Pre-existing atlas must not be overwritten");
        // New atlas added via appendTextureAtlasPages
        assertEquals(1, w.appendTextureAtlasPagesCalls);
        assertEquals(3, w.atlases.size(), "Model should now have 3 atlases total (1 old + 2 new)");

        // New atlas should be sized by new textures (512x512), heterogeneous from old atlas (1024x1024)
        var newAtlas = w.atlases.stream()
                .filter(a -> !a.id().equals(oldAtlasId))
                .findFirst()
                .orElseThrow();
        assertEquals(512, newAtlas.width(), "New atlas should be sized by new textures, not old atlas");
        assertEquals(512, newAtlas.height());

        assertNotNull(w.lastAppendedPlan);
        assertEquals(2, w.lastAppendedPlan.pageCount());
    }

    @Test
    void appendFailureTriggersTransactionRollbackLeavingNoPartialArtifacts() throws Exception {
        var w = new TestWorld();
        w.choice = 1;
        w.appendFails = true; // Inject failure in appendTextureAtlasPages

        w.start();
        byte[] cubeGlb = ModelImportTestFixtures.readBytes("test-assets/model-import-3d/cube-985496727e50/cube.glb");
        w.feedModel(cubeGlb);
        assertTrue(w.finished.await(10, TimeUnit.SECONDS));
        edt(() -> {});

        assertTrue(w.transactionRolledBack, "Transaction should roll back on layout failure");
        assertTrue(w.reportedIsError, "Should report an error message");

        // Model must be left clean: no parameters, no meshes, no atlases left behind
        assertTrue(w.parameters.isEmpty(), "Parameters should be rolled back");
        assertTrue(w.drawables.isEmpty(), "Drawables should be rolled back");
        assertTrue(w.atlases.isEmpty(), "Atlases should be rolled back");
    }

    @Test
    void supportsAppendTextureAtlasPagesFalseRejectsBeforeMutation() throws Exception {
        var w = new TestWorld();
        w.supportsAppend = false; // Capability unavailable

        w.start();
        byte[] cubeGlb = ModelImportTestFixtures.readBytes("test-assets/model-import-3d/cube-985496727e50/cube.glb");
        w.feedModel(cubeGlb);
        assertTrue(w.finished.await(10, TimeUnit.SECONDS));
        edt(() -> {});

        assertTrue(w.reportedIsError, "Should report an error when layout capability is unavailable");
        assertEquals(0, w.transactionCount, "No transaction should begin when capability check fails");
        assertEquals(0, w.appendTextureAtlasPagesCalls, "No atlas should be created");
        assertTrue(w.drawables.isEmpty(), "No drawables should be created");
    }

    @Test
    void allSixI18nBundlesContainRequiredAtlasKeys() throws Exception {
        String[] bundles = {
            "messages.properties",
            "messages_en.properties",
            "messages_ja.properties",
            "messages_ko.properties",
            "messages_zh_Hans.properties",
            "messages_zh_Hant.properties"
        };
        String[] requiredKeys = {
            "atlas.page-name",
            "dialog.confirm.simple",
            "dialog.confirm.layers",
            "dialog.confirm.vertices",
            "dialog.confirm.vertices-optimized",
            "dialog.confirm.atlas-info",
            "error.texture-atlas-unavailable",
            "error.atlas-budget-exceeded",
            "error.atlas-layout-failed",
            "error.atlas-oversized"
        };

        for (String bundleName : bundles) {
            String path = "META-INF/turboism/i18n/" + bundleName;
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
                assertNotNull(in, "Bundle file not found: " + path);
                var props = new Properties();
                props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                for (String key : requiredKeys) {
                    assertTrue(props.containsKey(key), "Bundle " + bundleName + " is missing required key: " + key);
                    assertFalse(
                            props.getProperty(key).isBlank(),
                            "Key " + key + " in " + bundleName + " must not be blank");
                }
            }
        }
    }

    @Test
    void textureIdMappingPreservesExactPageAndMaterialWhenSortedByDifferentSizes() {
        var items = List.of(
                new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem("source-small", 100, 100),
                new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem("source-large", 800, 800),
                new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem("source-med", 300, 300));

        var plan = ModelTextureAtlasPlanner.plan(items, ModelTextureAtlasPlanner.Options.DEFAULT);
        assertEquals(3, plan.placements().size());
        assertEquals("source-large", plan.placements().get(0).textureId());
        assertEquals(800, plan.placements().get(0).width());

        var w = new TestWorld();
        var meshSmall = new ArtMeshId("mesh-small");
        var meshLarge = new ArtMeshId("mesh-large");
        var meshMed = new ArtMeshId("mesh-med");
        w.drawables.addAll(List.of(meshSmall, meshLarge, meshMed));

        var bindSmall = new ModelImageBinding(
                "scope", w.modelId, 1L, new RawImageId("raw-1"), new ModelImageId("img-small"), Optional.of(meshSmall));
        var bindLarge = new ModelImageBinding(
                "scope", w.modelId, 1L, new RawImageId("raw-2"), new ModelImageId("img-large"), Optional.of(meshLarge));
        var bindMed = new ModelImageBinding(
                "scope", w.modelId, 1L, new RawImageId("raw-3"), new ModelImageId("img-med"), Optional.of(meshMed));

        w.meshInputs.put(
                meshSmall,
                new ArtMeshTextureInput(
                        meshSmall,
                        w.modelId,
                        1L,
                        ArtMeshTextureInput.Kind.MODEL_IMAGE,
                        Optional.of(bindSmall.modelImageId()),
                        Optional.empty()));
        w.meshInputs.put(
                meshLarge,
                new ArtMeshTextureInput(
                        meshLarge,
                        w.modelId,
                        1L,
                        ArtMeshTextureInput.Kind.MODEL_IMAGE,
                        Optional.of(bindLarge.modelImageId()),
                        Optional.empty()));
        w.meshInputs.put(
                meshMed,
                new ArtMeshTextureInput(
                        meshMed,
                        w.modelId,
                        1L,
                        ArtMeshTextureInput.Kind.MODEL_IMAGE,
                        Optional.of(bindMed.modelImageId()),
                        Optional.empty()));

        var bindingsBySourceId = Map.of(
                "source-small", bindSmall,
                "source-large", bindLarge,
                "source-med", bindMed);
        var meshBindings = Map.of(
                meshSmall, bindSmall,
                meshLarge, bindLarge,
                meshMed, bindMed);

        var result = ModelTextureAtlasAuthoring.apply(w.model, w.drawables, bindingsBySourceId, meshBindings, plan);

        assertEquals(2, result.createdAtlasIds().size(), "Should plan 2 pages to optimize memory");
        var page0Id = result.createdAtlasIds().get(0);
        var page1Id = result.createdAtlasIds().get(1);

        for (var p : result.appliedPlan().placements()) {
            if (p.modelImageId().equals(bindLarge.modelImageId())) {
                assertEquals(800, p.width());
                assertEquals(0, p.pageIndex());
            } else if (p.modelImageId().equals(bindMed.modelImageId())) {
                assertEquals(300, p.width());
                assertEquals(1, p.pageIndex());
            } else if (p.modelImageId().equals(bindSmall.modelImageId())) {
                assertEquals(100, p.width());
                assertEquals(1, p.pageIndex());
            } else {
                fail("Unexpected placement modelImageId: " + p.modelImageId());
            }
        }
        assertEquals(
                ArtMeshTextureInput.Kind.TEXTURE_ATLAS_REGION,
                w.meshInputs.get(meshLarge).kind());
        assertEquals(
                Optional.of(page0Id), w.meshInputs.get(meshLarge).textureAtlasId(), "Large mesh must bind to page 0");

        assertEquals(
                ArtMeshTextureInput.Kind.TEXTURE_ATLAS_REGION,
                w.meshInputs.get(meshMed).kind());
        assertEquals(
                Optional.of(page1Id), w.meshInputs.get(meshMed).textureAtlasId(), "Medium mesh must bind to page 1");

        assertEquals(
                ArtMeshTextureInput.Kind.TEXTURE_ATLAS_REGION,
                w.meshInputs.get(meshSmall).kind());
        assertEquals(
                Optional.of(page1Id), w.meshInputs.get(meshSmall).textureAtlasId(), "Small mesh must bind to page 1");
    }
}
