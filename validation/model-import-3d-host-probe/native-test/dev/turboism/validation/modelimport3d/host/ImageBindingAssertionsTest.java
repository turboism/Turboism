package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistoryEntryId;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshTextureInput;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Cell;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form;
import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import dev.turboism.sdk.cubism.model.Canvas;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.ModelImageBinding;
import dev.turboism.sdk.cubism.model.ModelImageEntry;
import dev.turboism.sdk.cubism.model.ModelImageGroup;
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.cubism.model.RawTexture;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SDK-interface stub world driving the real {@link ImageBindingProbe} through every leg:
 * registration, undo/redo, shared bind, no-change rebind, the five-case rejection matrix,
 * the honest UNAVAILABLE decode-counter row and the finally-restore contract. No runtime
 * or Cubism classes are touched.
 */
final class ImageBindingAssertionsTest {
    private static int checks;
    private static final Set<String> EXPECTED_CHECKS = Set.of(
        "registerBindExecuted", "handleIdentityFields", "registrationProjection", "bindingHandleArtMesh",
        "shareSecondMeshBound", "rebindNoChange", "differentImageRejected", "lockedTargetRejected",
        "deformerParentRejected", "oversizePngRejected", "undersizePngRejected", "rejectionsZeroMutation",
        "undoRestoresProjection", "redoRestoresProjection", "handleFieldsStableAcrossRedo",
        "meshSnapshotUnchanged", "decodeCountersUnavailable", "historyPositionRestored", "finalStateClean",
        "boundInputRegistered", "boundInputUndoUnbound", "boundInputRedoSameImage",
        "boundInputRebindUnchanged", "boundInputShareSameImage", "boundInputSecondImage",
        "boundInputRejectionsUnchanged");

