package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.command.EditorCanvasSettingsRequest;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.command.EditorFileCommand;
import dev.turboism.sdk.cubism.command.EditorFileCommandRequest;
import dev.turboism.sdk.cubism.command.EditorOverwritePolicy;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.ArtMeshKeyform;
import dev.turboism.sdk.cubism.model.ArtMeshTextureInput;
import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismRenderedPixels;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import dev.turboism.sdk.ui.UserFileAccessService;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileRequest;
import dev.turboism.sdk.ui.UserFileRequestStatus;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * T008/SC-003: native pixel sampling of one fixed 256x128 OPAQUE direction-marked
 * plane through {@code CubismModel.renderPixels(1024,1024)}. The material recipe,
 * output bounds {@code [256,384,768,640]}, fifteen 5x5 interior patches, the
 * {@code (2,2,2,0)} channel tolerance, the +-80px parameter shift and the
 * baseline/Undo/Redo contracts are all frozen literals — nothing is fitted from
 * rendered output, and no frame is searched, cropped or rescaled. Every native
 * frame is preserved as a PNG artifact whose SHA-256, dimensions and pose are
 * linked into this section for independent offline re-verification.
 */
final class PixelSamplingProbe {

    private static final int OUT = 1024;
    private static final int SRC_W = 256, SRC_H = 128;
    private static final int BX = 256, BY = 384;          // plane bounds in output pixels
    private static final int SHIFT = 80;
    private static final int PATCH = 2;                    // 5x5 patches, radius 2
    private static final int TOL = 2;                      // per-channel RGB tolerance
    private static final ParameterId PARAM = new ParameterId("ParamMI3DPixShift");

    /** Frozen interior samples: name, logical source x/y, expected RGB (alpha pinned 255). */
    private static final Object[][] SAMPLES = {
        {"TL-near-corner", 16, 16, 226, 59, 59},
        {"TL-right", 104, 32, 226, 59, 59},
        {"TL-above-marker", 44, 12, 226, 59, 59},
        {"TL-below-marker", 44, 52, 226, 59, 59},
        {"marker-shaft", 40, 30, 16, 16, 16},
        {"marker-head", 72, 30, 16, 16, 16},
        {"TR-near-corner", 240, 16, 59, 178, 90},
        {"TR-center", 192, 32, 59, 178, 90},
        {"TR-inner", 148, 48, 59, 178, 90},
        {"BL-near-corner", 16, 112, 59, 92, 226},
        {"BL-center", 64, 96, 59, 92, 226},
        {"BL-inner", 108, 80, 59, 92, 226},
        {"BR-near-corner", 240, 112, 240, 200, 50},
        {"BR-center", 192, 96, 240, 200, 50},
        {"BR-inner", 148, 80, 240, 200, 50},
    };

    /** Frozen outside-mesh probes: output x/y; alpha must be exactly zero. */
    private static final int[][] OUTSIDE = {
        {244, 448}, {780, 448}, {384, 372}, {384, 652}, {128, 128}
    };

    /** Principled bound: 1024x1024 RGBA cannot exceed ~4.2 MiB encoded. */
    private static final int MAX_ARTIFACT_BYTES = 8 * 1024 * 1024;
    /** T014: this micro-fixture CMO3's transport budget — not a model limit. */
    private static final int MAX_CMO3_BYTES = 32 * 1024 * 1024;
    private static final String SAVE_REQUEST_ID = "mi3d-t014-persistence-save";
    private static final String SAVE_FILE = "mi3d-t014-model.cmo3";

    private final Supplier<CubismModel> current;
    private final Supplier<HistorySnapshot> history;
    private final IntConsumer restore;
    private final AuthoringTransactionService transactions;
    private final Path artifacts;
    private final Map<String, Object> data;
    private final List<Map<String, Object>> checks = new ArrayList<>();
    private final List<Map<String, Object>> frames = new ArrayList<>();
    private CubismModel modelRef;
    private Drawable meshRef;
    private CubismRenderedPixels boundPixels;

