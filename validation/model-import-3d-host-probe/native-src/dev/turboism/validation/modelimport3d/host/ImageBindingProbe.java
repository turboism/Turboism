package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import dev.turboism.sdk.cubism.model.ArtMeshTextureInput;
import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.ModelImageBinding;
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/**
 * SDK-only N5a image registration/binding probe. Frozen execution order is
 * register+undo/redo -> shared bind -> no-change rebind -> rejection matrix ->
 * decode-counter observation -> restore. The probe never reads runtime or
 * Cubism classes reflectively; the private static decode counters have no
 * public channel and are honestly recorded UNAVAILABLE.
 */
final class ImageBindingProbe {
    private static final int WIDTH = 256, HEIGHT = 128;
    private static final String EVALUATION_UNAVAILABLE = "Core evaluated data is unavailable";
    private static final long TEXTURE_INDEX_POLL_MILLIS = 250;
    private final LongSupplier clock;
    private final EvaluationProbe.Sleeper sleeper;
    private final Supplier<CubismModel> current;
    private final Supplier<HistorySnapshot> history;
    private final IntConsumer restore;
    private final Map<String, Object> data;
    private final List<Map<String, Object>> checks = new ArrayList<>(), samples = new ArrayList<>();
    private final List<Map<String, Object>> setupMutations = new ArrayList<>();
    private Drawable mesh1, mesh2, mesh3, mesh4, mesh5;
    private final Map<String, Object> zeroMutations = new LinkedHashMap<>();

    private ImageBindingProbe(Supplier<CubismModel> current, Supplier<HistorySnapshot> history,
            IntConsumer restore, Map<String, Object> data, LongSupplier clock, EvaluationProbe.Sleeper sleeper) {
        this.clock = clock; this.sleeper = sleeper;
        this.current = current; this.history = history; this.restore = restore; this.data = data;
        data.put("status", "RUNNING"); data.put("checks", checks); data.put("samples", samples);
        data.put("setupMutations", setupMutations);
    }

    static void run(Supplier<CubismModel> current, Supplier<HistorySnapshot> history,
            IntConsumer restore, Map<String, Object> data) throws Exception {
        run(current, history, restore, data, System::nanoTime, Thread::sleep);
    }

    static void run(Supplier<CubismModel> current, Supplier<HistorySnapshot> history,
            IntConsumer restore, Map<String, Object> data, LongSupplier clock,
            EvaluationProbe.Sleeper sleeper) throws Exception {
        NativeAssertions.require(!SwingUtilities.isEventDispatchThread(), "N5a probe must not block EDT");
        var p = new ImageBindingProbe(current, history, restore, data, clock, sleeper);
        HistorySnapshot root = null; Map<String, Object> counts = null, canvas = null;
        Throwable primary = null;
        try {
            root = edt(history::get);
            counts = edt(() -> counts(current.get())); canvas = edt(() -> canvas(current.get()));
            data.put("initialHistory", historyData(root));
            data.put("initialCounts", counts); data.put("initialCanvas", canvas);
            // Host finding (jobs 46c9d38e/b9cc6bc0): the whole execute() must NOT hold
            // the EDT — the render loop produces the Core evaluated join only while the
            // EDT runs, so a single edt(execute()) self-starves every poll and fails the
            // current()-based setup writes. Mirror N3b: run off-EDT, dispatch each SDK
            // touch via edt, sleep off-EDT between polls.
            p.execute();
        } catch (Exception | Error failure) {
            primary = failure; data.put("status", "FAIL"); data.put("failure", failure.toString()); throw failure;
        } finally {
            if (root != null) {
                final int position = root.position();
                try {
                    edt(() -> { restore.accept(position); return null; });
                    var finalCounts = edt(() -> counts(current.get())); var finalCanvas = edt(() -> canvas(current.get()));
                    var h = edt(history::get);
                    data.put("restoredCounts", finalCounts); data.put("restoredCanvas", finalCanvas);
                    data.put("restoredHistory", historyData(h));
                    p.check("historyPositionRestored", h.position() == position);
                    p.check("finalStateClean", counts.equals(finalCounts) && canvas.equals(finalCanvas)
                        && h.position() == position);
                    data.put("finallyRestored", true);
                } catch (Exception | Error failure) {
                    data.put("status", "FAIL"); data.put("finallyRestored", false);
                    data.put("recoveryFailure", failure.toString());
                    if (primary != null) primary.addSuppressed(failure); else throw failure;
                }
            }
        }
        data.put("status", "PASS"); data.put("failure", null);
    }