    static void run() throws Exception {
        var world = new World();
        var data = new LinkedHashMap<String, Object>();
        ImageBindingProbe.run(world::model, world::history, world::restore, data);
        check(data.get("status").equals("PASS") && data.get("failure") == null
            && data.get("finallyRestored").equals(true), "probe reports PASS with restore");
        var rows = (List<Map<String, Object>>) data.get("checks");
        check(rows.size() == 26 && rows.stream().allMatch(r -> r.get("status").equals("PASS")
            && r.get("actual").equals(true) && r.get("evidenceKind").equals("EXACT_HOST")), "26 checks pass");
        check(rows.stream().map(r -> (String) r.get("id")).collect(Collectors.toSet())
            .equals(EXPECTED_CHECKS), "check inventory matches the frozen ids");
        var samples = (List<Map<String, Object>>) data.get("samples");
        check(samples.stream().map(s -> (String) s.get("kind")).toList().equals(List.of(
            "registration", "undo", "redo", "rebind", "share",
            "rejection", "rejection", "rejection", "rejection", "rejection", "decodeCounters")),
            "sample inventory covers every leg");
        check(samples.stream().filter(s -> s.get("kind").equals("rejection"))
            .map(s -> (String) s.get("id")).toList().equals(List.of(
            "differentImage", "locked", "deformerParent", "oversizePng", "undersizePng")),
            "rejection matrix order is frozen");
        var counters = samples.stream().filter(s -> s.get("kind").equals("decodeCounters"))
            .findFirst().orElseThrow();
        check(counters.get("resolution").equals("UNAVAILABLE") && counters.get("failure") == null
            && !((String) counters.get("reason")).isBlank(), "decode counters honestly UNAVAILABLE");
        var setup = (List<Map<String, Object>>) data.get("setupMutations");
        check(setup.size() == 9 && setup.stream().allMatch(s -> s.get("historyBefore") != null
            && s.get("historyAfter") != null), "setup mutations booked separately");
        var handle = (Map<?, ?>) samples.get(0).get("handle");
        check(handle.get("scope").equals("runtime.editor-model") && handle.get("modelGeneration").equals(11L),
            "handle carries scope and generation");

        // Host finding (job 46c9d38e): textureIndex legs are integers when the Core
        // evaluated data is immediately available (the synthetic default).
        check(samples.get(0).get("textureIndexBefore") instanceof Integer
            && samples.get(0).get("textureIndexAfter") instanceof Integer
            && samples.get(1).get("textureIndex") instanceof Integer
            && samples.get(2).get("textureIndex") instanceof Integer
            && samples.get(4).get("textureIndexBefore") instanceof Integer
            && samples.get(4).get("textureIndexAfter") instanceof Integer,
            "textureIndex legs are integers when evaluation is immediately available");

        // Delayed join: the first reads fail-closed, then evaluation data arrives and
        // the availability-aware read recovers with integer legs.
        var delayed = new World(false, 4);
        var delayedData = new LinkedHashMap<String, Object>();
        ImageBindingProbe.run(delayed::model, delayed::history, delayed::restore, delayedData);
        var delayedSamples = (List<Map<String, Object>>) delayedData.get("samples");
        check(delayedData.get("status").equals("PASS")
            && delayedSamples.get(0).get("textureIndexBefore") instanceof Integer,
            "textureIndex read recovers once evaluation data joins");

        // Permanently unavailable evaluation: every leg reports "UNAVAILABLE" with a
        // non-blank reason and the probe still completes PASS honestly.
        var unavailable = new World(false, Integer.MAX_VALUE);
        var unavailableData = new LinkedHashMap<String, Object>();
        ImageBindingProbe.run(unavailable::model, unavailable::history, unavailable::restore, unavailableData);
        var unavailableSamples = (List<Map<String, Object>>) unavailableData.get("samples");
        check(unavailableData.get("status").equals("PASS")
            && unavailableSamples.get(0).get("textureIndexBefore").equals("UNAVAILABLE")
            && unavailableSamples.get(0).get("textureIndexAfter").equals("UNAVAILABLE")
            && unavailableSamples.get(1).get("textureIndex").equals("UNAVAILABLE")
            && unavailableSamples.get(2).get("textureIndex").equals("UNAVAILABLE")
            && unavailableSamples.get(4).get("textureIndexBefore").equals("UNAVAILABLE")
            && unavailableSamples.get(4).get("textureIndexAfter").equals("UNAVAILABLE")
            && !((String) unavailableSamples.get(0).get("reason")).isBlank()
            && !((String) unavailableSamples.get(1).get("reason")).isBlank()
            && !((String) unavailableSamples.get(2).get("reason")).isBlank()
            && !((String) unavailableSamples.get(4).get("reason")).isBlank(),
            "permanently unavailable evaluation reports UNAVAILABLE legs with reasons");

        // Real NativeReport serialization loop: the UNAVAILABLE-evaluation run mirrors
        // the exact-host textureIndex shape, so it is the report the Python oracle reads.
        String dump = System.getProperty("mi3d.imageBindingReport");
        if (dump != null) {
            var report = new LinkedHashMap<String, Object>();
            report.put("document", Map.of("editorModelId", "editor-fixture"));
            report.put("historyRestored", unavailableData.get("restoredHistory"));
            report.put("morphOrder", Map.of("restoredCounts", unavailableData.get("restoredCounts"),
                "restoredCanvas", unavailableData.get("restoredCanvas")));
            var section = new LinkedHashMap<String, Object>(unavailableData);
            section.put("schemaVersion", "mi3d-native-n5a-v1");
            report.put("textureBinding", section);
            var out = java.nio.file.Path.of(dump);
            if (out.getParent() != null) java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, NativeReport.json(report) + "\n",
                java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
        }

        // A corrupt backend that mutates on a rejected call must fail the probe.
        var corrupt = new World(true);
        var failed = new LinkedHashMap<String, Object>();
        try {
            ImageBindingProbe.run(corrupt::model, corrupt::history, corrupt::restore, failed);
            throw new AssertionError("corrupt binding backend was accepted");
        } catch (IllegalStateException expected) {
            // r7 threading: execute() runs on the caller thread (no EDT task wrap),
            // so the probe failure surfaces unwrapped.
            check(failed.get("status").equals("FAIL"), "mutating rejection fails the probe");
        }
        System.out.println("ImageBindingAssertionsTest checks=" + checks + " PASS");
    }

    private static void check(boolean value, String message) { NativeAssertions.require(value, message); checks++; }