    static void run(Supplier<CubismModel> current, Supplier<HistorySnapshot> history,
            IntConsumer restore, EditorCommandService commands,
            AuthoringTransactionService transactions, UserFileAccessService userFiles,
            Path stateDir, Map<String, Object> data, Map<String, Object> save) throws Exception {
        NativeAssertions.require(!SwingUtilities.isEventDispatchThread(), "T008 probe must not block EDT");
        var p = new PixelSamplingProbe(current, history, restore, transactions,
            stateDir.resolve("pixel-sampling"), data);
        HistorySnapshot root = null; Map<String, Object> counts = null, canvas = null;
        Throwable primary = null;
        try {
            root = edt(history::get);
            counts = edt(() -> counts(current.get())); canvas = edt(() -> canvas(current.get()));
            data.put("initialHistory", historyData(root));
            data.put("initialCounts", counts); data.put("initialCanvas", canvas);
            p.setup(commands);
            p.execute();
            // T014 save hook: after rebound@0 with mesh/image/parameter/keyforms
            // still alive, BEFORE the finally leg restores the root position.
            // The saved file is never written again and Undo never touches the
            // seed document.
            if (save != null) p.saveSection(commands, userFiles, stateDir, save);
        } catch (Exception | Error failure) {
            primary = failure; data.put("status", "FAIL"); data.put("failure", failure.toString()); throw failure;
        } finally {
            if (root != null) {
                final int position = root.position();
                try {
                    edt(() -> { restore.accept(position); return null; });
                    var h = edt(history::get);
                    data.put("restoredHistory", historyData(h));
                    var restoredCounts = edt(() -> counts(current.get()));
                    var restoredCanvas = edt(() -> canvas(current.get()));
                    data.put("restoredCounts", restoredCounts);
                    data.put("restoredCanvas", restoredCanvas);
                    p.check("historyPositionRestored", h.position() == position,
                        "history returns to the pre-fixture position");
                    p.check("restoredObjectsAndCanvas",
                        restoredCounts.equals(counts) && restoredCanvas.equals(canvas),
                        "native Undo restores initial object counts and canvas");
                    // Whole-leg recovery: every created object is gone, so the
                    // native frame must be entirely transparent — a separate
                    // proof from baseline equality.
                    var blank = p.capture("blank", p.modelRef, null);
                    p.check("blankFrameAfterRestore", allTransparent(blank),
                        "fully restored model renders an all-alpha-zero frame");
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

    private PixelSamplingProbe(Supplier<CubismModel> current, Supplier<HistorySnapshot> history,
            IntConsumer restore, AuthoringTransactionService transactions,
            Path artifacts, Map<String, Object> data) {
        this.current = current; this.history = history; this.restore = restore;
        this.transactions = transactions; this.artifacts = artifacts; this.data = data;
        data.put("checks", checks); data.put("frames", frames);
        data.put("coordinateSpace", "NATIVE_1024_OUTPUT_ARGB");
        data.put("instanceObservation",
            "SDK_RENDER_TYPE_ORDER_ONLY (native instance identity is not observable via the public SDK)");
        data.put("planeBounds", List.of(BX, BY, BX + 2 * SRC_W, BY + 2 * SRC_H));
        data.put("channelTolerance", List.of(TOL, TOL, TOL, 0));
        data.put("patchRadius", PATCH);
    }

    /**
     * The frozen 1024x1024 canvas must exist BEFORE any T008 geometry is
     * created — the native renderer fits model space into the model canvas, so
     * a 1000x2000 canvas produced the shrunken/misplaced bound frame. The
     * command is the EvaluationProbe-proven standalone native edit on the EDT;
     * it commits its own history entry, so this leg's final Undo restores the
     * initial canvas.
     */
    private void setup(EditorCommandService commands) throws Exception {
        edt(() -> {
            current.get();
            var result = commands.execute(new EditorCanvasSettingsRequest(OUT, OUT));
            data.put("canvasCommand", result.status().name());
            data.put("setupCanvas", canvas(current.get()));
            check("canvasEstablished", result.status() == EditorCommandResult.Status.EXECUTED
                && current.get().canvas().widthPixels() == OUT
                && current.get().canvas().heightPixels() == OUT,
                "1024x1024 canvas established before T008 fixture geometry");
            return null;
        });
    }

    private void execute() throws Exception {
        CubismModel model = edt(current::get);
        this.modelRef = model;

        // (1) Fixture: one shift parameter, one axis-aligned rectangle mesh
        //     covering the frozen canvas rect, keys -1/0/+1 bound. Parameter
        //     creation, mesh creation and binding are standalone native edits
        //     on EDT (the B0-proven path) — no single-transaction atomicity is
        //     claimed for this setup.
        final Drawable[] created = new Drawable[1];
        edt(() -> {
            model.parameters().createMany(List.of(new ParameterDefinition(
                PARAM, "MI3D pixel shift", -1f, 0f, 1f,
                dev.turboism.sdk.cubism.model.ParameterType.NORMAL, false)));
            created[0] = model.drawables().create("MI3D_PIX_PLANE", null, -1, geometry());
            model.parameterBindings(PARAM).bind(
                ParameterBindingTarget.artMesh(created[0].id()),
                List.of(new ParameterBindingPoint(
                    new dev.turboism.sdk.cubism.id.ParameterBindingPointId("P_NEG"), -1f),
                    new ParameterBindingPoint(
                        new dev.turboism.sdk.cubism.id.ParameterBindingPointId("P_ZERO"), 0f),
                    new ParameterBindingPoint(
                        new dev.turboism.sdk.cubism.id.ParameterBindingPointId("P_POS"), 1f)));
            return null;
        });
        check("fixtureCommitted", created[0] != null
                && edt(() -> model.parameters().find(PARAM)) != null,
            "parameter, mesh and key bindings materialize via standalone native setup");
        var mesh = created[0];
        this.meshRef = mesh;
        var axis = axis(edt(mesh::authoringSnapshot));
        check("shiftKeysMaterialized", axis != null, "bound axis exposes -1/0/+1 keys");

        var neg = List.of(new ArtMeshAuthoringSnapshot.Coordinate(axis, -1f));
        var pos = List.of(new ArtMeshAuthoringSnapshot.Coordinate(axis, 1f));
        var keyformTx = edt(() -> transactions.execute(
            AuthoringTransactionOptions.of("MI3D T008 shift keyforms"), () -> {
                keyform(mesh, neg).setPositions(shifted(-SHIFT));
                keyform(mesh, pos).setPositions(shifted(SHIFT));
                return null;
            }));
        check("keyformsCommitted", keyformTx.outcome() == AuthoringTransactionOutcome.COMMITTED,
            "-80px/+80px keyform writes commit");

        // (2) Baseline BEFORE binding: an unbound mesh's native frame. Undo of the
        //     binding must reproduce this frame exactly — it is not assumed blank.
        var baseline = capture("baseline", model, mesh);
        check("baselineCaptured", baseline.argb().length == OUT * OUT,
            "unbound baseline frame captured at output size");

        // (3) Bind the fixed material and render the bound frame at param 0.
        int bindBefore = edt(history::get).position();
        var binding = edt(() -> model.textures().registerImageAndBind(
            AuthorizedImageContent.of(materialPng(), "mi3d-t008-material.png"),
            mesh.id()));
        int bindAfter = edt(history::get).position();
        check("bindExecuted", binding != null, "256x128 material registers and binds");
        var bound0 = capture("bound-p0", model, mesh);
        this.boundPixels = bound0;
        check("boundPatchesP0", patchFailures(bound0, 0).isEmpty()
                && outsideAlphaZero(bound0, 0),
            "all 15 interior patches + outside alpha at frozen coordinates (param 0)");
        check("boundDiffersFromBaseline", !Arrays.equals(baseline.argb(), bound0.argb()),
            "bound frame is non-trivially different from the unbound baseline");
        check("boundInputTyped", typedImage(mesh, binding.modelImageId().value()),
            "textureInput reports the bound model image for this mesh");

        // (4) Parameter-current proof: +1 and -1 poses sample their own keyforms,
        //     never the default form and never a recentered frame.
        edt(() -> { param(model).setValue(1f); return null; });
        var boundPos = capture("bound-p1", model, mesh);
        check("boundPatchesPos", patchFailures(boundPos, SHIFT).isEmpty()
                && outsideAlphaZero(boundPos, SHIFT),
            "the +1 frame matches the +80px-shifted frozen patches");
        edt(() -> { param(model).setValue(-1f); return null; });
        var boundNeg = capture("bound-m1", model, mesh);
        check("boundPatchesNeg", patchFailures(boundNeg, -SHIFT).isEmpty()
                && outsideAlphaZero(boundNeg, -SHIFT),
            "the -1 frame matches the -80px-shifted frozen patches");
        edt(() -> { param(model).setValue(0f); return null; });
        // Oracle negatives: the oracle must reject a stale/wrong-pose frame.
        check("oracleRejectsStaleShift", !patchFailures(bound0, SHIFT).isEmpty(),
            "param-0 frame is rejected at the +80px expectation");
        check("oracleRejectsShiftedAtZero", !patchFailures(boundPos, 0).isEmpty(),
            "+1 frame is rejected at the unshifted expectation");

        // (5) Undo must reproduce the captured baseline exactly; Redo must
        //     reproduce the bound param-0 frame exactly.
        edt(() -> { restore.accept(bindBefore); return null; });
        var unbound = capture("unbound", model, mesh);
        check("unboundEqualsBaseline", Arrays.equals(baseline.argb(), unbound.argb()),
            "Undo of the binding renders the exact pre-bind baseline");
        check("unboundInputEmpty", edt(() -> model.textures()
                .textureInput(mesh.id())).isEmpty(),
            "Undo leaves no typed texture input on this mesh");
        edt(() -> { restore.accept(bindAfter); return null; });
        var rebound = capture("rebound", model, mesh);
        check("reboundEqualsBound", Arrays.equals(bound0.argb(), rebound.argb()),
            "Redo renders the exact bound param-0 frame");
        check("reboundInputTyped", typedImage(mesh, binding.modelImageId().value()),
            "Redo restores the same model image identity");
    }

    /**
     * T014 Job A save hook — runs with the live fixture still bound (rebound@0
     * completed) and BEFORE the finally leg restores the root history position.
     * The grant request completes off-EDT; the native SAVE_AS itself executes
     * on the EDT through the typed command service. Only the fixed task path
     * {@code <stateDir>/persistence/mi3d-t014-model.cmo3} is acceptable.
     */
    private void saveSection(EditorCommandService commands, UserFileAccessService userFiles,
            Path stateDir, Map<String, Object> save) throws Exception {
        save.put("schemaVersion", "mi3d-t014-save-v1");
        save.put("status", "FAIL"); save.put("failure", null);
        save.put("checks", new ArrayList<Map<String, Object>>());
        save.put("process", processIdentity());
        var manifestBefore = edt(() -> manifest(modelRef));
        save.put("manifestBefore", manifestBefore);
        var dir = stateDir.resolve("persistence");
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            NativeAssertions.require(Files.isDirectory(dir) && !Files.isSymbolicLink(dir),
                "persistence artifact directory is not a real directory");
        } else {
            Files.createDirectories(dir);
        }
        var target = dir.resolve(SAVE_FILE);
        NativeAssertions.require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS),
            "refusing to overwrite an existing persistence artifact");
        // Async grant boundary is honored off-EDT; the grant source completes
        // synchronously for this pinned request, never needing the EDT.
        var result = userFiles.request(new UserFileRequest(SAVE_REQUEST_ID,
            "MI3D T014 persistence save", List.of("cmo3"),
            UserFileMode.WRITE, UserFileLifetime.ONE_OPERATION))
            .toCompletableFuture().get(60, java.util.concurrent.TimeUnit.SECONDS);
        save.put("requestStatus", result.status().name());
        var handle = result.handle().orElse(null);
        sectionCheck(save, "saveGrantAcquired",
            result.status() == UserFileRequestStatus.GRANTED && handle != null,
            "exact pinned WRITE/ONE_OPERATION cmo3 grant acquired");
        var command = edt(() -> commands.execute(new EditorFileCommandRequest(
            EditorFileCommand.SAVE_AS, handle, EditorOverwritePolicy.REJECT_EXISTING)));
        try { handle.close(); } catch (Exception ignored) { }
        save.put("commandStatus", command.status().name());
        sectionCheck(save, "saveCommandExecuted",
            command.status() == EditorCommandResult.Status.EXECUTED,
            "native SAVE_AS executed to the fixed task path");
        // Validate the real file before any read: regular file, no symlink
        // component on the target, declared size inside the micro budget —
        // then a bounded NOFOLLOW channel read that rejects mid-read growth.
        NativeAssertions.require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
            && !Files.isSymbolicLink(target), "saved artifact is not a real regular file");
        long declared = Files.size(target);
        NativeAssertions.require(declared > 0 && declared <= MAX_CMO3_BYTES,
            "saved CMO3 out of the 32MiB micro-fixture budget");
        byte[] bytes;
        try (var channel = Files.newByteChannel(target,
                java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = java.nio.ByteBuffer.allocate(8192);
            var out = new ByteArrayOutputStream((int) declared);
            long total = 0;
            while (true) {
                buffer.clear();
                int n = channel.read(buffer);
                if (n < 0) break;
                total += n;
                NativeAssertions.require(total <= declared,
                    "saved CMO3 grew past its declared size during the bounded read");
                out.write(buffer.array(), 0, n);
            }
            bytes = out.toByteArray();
        }
        NativeAssertions.require(bytes.length == declared && Files.size(target) == declared,
            "saved CMO3 size changed across the read");
        sectionCheck(save, "savedFileValid", bytes.length > 0,
            "new regular CMO3 read back within the declared size");
        save.put("artifact", "persistence/" + SAVE_FILE);
        save.put("artifactBytes", bytes.length);
        save.put("artifactSha256", sha256(bytes));
        var manifestAfter = edt(() -> manifest(modelRef));
        save.put("manifestAfter", manifestAfter);
        sectionCheck(save, "saveUnchanged", manifestBefore.equals(manifestAfter),
            "SAVE_AS changed no authoring/resource/parameter tuple");
        // Post-save re-sample at param 0: a separately named bounded PNG proves
        // the live document still renders the bound fixture after SAVE_AS. The
        // row keeps the same protected-state tuple; the oracle cross-checks it
        // against the rebound@0 frame and the pre-save pixels.
        var postRows = new ArrayList<Map<String, Object>>();
        var post = captureInto("post-save-p0", modelRef, meshRef, dir,
            "persistence/post-save-p0.png", postRows);
        save.put("postSaveFrame", postRows.get(0));
        sectionCheck(save, "postSavePixelsEqual", Arrays.equals(boundPixels.argb(), post.argb()),
            "post-save frame is pixel-equal to the pre-save bound render");
        // The pre-save pixel artifacts' SHAs anchor what the live fixture
        // rendered immediately before persistence.
        var pngShas = new LinkedHashMap<String, Object>();
        for (var row : frames) pngShas.put((String) row.get("pose"), row.get("artifactSha256"));
        save.put("pngShas", pngShas);
        save.put("status", "PASS");
    }

    /**
     * T014 Job B reopen leg — runs in a NEW host process whose seed document
     * IS the saved CMO3. No fixture is created: persistent ids and frozen
     * content uniquely re-resolve the mesh/parameter/image from the loaded
     * document, then the same frozen pixel contract is re-sampled.
     */
    static void reopen(Supplier<CubismModel> current, Supplier<HistorySnapshot> history,
            Path stateDir, Map<String, Object> data) throws Exception {
        NativeAssertions.require(!SwingUtilities.isEventDispatchThread(), "T014 reopen must not block EDT");
        var p = new PixelSamplingProbe(current, history, position -> { }, null,
            stateDir.resolve("pixel-sampling"), data);
        data.put("schemaVersion", "mi3d-t014-reopen-v1");
        data.put("mode", "persistence-reopen");
        data.put("process", processIdentity());
        CubismModel model = edt(current::get);
        p.modelRef = model;
        var canvas = edt(() -> canvas(model));
        data.put("reopenedCanvas", canvas);
        p.check("reopenedCanvasFrozen",
            canvas.get("width").equals((float) OUT) && canvas.get("height").equals((float) OUT),
            "persisted canvas is the frozen 1024x1024");
        // Persistent identities re-resolved through public collections; no
        // fixture object is ever created in this process.
        var resolved = edt(() -> {
            var param = model.parameters().all().stream()
                .filter(v -> v.id().equals(PARAM)).findFirst().orElse(null);
            var candidates = model.drawables().all().stream().filter(d -> {
                var snapshot = d.authoringSnapshot();
                var axisBound = snapshot.axes().stream()
                    .anyMatch(a -> a.parameterId().equals(PARAM));
                var imageBound = model.textures().textureInput(d.id())
                    .map(i -> i.kind() == ArtMeshTextureInput.Kind.MODEL_IMAGE).orElse(false);
                return axisBound && imageBound;
            }).toList();
            return new Object[]{param, candidates.size() == 1 ? candidates.get(0) : null,
                candidates.size()};
        });
        var param = (dev.turboism.sdk.cubism.model.Parameter) resolved[0];
        var mesh = (Drawable) resolved[1];
        p.check("persistentParameterResolved", param != null
            && edt(() -> param.getMinimumValue() == -1f && param.getMaximumValue() == 1f
                && param.getDefaultValue() == 0f),
            "parameter id persisted with its frozen domain");
        p.check("uniqueMeshResolved", mesh != null && resolved[2].equals(1),
            "exactly one drawable carries the persistent axis + MODEL_IMAGE binding");
        p.meshRef = mesh;
        var manifest = edt(() -> p.manifest(model));
        data.put("manifest", manifest);
        p.check("reopenedContentFrozen", p.contentMatchesFrozen(manifest),
            "reloaded mesh/parameter/binding content matches the frozen fixture");
        edt(() -> { param.setValue(0f); return null; });
        var p0 = p.capture("bound-p0", model, mesh);
        p.check("boundPatchesP0", patchFailures(p0, 0).isEmpty() && outsideAlphaZero(p0, 0),
            "reopened param-0 frame matches frozen patches");
        edt(() -> { param.setValue(1f); return null; });
        var p1 = p.capture("bound-p1", model, mesh);
        p.check("boundPatchesPos", patchFailures(p1, SHIFT).isEmpty() && outsideAlphaZero(p1, SHIFT),
            "reopened +1 frame matches +80px patches");
        edt(() -> { param.setValue(-1f); return null; });
        var m1 = p.capture("bound-m1", model, mesh);
        p.check("boundPatchesNeg", patchFailures(m1, -SHIFT).isEmpty() && outsideAlphaZero(m1, -SHIFT),
            "reopened -1 frame matches -80px patches");
        Object input = manifest.get("textureInput");
        String imageId = input instanceof Map<?, ?> map ? String.valueOf(map.get("modelImageId")) : "";
        p.check("reopenedBoundInputTyped", p.typedImage(mesh, imageId),
            "typed texture input intact after reload");
        data.put("status", "PASS"); data.put("failure", null);
    }

    private dev.turboism.sdk.cubism.model.Parameter param(CubismModel model) throws Exception {
        var found = edt(() -> model.parameters().find(PARAM));
        NativeAssertions.require(found != null, "pixel shift parameter missing");
        return found;
    }

    /**
     * The persisted-content manifest read on the EDT: persistent identities
     * (mesh/source/form/axis/image GUIDs, parameter id), the frozen authoring
     * tuple (source/UV/topology/forms/axes/cells incl. the real default-form
     * keyform observation), the parameter domain, the typed texture binding,
     * and the EXPECTED source-image recipe digest. Runtime-generation fields
     * (modelGeneration) are deliberately excluded — they are process-local,
     * not persistent identities. The native image content digest stays
     * "UNAVAILABLE" — no verified public readback seam exists (F4).
     */
    private Map<String, Object> manifest(CubismModel model) throws Exception {
        var m = new LinkedHashMap<String, Object>();
        m.put("canvas", canvas(model));
        m.put("meshId", meshRef.id().value());
        var snapshot = meshRef.authoringSnapshot();
        m.put("sourceGuid", snapshot.sourceGuid());
        var source = snapshot.sourceGeometry();
        m.put("sourcePositions", points(source.positions()));
        m.put("sourceUvs", points(source.uvs()));
        m.put("triangles", List.copyOf(source.triangleIndices()));
        var forms = new ArrayList<Map<String, Object>>();
        for (var form : snapshot.forms()) {
            forms.add(Map.of("formGuid", form.formGuid(),
                "positions", points(form.positions()), "drawOrder", form.drawOrder()));
        }
        m.put("forms", List.copyOf(forms));
        var axes = new ArrayList<Map<String, Object>>();
        for (var axis : snapshot.axes()) {
            axes.add(Map.of("parameterGuid", axis.parameterGuid(),
                "parameterId", axis.parameterId().value(),
                "minimum", (double) axis.minimum(), "default", (double) axis.defaultValue(),
                "maximum", (double) axis.maximum(), "keys", List.copyOf(axis.keys())));
        }
        m.put("axes", List.copyOf(axes));
        var cells = new ArrayList<Map<String, Object>>();
        for (var cell : snapshot.cells()) {
            var coords = new ArrayList<Map<String, Object>>();
            for (var c : cell.coordinates()) {
                coords.add(Map.of("parameterGuid", c.parameterGuid(), "value", (double) c.value()));
            }
            cells.add(Map.of("formGuid", cell.formGuid(),
                "coordinates", List.copyOf(coords),
                "defaultKeyform", meshRef.ordinaryKeyform(cell.coordinates()).defaultKeyform()));
        }
        m.put("cells", List.copyOf(cells));
        m.put("morphKeys", snapshot.morphKeys().size());
        // The actual ordinary-keyform reference proves the default tuple's
        // identity/positions are persisted — not inferred from the snapshot set.
        var shiftAxis = snapshot.axes().stream()
            .filter(a -> a.parameterId().equals(PARAM)).findFirst().orElseThrow();
        var defaultKeyform = meshRef.ordinaryKeyform(List.of(
            new ArtMeshAuthoringSnapshot.Coordinate(shiftAxis.parameterGuid(), shiftAxis.defaultValue())));
        var defaultForm = defaultKeyform.snapshot();
        m.put("defaultForm", Map.of(
            "formGuid", defaultForm.formGuid(),
            "positions", points(defaultForm.positions()),
            "drawOrder", defaultForm.drawOrder(),
            "defaultKeyform", defaultKeyform.defaultKeyform()));
        var param = model.parameters().all().stream()
            .filter(v -> v.id().equals(PARAM)).findFirst().orElse(null);
        NativeAssertions.require(param != null,
            "shift parameter must persist for the persistence manifest");
        m.put("parameter", Map.of("id", param.id().value(),
            "value", (double) param.getValue(), "minimum", (double) param.getMinimumValue(),
            "maximum", (double) param.getMaximumValue(), "default", (double) param.getDefaultValue()));
        m.put("textureInput", model.textures().textureInput(meshRef.id())
            .map(input -> {
                var dict = new LinkedHashMap<String, Object>();
                dict.put("kind", input.kind().name());
                dict.put("artMeshId", input.artMeshId().value());
                dict.put("modelId", input.modelId().value());
                dict.put("modelImageId", input.modelImageId().map(id -> id.value()).orElse(null));
                dict.put("textureAtlasId", input.textureAtlasId().map(id -> id.value()).orElse(null));
                return dict;
            }).orElse(null));
        // Honest split: the digest of the frozen input recipe is a stable
        // EXPECTED value; no verified public SDK seam reads the bound model
        // image's bytes back, so the observed native content digest is
        // recorded as unavailable rather than fabricated (T014 review F4).
        m.put("expectedMaterialSha256", sha256(materialPng()));
        m.put("observedMaterialSha256", "UNAVAILABLE");
        return m;
    }

    /** Frozen-fixture content check shared by the save manifest and reopen. */
    private boolean contentMatchesFrozen(Map<String, Object> manifest) {
        if (!manifest.get("sourcePositions").equals(points(rect(0f)))
                || !manifest.get("sourceUvs").equals(
                    points(List.of(new Point2(0f, 0f), new Point2(1f, 0f),
                        new Point2(1f, 1f), new Point2(0f, 1f))))
                || !manifest.get("triangles").equals(List.of(0, 1, 2, 0, 2, 3))) {
            return false;
        }
        @SuppressWarnings("unchecked") var axes =
            (List<Map<String, Object>>) manifest.get("axes");
        if (axes.size() != 1 || !axes.get(0).get("parameterId").equals(PARAM.value())) {
            return false;
        }
        var axis = axes.get(0);
        @SuppressWarnings("unchecked") var keys = (List<Object>) axis.get("keys");
        if (!java.util.Set.copyOf(keys).equals(java.util.Set.of(-1f, 0f, 1f))
                || !axis.get("minimum").equals(-1.0) || !axis.get("default").equals(0.0)
                || !axis.get("maximum").equals(1.0)) {
            return false;
        }
        @SuppressWarnings("unchecked") var forms =
            (List<Map<String, Object>>) manifest.get("forms");
        if (forms.size() != 3
                || forms.stream().map(f -> f.get("formGuid")).distinct().count() != 3) {
            return false;
        }
        var formByGuid = new java.util.HashMap<String, Map<String, Object>>();
        for (var form : forms) formByGuid.put((String) form.get("formGuid"), form);
        @SuppressWarnings("unchecked") var cells =
            (List<Map<String, Object>>) manifest.get("cells");
        @SuppressWarnings("unchecked") var parameter =
            (Map<String, Object>) manifest.get("parameter");
        @SuppressWarnings("unchecked") var input =
            (Map<String, Object>) manifest.get("textureInput");
        if (!(cells.size() == 3 && manifest.get("morphKeys").equals(0)
                && parameter.get("id").equals(PARAM.value())
                && parameter.get("minimum").equals(-1.0) && parameter.get("maximum").equals(1.0)
                && parameter.get("default").equals(0.0)
                && input != null && input.get("kind").equals("MODEL_IMAGE")
                && input.get("artMeshId").equals(manifest.get("meshId"))
                && input.get("modelImageId") instanceof String
                && input.get("textureAtlasId") == null)) {
            return false;
        }
        // Persisted tuple→form linkage: each cell is a distinct key tuple bound
        // to a distinct form whose stored positions are exactly the frozen
        // ±80px rect, and precisely the 0-key tuple is the default form.
        var seenTuples = new java.util.HashSet<Object>();
        var seenForms = new java.util.HashSet<Object>();
        String defaultCellForm = null;
        try {
            for (var cell : cells) {
                if (!seenForms.add(cell.get("formGuid"))
                        || !(cell.get("defaultKeyform") instanceof Boolean)) {
                    return false;
                }
                @SuppressWarnings("unchecked")
                var tuple = (List<Map<String, Object>>) cell.get("coordinates");
                if (tuple.size() != 1
                        || !tuple.get(0).get("parameterGuid").equals(axis.get("parameterGuid"))
                        || !seenTuples.add(tuple)) {
                    return false;
                }
                var value = ((Number) tuple.get(0).get("value")).doubleValue();
                var form = formByGuid.get((String) cell.get("formGuid"));
                if (form == null
                        || !form.get("positions").equals(points(rect((float) (SHIFT * value))))
                        || !(form.get("drawOrder") instanceof Integer)) {
                    return false;
                }
                if (value == 0.0) {
                    if (!Boolean.TRUE.equals(cell.get("defaultKeyform"))) return false;
                    defaultCellForm = (String) cell.get("formGuid");
                } else if (Math.abs(value) != 1.0
                        || Boolean.TRUE.equals(cell.get("defaultKeyform"))) {
                    return false;
                }
            }
            @SuppressWarnings("unchecked") var defaultForm =
                (Map<String, Object>) manifest.get("defaultForm");
            return defaultCellForm != null && defaultForm != null
                && defaultForm.get("defaultKeyform").equals(Boolean.TRUE)
                && defaultForm.get("formGuid").equals(defaultCellForm)
                && defaultForm.get("positions").equals(points(rect(0f)))
                && manifest.get("expectedMaterialSha256").equals(sha256(materialPng()))
                && manifest.get("observedMaterialSha256").equals("UNAVAILABLE");
        } catch (Exception failure) {
            return false;
        }
    }

    /** Actual process identity for cross-process persistence evidence. */
    private static Map<String, Object> processIdentity() {
        var process = ProcessHandle.current();
        var map = new LinkedHashMap<String, Object>();
        map.put("pid", process.pid());
        map.put("startEpochMillis",
            process.info().startInstant().map(Instant::toEpochMilli).orElse(-1L));
        return map;
    }

    private void sectionCheck(Map<String, Object> save, String id, boolean actual,
            String expected) {
        @SuppressWarnings("unchecked") var list =
            (List<Map<String, Object>>) save.get("checks");
        list.add(Map.of("id", id, "actual", actual, "expected", expected,
            "status", actual ? "PASS" : "FAIL", "evidenceKind", "EXACT_HOST"));
        NativeAssertions.require(actual, id + ": " + expected);
    }

    /**
     * Frozen canvas-document rect: TL (256,384), TR (768,384), BR (768,640),
     * BL (256,640). SDK positions are written into the source and CANVAS form
     * verbatim — the native renderer performs its own centering/Y transform,
     * so the document rect IS the frozen 1024 output rect, scaled 2x from the
     * 256x128 source.
     */
    private static List<Point2> rect(final float dx) {
        return List.of(
            new Point2(256f + dx, 384f), new Point2(768f + dx, 384f),
            new Point2(768f + dx, 640f), new Point2(256f + dx, 640f));
    }

    private static List<Point2> shifted(final float dx) { return rect(dx); }

    /**
     * Fixed geometry: full-coverage quad UVs with (0,0) at the source image's
     * top-left corner (v down), two triangles, frozen topology.
     */
    static ArtMeshGeometry geometry() {
        return new ArtMeshGeometry(rect(0f),
            List.of(new Point2(0f, 0f), new Point2(1f, 0f),
                new Point2(1f, 1f), new Point2(0f, 1f)),
            List.of(0, 1, 2, 0, 2, 3));
    }

    /**
     * The fixed 256x128 OPAQUE material: four quadrant colors and a coarse
     * right-pointing arrow. Recipe literals only — independent of any output.
     */
    static byte[] materialPng() throws Exception {
        var image = new BufferedImage(SRC_W, SRC_H, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        g.setColor(new java.awt.Color(226, 59, 59));  g.fillRect(0, 0, 128, 64);
        g.setColor(new java.awt.Color(59, 178, 90));  g.fillRect(128, 0, 128, 64);
        g.setColor(new java.awt.Color(59, 92, 226));  g.fillRect(0, 64, 128, 64);
        g.setColor(new java.awt.Color(240, 200, 50)); g.fillRect(128, 64, 128, 64);
        g.setColor(new java.awt.Color(16, 16, 16));
        g.fillRect(28, 24, 37, 13);
        g.fillPolygon(new int[]{64, 88, 64}, new int[]{12, 30, 48}, 3);
        g.dispose();
        var out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "PNG", out);
        return out.toByteArray();
    }

    /**
     * One native frame in ONE EDT observation window: the complete protected
     * state tuple is read before and after the render and must be identical.
     * The tuple covers the history document/manager/generation/position/entry
     * content (never position alone), the model and current-instance
     * identities, the parameter value, the requested/observed mesh source
     * geometry, UVs, triangles, stored forms/axes/cells, the evaluated
     * positions and the typed texture binding. {@code dirty} stays
     * {@code "UNAVAILABLE"} — no verified read seam exists.
     */
    private CubismRenderedPixels capture(String pose) throws Exception {
        return capture(pose, null, null);
    }

    private CubismRenderedPixels capture(String pose, CubismModel model, Drawable mesh)
            throws Exception {
        return captureInto(pose, model, mesh, artifacts,
            "pixel-sampling/" + pose + ".png", frames);
    }

    /**
     * T014 post-save sample: the same one-window observation/render, written
     * to an independently named bounded artifact and an independent sink so
     * the frozen seven-pose frame list is never polluted.
     */
    private CubismRenderedPixels captureInto(String pose, CubismModel model, Drawable mesh,
            Path dir, String artifact, List<Map<String, Object>> sink) throws Exception {
        NativeAssertions.require(pose.matches("[a-z0-9][a-z0-9-]*"), "unsafe pose label");
        return edt(() -> {
            var before = observe(model, mesh);
            var pixels = current.get().renderPixels(OUT, OUT);
            var after = observe(model, mesh);
            NativeAssertions.require(before.state.equals(after.state),
                "protected state drifted across the native render for pose " + pose);
            var png = pngBytes(pixels);
            NativeAssertions.require(png.length <= MAX_ARTIFACT_BYTES,
                "PNG artifact exceeds the bounded size for pose " + pose);
            writeArtifactExclusive(dir, pose, png);
            var row = new LinkedHashMap<String, Object>();
            row.put("pose", pose);
            row.put("width", pixels.width()); row.put("height", pixels.height());
            row.put("stateBefore", before.state); row.put("stateAfter", after.state);
            row.put("historyRevisionBefore", before.revision);
            row.put("historyRevisionAfter", after.revision);
            row.put("artifact", artifact);
            row.put("artifactSha256", sha256(png));
            row.put("artifactBytes", png.length);
            sink.add(row);
            return pixels;
        });
    }

    private record Observed(Map<String, Object> state, long revision) {
    }

    /** The protected-state tuple read inside the same EDT window as the render. */
    private Observed observe(CubismModel model, Drawable mesh) {
        var state = new LinkedHashMap<String, Object>();
        HistorySnapshot h = history.get();
        state.put("history", historyTuple(h));
        if (model == null) {
            state.put("model", null); state.put("canvas", null); state.put("instances", null);
            state.put("currentInstance", null); state.put("parameter", null);
        } else {
            state.put("model", model.id().value());
            state.put("canvas", canvas(model));
            // The public SDK re-projects model instances as fresh value objects
            // on every read; their references carry no native identity. Only the
            // ordered renderType sequence and the current renderType value are
            // observable here — the raw object identity/order guard lives inside
            // EditorRenderPixelsAccess (its pre/post == checks on native refs).
            var instances = new ArrayList<String>();
            for (var instance : model.modelInstances()) {
                instances.add(instance.renderType().name());
            }
            state.put("instances", List.copyOf(instances));
            state.put("currentInstance", model.currentModelInstance()
                .map(instance -> instance.renderType().name()).orElse(null));
            // Presence is decided through the public parameter collection:
            // Parameters.find throws NoSuchElementException for absent ids, and
            // the fixture parameter is legitimately deleted by the whole-leg
            // restore before the blank frame. For a bound frame the parameter
            // must still exist with its expected pose value.
            var present = model.parameters().all().stream()
                .filter(parameter -> parameter.id().equals(PARAM)).findFirst().orElse(null);
            if (mesh != null) {
                NativeAssertions.require(present != null,
                    "pixel shift parameter unexpectedly absent during a bound frame");
            }
            state.put("parameter", present == null ? null : Map.of(
                "id", present.id().value(), "value", (double) present.getValue()));
        }
        if (model == null || mesh == null) {
            state.put("mesh", null); state.put("textureInput", null);
        } else {
            var snapshot = mesh.authoringSnapshot();
            var source = snapshot.sourceGeometry();
            var forms = new ArrayList<Map<String, Object>>();
            for (var form : snapshot.forms()) {
                forms.add(Map.of("formGuid", form.formGuid(),
                    "positions", points(form.positions()), "drawOrder", form.drawOrder()));
            }
            var axes = new ArrayList<Map<String, Object>>();
            for (var axis : snapshot.axes()) {
                axes.add(Map.of("parameterGuid", axis.parameterGuid(),
                    "parameterId", axis.parameterId().value(), "keys", List.copyOf(axis.keys())));
            }
            var cells = new ArrayList<Map<String, Object>>();
            for (var cell : snapshot.cells()) {
                var coords = new ArrayList<Map<String, Object>>();
                for (var c : cell.coordinates()) {
                    coords.add(Map.of("parameterGuid", c.parameterGuid(), "value", (double) c.value()));
                }
                // The real ordinary-keyform lookup, not a snapshot claim: the
                // generation-bound reference answers whether this exact tuple is
                // the default form (ShapeImportProbe-proven SDK path).
                cells.add(Map.of("formGuid", cell.formGuid(),
                    "coordinates", List.copyOf(coords),
                    "defaultKeyform", mesh.ordinaryKeyform(cell.coordinates()).defaultKeyform()));
            }
            state.put("mesh", Map.of(
                "id", mesh.id().value(),
                "sourcePositions", points(source.positions()),
                "sourceUvs", points(source.uvs()),
                "triangles", List.copyOf(source.triangleIndices()),
                "forms", List.copyOf(forms), "axes", List.copyOf(axes),
                "cells", List.copyOf(cells), "morphKeys", snapshot.morphKeys().size(),
                "evaluatedPositions", points(mesh.geometry().positions())));
            state.put("textureInput", model.textures().textureInput(mesh.id())
                .map(input -> {
                    var dict = new LinkedHashMap<String, Object>();
                    dict.put("kind", input.kind().name());
                    dict.put("artMeshId", input.artMeshId().value());
                    dict.put("modelId", input.modelId().value());
                    dict.put("modelGeneration", input.modelGeneration());
                    dict.put("modelImageId", input.modelImageId().map(id -> id.value()).orElse(null));
                    dict.put("textureAtlasId", input.textureAtlasId().map(id -> id.value()).orElse(null));
                    return dict;
                }).orElse(null));
        }
        // No verified dirty read seam exists; modelEditing is not a dirty flag.
        state.put("dirty", "UNAVAILABLE");
        return new Observed(state, h.revision());
    }

    private static Map<String, Object> counts(CubismModel m) {
        return Map.of("meshes", m.drawables().all().size(), "deformers", m.deformers().all().size(),
            "parameters", m.parameters().all().size(), "parts", m.parts().all().size());
    }

    private static Map<String, Object> canvas(CubismModel m) {
        var c = m.canvas();
        return Map.of("width", c.widthPixels(), "height", c.heightPixels(),
            "originX", c.originXPixels(), "originY", c.originYPixels(),
            "pixelsPerUnit", c.pixelsPerUnit());
    }

    private static List<List<Float>> points(List<Point2> values) {
        var out = new ArrayList<List<Float>>(values.size());
        for (Point2 p : values) out.add(List.of(p.x(), p.y()));
        return out;
    }

    /** Exclusive bounded write: refuses existing files and symlink components. */
    private void writeArtifactExclusive(Path dir, String pose, byte[] png) throws Exception {
        if (Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            NativeAssertions.require(Files.isDirectory(dir)
                && !Files.isSymbolicLink(dir), "artifact directory is not a real directory");
        } else {
            Files.createDirectories(dir);
        }
        var file = dir.resolve(pose + ".png");
        NativeAssertions.require(!Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS),
            "refusing to overwrite existing artifact " + pose + ".png");
        try (var stream = Files.newOutputStream(file,
                java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE)) {
            stream.write(png);
        }
    }