    private void execute() throws Exception {
        CubismModel model = edt(current::get);
        textureCache = edt(() -> current.get().textures());
        mesh1 = createMesh("MI3D_N5A_1"); mesh2 = createMesh("MI3D_N5A_2"); mesh3 = createMesh("MI3D_N5A_3");
        mesh4 = createMesh("MI3D_N5A_4"); mesh5 = createMesh("MI3D_N5A_5");
        // Host-assigned ids are NOT the display names passed to create (r9: the host
        // returned ArtMeshMI3DN5A1 for "MI3D_N5A_1"). Record the role->native-id map
        // so the oracle resolves every expected target from real reads, never by
        // assuming a name prefix.
        var fixtureMeshes = new ArrayList<Map<String, Object>>();
        final Drawable[] meshes = {mesh1, mesh2, mesh3, mesh4, mesh5};
        for (int i = 0; i < meshes.length; i++) {
            final Drawable mesh = meshes[i];
            fixtureMeshes.add(Map.of("name", "MI3D_N5A_" + (i + 1), "id", edt(mesh::id).value()));
        }
        data.put("fixtureMeshes", fixtureMeshes);
        data.put("fixtureCounts", edt(() -> counts(model)));

        // (1) Authorized registration: image1 -> mesh1 (registration + binding in one transaction).
        var registration = sample("registration");
        var regBefore = state();
        var regMeshBefore = edt(() -> NativeAssertions.snapshot(mesh1.authoringSnapshot()));
        var regIndexBefore = textureIndex(mesh1, registration);
        registration.put("boundInputBefore", boundInput(mesh1));
        ModelImageBinding bound = edt(() -> textures().registerImageAndBind(
            AuthorizedImageContent.of(png(1), "mi3d-n5a-a.png"), mesh1.id()));
        var regAfter = state();
        registration.put("handle", handle(bound));
        registration.put("before", regBefore.get("projection")); registration.put("after", regAfter.get("projection"));
        registration.put("historyBefore", regBefore.get("history")); registration.put("historyAfter", regAfter.get("history"));
        registration.put("meshSnapshotBefore", regMeshBefore);
        registration.put("meshSnapshotAfter", edt(() -> NativeAssertions.snapshot(mesh1.authoringSnapshot())));
        registration.put("textureIndexBefore", regIndexBefore);
        registration.put("textureIndexAfter", textureIndex(mesh1, registration));
        registration.put("boundInputAfter", boundInput(mesh1));
        check("registerBindExecuted", true);
        check("handleIdentityFields", "runtime.editor-model".equals(bound.scope())
            && bound.modelId().value().equals(edt(() -> model.id().value())) && bound.modelGeneration() > 0
            && !bound.rawImageId().value().isBlank() && !bound.modelImageId().value().isBlank()
            && bound.artMeshId().orElseThrow().equals(edt(mesh1::id)));
        check("registrationProjection", registered(regBefore.get("projection"), regAfter.get("projection"), bound));
        check("boundInputRegistered", isBoundImage(registration.get("boundInputAfter"), bound.modelImageId(), mesh1)
            && ((Map<?, ?>) registration.get("boundInputBefore")).get("bound").equals(false));

        // (5) Undo/Redo legs run while the registration transaction is the stack top.
        var undo = sample("undo"); var redo = sample("redo");
        edt(() -> { restore.accept(((HistorySnapshot) regBefore.get("snapshot")).position()); return null; });
        undo.put("history", historyData(edt(history::get))); undo.put("projection", edt(this::projection));
        undo.put("meshSnapshot", edt(() -> NativeAssertions.snapshot(mesh1.authoringSnapshot())));
        undo.put("textureIndex", textureIndex(mesh1, undo));
        undo.put("boundInput", boundInput(mesh1));
        edt(() -> { restore.accept(((HistorySnapshot) regAfter.get("snapshot")).position()); return null; });
        redo.put("history", historyData(edt(history::get))); redo.put("projection", edt(this::projection));
        redo.put("meshSnapshot", edt(() -> NativeAssertions.snapshot(mesh1.authoringSnapshot())));
        redo.put("textureIndex", textureIndex(mesh1, redo));
        redo.put("boundInput", boundInput(mesh1));
        check("undoRestoresProjection", undo.get("projection").equals(regBefore.get("projection"))
            && undo.get("meshSnapshot").equals(regMeshBefore) && undo.get("textureIndex").equals(regIndexBefore));
        check("redoRestoresProjection", redo.get("projection").equals(regAfter.get("projection")));
        check("handleFieldsStableAcrossRedo", projectionContains(redo.get("projection"), bound));
        check("meshSnapshotUnchanged", regMeshBefore.equals(registration.get("meshSnapshotAfter"))
            && regMeshBefore.equals(undo.get("meshSnapshot")) && regMeshBefore.equals(redo.get("meshSnapshot")));
        check("boundInputUndoUnbound", ((Map<?, ?>) undo.get("boundInput")).get("bound").equals(false));
        check("boundInputRedoSameImage", isBoundImage(redo.get("boundInput"), bound.modelImageId(), mesh1));

        // (2) Rebinding image1 to mesh1 is the no-change case; binding it to mesh2 shares the image.
        var rebind = sample("rebind");
        var rebindBefore = state();
        rebind.put("boundInputBefore", boundInput(mesh1));
        ModelImageBinding rebound = edt(() -> textures().bindModelImage(bound.modelImageId(), mesh1.id()));
        var rebindAfter = state();
        rebind.put("boundInputAfter", boundInput(mesh1));
        rebind.put("handle", handle(rebound));
        rebind.put("before", rebindBefore.get("projection")); rebind.put("after", rebindAfter.get("projection"));
        rebind.put("historyBefore", rebindBefore.get("history")); rebind.put("historyAfter", rebindAfter.get("history"));
        check("bindingHandleArtMesh", rebound.artMeshId().orElseThrow().equals(edt(mesh1::id))
            && rebound.modelImageId().equals(bound.modelImageId()) && rebound.rawImageId().equals(bound.rawImageId()));
        check("rebindNoChange", rebindBefore.equals(rebindAfter));
        check("boundInputRebindUnchanged", rebind.get("boundInputBefore").equals(rebind.get("boundInputAfter"))
            && isBoundImage(rebind.get("boundInputAfter"), bound.modelImageId(), mesh1));
        var share = sample("share");
        var shareMeshBefore = edt(() -> NativeAssertions.snapshot(mesh2.authoringSnapshot()));
        var shareIndexBefore = textureIndex(mesh2, share);
        var shareHistoryBefore = edt(history::get);
        share.put("boundInputBefore", boundInput(mesh2));
        ModelImageBinding shared = edt(() -> textures().bindModelImage(bound.modelImageId(), mesh2.id()));
        share.put("boundInputAfter", boundInput(mesh2));
        share.put("handle", handle(shared));
        share.put("meshSnapshotBefore", shareMeshBefore);
        share.put("meshSnapshotAfter", edt(() -> NativeAssertions.snapshot(mesh2.authoringSnapshot())));
        share.put("textureIndexBefore", shareIndexBefore);
        share.put("textureIndexAfter", textureIndex(mesh2, share));
        share.put("historyBefore", historyData(shareHistoryBefore)); share.put("historyAfter", historyData(edt(history::get)));
        check("shareSecondMeshBound", shared.artMeshId().orElseThrow().equals(edt(mesh2::id))
            && shared.modelImageId().equals(bound.modelImageId()) && shareMeshBefore.equals(share.get("meshSnapshotAfter")));
        check("boundInputShareSameImage", isBoundImage(share.get("boundInputAfter"), bound.modelImageId(), mesh2)
            && ((Map<?, ?>) share.get("boundInputBefore")).get("bound").equals(false));

        // (4) Rejection matrix; setup mutations are booked separately from the tested calls.
        var rejected = new LinkedHashMap<String, Object>();
        var second = new ModelImageBinding[1];
        setup("registerSecondImage", () -> second[0] = textures().registerImageAndBind(
            AuthorizedImageContent.of(png(2), "mi3d-n5a-b.png"), mesh3.id()));
        var differentImage = reject("differentImage",
            () -> edt(() -> textures().bindModelImage(second[0].modelImageId(), mesh1.id())), rejected, mesh1);
        differentImage.put("otherMeshBoundInput", boundInput(mesh3));
        check("boundInputSecondImage", isBoundImage(differentImage.get("otherMeshBoundInput"),
            second[0].modelImageId(), mesh3)
            && !second[0].modelImageId().equals(bound.modelImageId()));
        setup("lockMesh4", () -> mesh4.setLocked(true));
        reject("locked",
            () -> edt(() -> textures().bindModelImage(bound.modelImageId(), mesh4.id())), rejected, mesh4);
        var warp = new WarpDeformer[1];
        setup("createWarp", () -> warp[0] = model.deformers().createWarp("MI3D_N5A_WARP", null, -1, 2, 2));
        setup("reparentMesh5", () -> mesh5.setTargetDeformer(Optional.of(warp[0].id())));
        reject("deformerParent",
            () -> edt(() -> textures().bindModelImage(bound.modelImageId(), mesh5.id())), rejected, mesh5);
        reject("oversizePng", () -> AuthorizedImageContent.of(
            new byte[AuthorizedImageContent.MAX_PNG_BYTES + 1], "mi3d-n5a-oversize.png"), rejected, null);
        reject("undersizePng", () -> AuthorizedImageContent.of(
            png(64, 64, 3), "mi3d-n5a-undersize.png"), rejected, null);
        check("differentImageRejected", "java.lang.IllegalStateException".equals(rejected.get("differentImage")));
        check("lockedTargetRejected", "java.lang.IllegalStateException".equals(rejected.get("locked")));
        check("deformerParentRejected", "java.lang.IllegalStateException".equals(rejected.get("deformerParent")));
        check("oversizePngRejected", "java.lang.IllegalArgumentException".equals(rejected.get("oversizePng")));
        check("undersizePngRejected", "java.lang.IllegalArgumentException".equals(rejected.get("undersizePng")));
        check("rejectionsZeroMutation", zeroMutations.values().stream()
            .allMatch(v -> ((List<?>) v).isEmpty()));
        check("boundInputRejectionsUnchanged", samples.stream()
            .filter(s -> s.get("kind").equals("rejection"))
            .filter(s -> s.containsKey("boundInputBefore"))
            .allMatch(s -> s.get("boundInputBefore").equals(s.get("boundInputAfter"))));

        // (6) Decode counters: no public SDK channel exists; record UNAVAILABLE honestly.
        var counters = sample("decodeCounters");
        counters.put("resolution", "UNAVAILABLE");
        counters.put("reason", "private static CImageResource decode counters have no public SDK channel; "
            + "decode-once is carried by offline fixture evidence only");
        counters.put("failure", null);
        check("decodeCountersUnavailable", "UNAVAILABLE".equals(counters.get("resolution"))
            && !((String) counters.get("reason")).isBlank());
    }