    /** SDK-only fake model world with honest undo/redo timeline semantics. */
    private static final class World {
        private final ModelId modelId = new ModelId("editor-fixture");
        private final List<HistoryEntry> entries = new ArrayList<>();
        private final List<State> timeline = new ArrayList<>();
        private final List<Mesh> meshes = new ArrayList<>();
        private final List<String> deformers = new ArrayList<>();
        private Map<String, Map<String, Object>> raws = new LinkedHashMap<>();
        private List<Map<String, Object>> groups = new ArrayList<>();
        private final Map<String, String> imageRaw = new LinkedHashMap<>();
        private int position, revision = 3, sequence;
        private final boolean corrupt;
        private int textureIndexFailures;
        private final CubismModel model = buildModel();

        World() { this(false); }
        World(boolean corrupt) { this(corrupt, 0); }
        World(boolean corrupt, int textureIndexFailures) {
            this.corrupt = corrupt; this.textureIndexFailures = textureIndexFailures;
            timeline.add(capture());
        }

        CubismModel model() { return model; }

        HistorySnapshot history() {
            return new HistorySnapshot(HistorySnapshot.Availability.AVAILABLE, 1, revision, position,
                List.copyOf(entries), position > 0, position < entries.size(), "doc-fixture", "mgr-fixture");
        }

        void restore(int target) {
            if (target < 0 || target >= timeline.size()) throw new IllegalArgumentException("bad position");
            load(timeline.get(position = target));
        }

        private void commit(String label) {
            entries.add(new HistoryEntry(position, label, true, Optional.empty(),
                Optional.of(new HistoryEntryId("n5a-" + position)), Optional.empty()));
            position++; revision++; timeline.add(capture());
        }

        private State capture() {
            return new State(
                meshes.stream().map(m -> new MeshState(m.locked, m.parent, m.bound, m.textureIndex)).toList(),
                List.copyOf(deformers), new LinkedHashMap<>(raws),
                groups.stream().map(g -> (Map<String, Object>) new LinkedHashMap<>(g)).toList());
        }

        private void load(State state) {
            for (int i = 0; i < state.meshes().size(); i++) {
                var m = meshes.get(i); var s = state.meshes().get(i);
                m.locked = s.locked(); m.parent = s.parent(); m.bound = s.bound();
                m.textureIndex = s.textureIndex();
            }
            while (meshes.size() > state.meshes().size()) meshes.remove(meshes.size() - 1);
            deformers.clear(); deformers.addAll(state.deformers());
            raws = new LinkedHashMap<>(state.raws());
            groups = new ArrayList<>(state.groups().stream()
                .map(g -> (Map<String, Object>) new LinkedHashMap<>(g)).toList());
        }

        private Mesh mesh(ArtMeshId id) {
            return meshes.stream().filter(m -> m.id.equals(id)).findFirst()
                .orElseThrow(() -> new NoSuchElementException(id.value()));
        }

        private ModelImageBinding handle(Mesh mesh, String imageId) {
            return new ModelImageBinding("runtime.editor-model", modelId, 11,
                new RawImageId(imageRaw.get(imageId)), new ModelImageId(imageId), Optional.of(mesh.id));
        }

        private void requireBindable(Mesh mesh) {
            if (mesh.locked) throw new IllegalStateException("locked hierarchy");
            if (mesh.parent != null) throw new IllegalStateException("deformer parent");
        }

        private ModelImageBinding register(AuthorizedImageContent grant, ArtMeshId target) {
            grant.claim();
            var mesh = mesh(target); requireBindable(mesh);
            if (mesh.bound != null) throw new IllegalStateException("already bound");
            var rawId = "raw-" + (++sequence); var imageId = "image-" + sequence;
            raws.put(rawId, Map.of("id", rawId, "name", "mi3d", "width", 256, "height", 128));
            var group = new LinkedHashMap<String, Object>();
            group.put("groupName", "group-" + rawId); group.put("memo", "");
            group.put("modelImages", List.of(Map.of(
                "id", imageId, "name", "mi3d", "width", 256, "height", 128)));
            groups.add(group); imageRaw.put(imageId, rawId);
            mesh.bound = imageId; mesh.textureIndex = raws.size() - 1;
            commit("register " + target.value());
            return new ModelImageBinding("runtime.editor-model", modelId, 11,
                new RawImageId(rawId), new ModelImageId(imageId), Optional.of(target));
        }