    private static byte[] pngBytes(CubismRenderedPixels pixels) throws Exception {
        var image = new BufferedImage(pixels.width(), pixels.height(), BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, pixels.width(), pixels.height(), pixels.argb(), 0, pixels.width());
        var out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "PNG", out);
        return out.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        var hex = new StringBuilder();
        for (byte b : digest.digest(bytes)) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private ArtMeshKeyform keyform(Drawable mesh, List<ArtMeshAuthoringSnapshot.Coordinate> coords) {
        return mesh.ordinaryKeyform(coords);
    }

    private String axis(ArtMeshAuthoringSnapshot s) {
        var found = s.axes().stream()
            .filter(a -> a.parameterId().value().equals(PARAM.value())).toList();
        NativeAssertions.require(found.size() == 1, "T008 axis identity missing/ambiguous");
        var axis = found.get(0);
        NativeAssertions.require(
            java.util.Set.copyOf(axis.keys()).equals(java.util.Set.of(-1f, 0f, 1f)),
            "T008 declared shift keys missing from the bound axis");
        return axis.parameterGuid();
    }

    /** Full typed-input identity: mesh, kind, image identity, no atlas alias. */
    private boolean typedImage(Drawable mesh, String imageId) throws Exception {
        final Optional<ArtMeshTextureInput> observed = edt(() ->
            current.get().textures().textureInput(mesh.id()));
        return observed.isPresent()
            && observed.get().kind() == ArtMeshTextureInput.Kind.MODEL_IMAGE
            && observed.get().artMeshId().equals(mesh.id())
            && observed.get().modelImageId().isPresent()
            && observed.get().modelImageId().get().value().equals(imageId)
            && observed.get().textureAtlasId().isEmpty();
    }