    private Drawable createMesh(final String name) throws Exception {
        var before = edt(history::get);
        var mesh = edt(() -> current.get().drawables().create(name, null, -1, NativeAssertions.GEOMETRY));
        var mutation = new LinkedHashMap<String, Object>();
        mutation.put("id", "create" + name); mutation.put("historyBefore", historyData(before));
        mutation.put("historyAfter", historyData(edt(history::get)));
        setupMutations.add(mutation);
        return mesh;
    }

    // Host finding (job 46c9d38e): Core evaluated data joins asynchronously per model
    // generation, so textureIndex() may fail-closed until the render loop produces it.
    // Bounded availability-aware poll: success -> Integer; timeout -> "UNAVAILABLE"
    // with the exception message recorded as the sample's reason. Other failures
    // propagate; never fabricate a value.
    private Object textureIndex(Drawable mesh, Map<String, Object> sample) throws Exception {
        long deadline = clock.getAsLong() + EvaluationProbe.WAIT_MILLIS * 1_000_000L;
        while (true) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException("N5a textureIndex read interrupted");
            try {
                return edt(mesh::textureIndex);
            } catch (java.util.concurrent.ExecutionException dispatched) {
                // The EDT-dispatched read failed: unwrap the task failure so the
                // availability-aware poll below sees the original exception shape.
                Throwable cause = dispatched.getCause();
                if (!(cause instanceof IllegalStateException failure)) throw rethrow(cause);
                String message = String.valueOf(failure.getMessage());
                if (!message.startsWith(EVALUATION_UNAVAILABLE)) throw failure;
                if (clock.getAsLong() >= deadline) {
                    sample.put("reason", message);
                    return "UNAVAILABLE";
                }
                // Sleep OFF the EDT so the render loop can produce the join.
                sleeper.sleep(TEXTURE_INDEX_POLL_MILLIS);
            }
        }
    }

    private void setup(String id, Runnable mutation) throws Exception {
        var before = edt(history::get);
        edt(() -> { mutation.run(); return null; });
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id); row.put("historyBefore", historyData(before));
        row.put("historyAfter", historyData(edt(history::get)));
        setupMutations.add(row);
    }

    @FunctionalInterface
    private interface ThrowingAction { void run() throws Exception; }

    private Map<String, Object> reject(String name, ThrowingAction action,
            Map<String, Object> rejected, Drawable target) throws Exception {
        var row = sample("rejection"); row.put("id", name);
        var before = state();
        if (target != null) row.put("boundInputBefore", boundInput(target));
        String actual = "NOT_REJECTED";
        try {
            action.run();
        } catch (java.util.concurrent.ExecutionException dispatched) {
            // The EDT-dispatched rejection surfaces wrapped; the cause is the
            // actual rejection type under observation.
            Throwable cause = dispatched.getCause();
            actual = cause == null ? dispatched.getClass().getName() : cause.getClass().getName();
        } catch (RuntimeException error) { actual = error.getClass().getName(); }
        var after = state();
        if (target != null) row.put("boundInputAfter", boundInput(target));
        row.put("exception", actual); row.put("before", before.get("projection")); row.put("after", after.get("projection"));
        row.put("historyBefore", before.get("history")); row.put("historyAfter", after.get("history"));
        rejected.put(name, actual);
        zeroMutations.put(name, before.equals(after) ? List.of() : List.of("mutated"));
        return row;
    }

    /**
     * Observes the target's actual native texture input via
     * {@code ModelTextures.textureInput} — never copied from a binding receipt.
     * A genuine unbound extension reports {@code bound:false}; a failed read
     * reports {@code bound:"ERROR"} with the exception type, never a value.
     * {@code artMeshId} always carries the observed target identity (the request
     * only when nothing was observed); {@code requestedArtMeshId} keeps the call
     * context so a wrong-target response is detected, never overwritten.
     */
    private Map<String, Object> boundInput(Drawable mesh) throws Exception {
        final ArtMeshId requested = edt(mesh::id);
        var row = new LinkedHashMap<String, Object>();
        row.put("requestedArtMeshId", requested.value());
        final Optional<ArtMeshTextureInput> observed;
        try {
            observed = edt(() -> textures().textureInput(requested));
        } catch (java.util.concurrent.ExecutionException dispatched) {
            Throwable cause = dispatched.getCause();
            row.put("artMeshId", requested.value());
            row.put("bound", "ERROR");
            row.put("exception", cause == null
                ? dispatched.getClass().getName() : cause.getClass().getName());
            return row;
        }
        if (observed.isEmpty()) {
            row.put("artMeshId", requested.value());
            row.put("bound", false);
            return row;
        }
        var input = observed.get();
        row.put("artMeshId", input.artMeshId().value());
        row.put("bound", true); row.put("kind", input.kind().name());
        row.put("modelId", input.modelId().value());
        row.put("modelGeneration", input.modelGeneration());
        row.put("modelImageId", input.modelImageId().map(ModelImageId::value).orElse(null));
        row.put("textureAtlasId", input.textureAtlasId().map(TextureAtlasId::value).orElse(null));
        return row;
    }

    private boolean isBoundImage(Object observation, ModelImageId image,
            Drawable mesh) throws Exception {
        var row = (Map<?, ?>) observation;
        return row.get("bound").equals(true) && "MODEL_IMAGE".equals(row.get("kind"))
            && image.value().equals(row.get("modelImageId"))
            && row.get("textureAtlasId") == null
            && edt(mesh::id).value().equals(row.get("artMeshId"))
            && edt(() -> current.get().id()).value().equals(row.get("modelId"))
            && row.get("modelGeneration") instanceof Long generation && generation > 0;
    }

    private Map<String, Object> sample(String kind) {
        var row = new LinkedHashMap<String, Object>(); row.put("kind", kind); samples.add(row); return row;
    }

    private ModelTextures textureCache;

    private ModelTextures textures() { return textureCache; }

    private Map<String, Object> state() throws Exception {
        var row = new LinkedHashMap<String, Object>();
        var h = edt(history::get);
        row.put("projection", edt(this::projection)); row.put("history", historyData(h)); row.put("snapshot", h);
        return row;
    }

    private Map<String, Object> projection() {
        var textures = textures();
        var row = new LinkedHashMap<String, Object>();
        row.put("rawImages", textures.rawImages().stream().map(t -> fields(
            "id", t.id().value(), "name", t.name(), "width", t.width(), "height", t.height())).toList());
        row.put("modelImageGroups", textures.modelImageGroups().stream().map(g -> fields(
            "groupName", g.groupName(), "memo", g.memo(),
            "modelImages", g.modelImages().stream().map(i -> fields(
                "id", i.id().value(), "name", i.name(), "width", i.width(), "height", i.height())).toList())).toList());
        row.put("textureAtlases", textures.textureAtlases().stream().map(a -> fields(
            "id", a.id().value(), "name", a.name(), "width", a.width(), "height", a.height(),
            "atlasVersion", a.atlasVersion(), "modelImageCount", a.modelImageCount())).toList());
        return row;
    }

    private static Map<String, Object> fields(Object... pairs) {
        var row = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) row.put((String) pairs[i], pairs[i + 1]);
        return row;
    }

    private static boolean registered(Object before, Object after, ModelImageBinding bound) {
        var b = (Map<?, ?>) before; var a = (Map<?, ?>) after;
        var raws = (List<?>) a.get("rawImages"); var previous = (List<?>) b.get("rawImages");
        if (raws.size() != previous.size() + 1) return false;
        var added = raws.stream().filter(r -> !previous.contains(r)).toList();
        if (added.size() != 1) return false;
        var raw = (Map<?, ?>) added.get(0);
        if (!bound.rawImageId().value().equals(raw.get("id"))
            || !Integer.valueOf(WIDTH).equals(raw.get("width")) || !Integer.valueOf(HEIGHT).equals(raw.get("height"))) return false;
        var groups = (List<?>) a.get("modelImageGroups");
        var matching = groups.stream().flatMap(g -> ((List<?>) ((Map<?, ?>) g).get("modelImages")).stream())
            .filter(i -> bound.modelImageId().value().equals(((Map<?, ?>) i).get("id"))).toList();
        if (matching.size() != 1) return false;
        var entry = (Map<?, ?>) matching.get(0);
        return Integer.valueOf(WIDTH).equals(entry.get("width")) && Integer.valueOf(HEIGHT).equals(entry.get("height"))
            && a.get("textureAtlases").equals(b.get("textureAtlases"));
    }

    private static boolean projectionContains(Object projection, ModelImageBinding bound) {
        var p = (Map<?, ?>) projection;
        var raw = ((List<?>) p.get("rawImages")).stream()
            .anyMatch(r -> bound.rawImageId().value().equals(((Map<?, ?>) r).get("id")));
        var image = ((List<?>) p.get("modelImageGroups")).stream()
            .flatMap(g -> ((List<?>) ((Map<?, ?>) g).get("modelImages")).stream())
            .anyMatch(i -> bound.modelImageId().value().equals(((Map<?, ?>) i).get("id")));
        return raw && image;
    }

    private static Map<String, Object> handle(ModelImageBinding b) {
        var row = new LinkedHashMap<String, Object>();
        row.put("scope", b.scope()); row.put("modelId", b.modelId().value());
        row.put("modelGeneration", b.modelGeneration()); row.put("rawImageId", b.rawImageId().value());
        row.put("modelImageId", b.modelImageId().value());
        row.put("artMeshId", b.artMeshId().map(ArtMeshId::value).orElse(null));
        return row;
    }

    private static byte[] png(int variant) { return png(WIDTH, HEIGHT, variant); }

    /** Deterministic合法 PNG bytes with an asymmetric marker; two variants are mutually distinct. */
    private static byte[] png(int width, int height, int variant) {
        try {
            var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) {
                image.setRGB(x, y, 0x00000000);
            }
            if (width == WIDTH && height == HEIGHT) {
                if (variant == 1) {
                    for (int i = 0; i < 24; i++) image.setRGB(12 + i, 16 + i, 0xFF2040FF);
                    image.setRGB(200, 32, 0xFFFF2020); image.setRGB(201, 32, 0xFFFF2020);
                } else {
                    for (int i = 0; i < 24; i++) image.setRGB(232 - i, 96 + (i % 5), 0xFF20FF40);
                    image.setRGB(40, 100, 0xFFFFFF20); image.setRGB(40, 101, 0xFFFFFF20);
                }
            }
            var out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private void check(String id, boolean actual) {
        NativeAssertions.require(checks.stream().noneMatch(c -> id.equals(c.get("id"))), "duplicate N5a check");
        checks.add(Map.of("id", id, "actual", actual, "status", actual ? "PASS" : "FAIL",
            "evidenceKind", "EXACT_HOST"));
        NativeAssertions.require(actual, "N5a " + id);
    }

    private static Map<String, Object> counts(CubismModel m) {
        return Map.of("meshes", m.drawables().all().size(), "deformers", m.deformers().all().size(),
            "parameters", m.parameters().all().size(), "parts", m.parts().all().size());
    }

    private static Map<String, Object> canvas(CubismModel m) {
        var c = m.canvas();
        return Map.of("width", c.widthPixels(), "height", c.heightPixels(),
            "originX", c.originXPixels(), "originY", c.originYPixels(), "pixelsPerUnit", c.pixelsPerUnit());
    }

    private static Map<String, Object> historyData(HistorySnapshot h) {
        return Map.of("availability", h.availability().name(), "generation", h.generation(), "revision", h.revision(),
            "position", h.position(), "documentBindingId", h.documentBindingId(), "managerBindingId", h.managerBindingId(),
            "entries", h.entries().stream().map(Object::toString).toList(),
            "entryIds", h.entries().stream().map(e -> e.entryId().map(i -> i.value()).orElse(null)).toList());
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        var task = new FutureTask<T>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }

    private static RuntimeException rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new IllegalStateException("N5a EDT read failed", failure);
    }
}