        private ModelImageBinding bind(ModelImageId image, ArtMeshId target) {
            var mesh = mesh(target); var value = image.value();
            if (!imageRaw.containsKey(value)) throw new NoSuchElementException(value);
            requireBindable(mesh);
            if (mesh.bound != null) {
                if (mesh.bound.equals(value) && !corrupt) return handle(mesh, value);
                if (corrupt) { commit("corrupt bind"); mesh.bound = value; return handle(mesh, value); }
                throw new IllegalStateException("different model image");
            }
            mesh.bound = value; mesh.textureIndex = raws.size() - 1;
            commit("bind " + target.value());
            return handle(mesh, value);
        }

        private WarpDeformer warp(String id) {
            return proxy(WarpDeformer.class, (p, method, args) -> switch (method.getName()) {
                case "id" -> new DeformerId(id);
                case "toString" -> "warp(" + id + ")";
                default -> throw new AssertionError("unexpected WarpDeformer call " + method.getName());
            });
        }

        private InvocationHandler texture(Map<String, Object> row) {
            return (p, method, args) -> switch (method.getName()) {
                case "id" -> new RawImageId((String) row.get("id"));
                case "name" -> row.get("name");
                case "width" -> row.get("width");
                case "height" -> row.get("height");
                default -> throw new AssertionError("unexpected RawTexture call " + method.getName());
            };
        }

        private InvocationHandler group(Map<String, Object> row) {
            return (p, method, args) -> switch (method.getName()) {
                case "groupName" -> row.get("groupName");
                case "memo" -> row.get("memo");
                case "modelImages" -> ((List<Map<String, Object>>) row.get("modelImages")).stream()
                    .map(i -> proxy(ModelImageEntry.class, (q, m, a) -> switch (m.getName()) {
                        case "id" -> new ModelImageId((String) i.get("id"));
                        case "name" -> i.get("name");
                        case "width" -> i.get("width");
                        case "height" -> i.get("height");
                        default -> throw new AssertionError("unexpected ModelImageEntry call " + m.getName());
                    })).toList();
                default -> throw new AssertionError("unexpected ModelImageGroup call " + method.getName());
            };
        }

        private record MeshState(boolean locked, String parent, String bound, int textureIndex) {}
        private record State(List<MeshState> meshes, List<String> deformers,
                Map<String, Map<String, Object>> raws, List<Map<String, Object>> groups) {}

        private final class Mesh {
            final ArtMeshId id; boolean locked; String parent, bound; int textureIndex = -1;
            Mesh(ArtMeshId id) { this.id = id; }
            Drawable drawable() {
                return proxy(Drawable.class, (p, method, args) -> switch (method.getName()) {
                    case "id" -> id;
                    case "authoringSnapshot" -> new ArtMeshAuthoringSnapshot(id, "src-" + id.value(),
                        NativeAssertions.GEOMETRY, List.of(), List.of(new Cell("form-" + id.value(), List.of())),
                        List.of(new Form("form-" + id.value(), NativeAssertions.GEOMETRY.positions(), 500)),
                        List.of());
                    case "textureIndex" -> {
                        if (textureIndexFailures > 0) {
                            textureIndexFailures--;
                            throw new IllegalStateException(
                                "Core evaluated data is unavailable: synthetic eval join pending");
                        }
                        yield textureIndex;
                    }
                    case "setLocked" -> { locked = (Boolean) args[0]; commit("lock " + id.value()); yield null; }
                    case "locked", "lockedInHierarchy" -> locked;
                    case "setTargetDeformer" -> {
                        parent = ((Optional<DeformerId>) args[0]).map(DeformerId::value).orElse(null);
                        commit("reparent " + id.value()); yield null;
                    }
                    case "toString" -> "Mesh(" + id.value() + ")";
                    default -> throw new AssertionError("unexpected Drawable call " + method.getName());
                });
            }
        }