    /** Frozen 15-patch assertion: every pixel of each 5x5 patch within tolerance. */
    static List<String> patchFailures(CubismRenderedPixels frame, int shiftX) {
        var failures = new ArrayList<String>();
        if (frame.width() != OUT || frame.height() != OUT) {
            failures.add("output-dimensions");
            return failures;
        }
        for (Object[] s : SAMPLES) {
            String name = (String) s[0];
            int lx = (Integer) s[1], ly = (Integer) s[2];
            int r = (Integer) s[3], g = (Integer) s[4], b = (Integer) s[5];
            int cx = BX + 2 * lx + 1 + shiftX, cy = BY + 2 * ly + 1;
            for (int py = cy - PATCH; py <= cy + PATCH; py++) {
                for (int px = cx - PATCH; px <= cx + PATCH; px++) {
                    int argb = frame.argbAt(px, py);
                    int a = (argb >>> 24) & 0xFF, rr = (argb >>> 16) & 0xFF;
                    int gg = (argb >>> 8) & 0xFF, bb = argb & 0xFF;
                    if (Math.abs(rr - r) > TOL || Math.abs(gg - g) > TOL
                        || Math.abs(bb - b) > TOL || a != 255) {
                        failures.add(name + "@" + px + "," + py);
                        py = cy + PATCH + 1;
                        break;
                    }
                }
            }
        }
        return failures;
    }

    static boolean outsideAlphaZero(CubismRenderedPixels frame, int shiftX) {
        for (int[] p : OUTSIDE) {
            if (((frame.argbAt(p[0] + shiftX, p[1]) >>> 24) & 0xFF) != 0) return false;
        }
        return true;
    }

    static boolean allTransparent(CubismRenderedPixels frame) {
        for (int argb : frame.argb()) {
            if (((argb >>> 24) & 0xFF) != 0) return false;
        }
        return true;
    }

    private void check(String id, boolean actual, String expected) {
        NativeAssertions.require(checks.stream().noneMatch(c -> id.equals(c.get("id"))),
            "duplicate T008 check");
        checks.add(Map.of("id", id, "actual", actual, "expected", expected,
            "status", actual ? "PASS" : "FAIL", "evidenceKind", "EXACT_HOST"));
        NativeAssertions.require(actual, id + ": " + expected);
    }

    /** Compared history identity tuple: document/manager/generation/position/entries. */
    private static Map<String, Object> historyTuple(HistorySnapshot h) {
        var entries = new ArrayList<Map<String, Object>>();
        for (var e : h.entries()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("index", e.index());
            row.put("label", e.label());
            row.put("significant", e.significant());
            row.put("entryId", e.entryId().map(id -> id.value()).orElse(null));
            row.put("transactionId", e.transactionId().orElse(null));
            entries.add(row);
        }
        var tuple = new LinkedHashMap<String, Object>();
        tuple.put("availability", h.availability().name());
        tuple.put("generation", h.generation());
        tuple.put("position", h.position());
        tuple.put("documentBindingId", h.documentBindingId());
        tuple.put("managerBindingId", h.managerBindingId());
        tuple.put("canUndo", h.canUndo());
        tuple.put("canRedo", h.canRedo());
        tuple.put("entries", List.copyOf(entries));
        return tuple;
    }

    /** Standalone history payload including the provider-local revision noise counter. */
    static Map<String, Object> historyData(HistorySnapshot h) {
        var data = historyTuple(h);
        data.put("revision", h.revision());
        return data;
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        var task = new FutureTask<>(action);
        SwingUtilities.invokeLater(task);
        return task.get();
    }
}