        private CubismModel buildModel() {
            var textures = proxy(ModelTextures.class, (p, method, args) -> switch (method.getName()) {
                case "rawImages" -> raws.values().stream()
                    .map(r -> proxy(RawTexture.class, texture(r))).toList();
                case "modelImageGroups" -> groups.stream()
                    .map(g -> proxy(ModelImageGroup.class, group(g))).toList();
                case "textureAtlases" -> List.of();
                case "registerImageAndBind" -> register((AuthorizedImageContent) args[0], (ArtMeshId) args[1]);
                case "bindModelImage" -> bind((ModelImageId) args[0], (ArtMeshId) args[1]);
                case "textureInput" -> {
                    var mesh = mesh((ArtMeshId) args[0]);
                    if (mesh.bound == null) yield Optional.empty();
                    yield Optional.of(new ArtMeshTextureInput(mesh.id, modelId, 11L,
                        ArtMeshTextureInput.Kind.MODEL_IMAGE,
                        Optional.of(new ModelImageId(mesh.bound)), Optional.empty()));
                }
                case "toString" -> "textures";
                default -> throw new AssertionError("unexpected ModelTextures call " + method.getName());
            });
            var drawables = proxy(Drawables.class, (p, method, args) -> switch (method.getName()) {
                case "all" -> meshes.stream().map(Mesh::drawable).toList();
                case "find" -> meshes.stream().filter(m -> m.id.equals(args[0])).findFirst()
                    .map(Mesh::drawable).orElseThrow(() -> new NoSuchElementException(String.valueOf(args[0])));
                case "create" -> {
                    // The host assigns its own object id, never the display name
                    // (real r9 handle: create("MI3D_N5A_1") -> id "ArtMeshMI3DN5A1").
                    var mesh = new Mesh(new ArtMeshId(
                        "ArtMesh" + ((String) args[0]).replace("_", "")));
                    meshes.add(mesh); commit("create " + args[0]);
                    yield mesh.drawable();
                }
                case "toString" -> "drawables";
                default -> throw new AssertionError("unexpected Drawables call " + method.getName());
            });
            var deformersAccess = proxy(Deformers.class, (p, method, args) -> switch (method.getName()) {
                case "all" -> deformers.stream().map(World.this::warp).map(d -> (Deformer) d).toList();
                case "find" -> deformers.stream().filter(d -> d.equals(((DeformerId) args[0]).value()))
                    .findFirst().map(World.this::warp)
                    .orElseThrow(() -> new NoSuchElementException(String.valueOf(args[0])));
                case "createWarp" -> {
                    var id = "warp-" + (++sequence); deformers.add(id); commit("warp " + args[0]);
                    yield warp(id);
                }
                case "toString" -> "deformers";
                default -> throw new AssertionError("unexpected Deformers call " + method.getName());
            });
            var parameters = proxy(Parameters.class, (p, method, args) -> switch (method.getName()) {
                case "all" -> List.of();
                default -> throw new AssertionError("unexpected Parameters call " + method.getName());
            });
            var parts = proxy(Parts.class, (p, method, args) -> switch (method.getName()) {
                case "all" -> List.of();
                default -> throw new AssertionError("unexpected Parts call " + method.getName());
            });
            var glues = proxy(Glues.class, (p, method, args) -> switch (method.getName()) {
                case "all" -> List.of();
                default -> throw new AssertionError("unexpected Glues call " + method.getName());
            });
            var canvas = proxy(Canvas.class, (p, method, args) -> switch (method.getName()) {
                case "widthPixels", "heightPixels" -> 1400f;
                case "originXPixels", "originYPixels" -> -700f;
                case "pixelsPerUnit" -> 72f;
                default -> throw new AssertionError("unexpected Canvas call " + method.getName());
            });
            return proxy(CubismModel.class, (p, method, args) -> switch (method.getName()) {
                case "id" -> modelId;
                case "drawables" -> drawables;
                case "deformers" -> deformersAccess;
                case "textures" -> textures;
                case "parameters" -> parameters;
                case "parts" -> parts;
                case "glues" -> glues;
                case "canvas" -> canvas;
                case "update" -> null;
                case "toString" -> "model";
                default -> throw new AssertionError("unexpected CubismModel call " + method.getName());
            });
        }

        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(
                ImageBindingAssertionsTest.class.getClassLoader(), new Class<?>[] {type}, handler));
        }
    }
}
