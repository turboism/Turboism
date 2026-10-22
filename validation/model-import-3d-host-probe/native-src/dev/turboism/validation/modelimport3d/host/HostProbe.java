package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCanvasSettingsRequest;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

/** Validation-only, SDK-only consumer. Never reads or invokes Cubism/runtime classes reflectively. */
public final class HostProbe implements TurboismPlugin {
    private PluginContext context;
    private volatile boolean active;
    private Thread worker;
    private NativeReport report;
    private String taskDocument;
    private String taskModel;
    private String taskSnapshotModel;
    private HistorySnapshot initialHistory;
    private Map<String, Object> initialCounts;

    private static final List<String> ADMITTED_HOST_VERSIONS = List.of(
        "5000", "5001", "5002", "5003", "5004", "5005", "5006",
        "5100", "5101", "5102", "5103",
        "5200", "5201", "5202", "5203",
        "5300", "5301", "5302", "5303", "5304");

    @Override public void init(PluginContext context) { this.context = context; }

    @Override public synchronized void enable() {
        if (worker != null) throw new IllegalStateException("probe may run only once");
        String runId = System.getProperty("turboism.validation.runId", "");
        NativeAssertions.require(runId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,180}"), "missing task run ID");
        NativeAssertions.require(ADMITTED_HOST_VERSIONS.contains(System.getProperty("turboism.validation.hostVersion")), "N2 requires admitted hostVersion");
        String mode = System.getProperty("turboism.validation.mi3d.mode", "");
        requireMode(mode);
        report = new NativeReport(runId);
        active = true;
        worker = new Thread(this::run, "mi3d-native-n2-probe"); worker.setDaemon(true); worker.start();
        context.logger().info("MI3D_N2_READY");
    }

    static void requireMode(String mode) {
        NativeAssertions.require(mode.isEmpty() || mode.equals("plane-render") || mode.equals("plane-seam-render") || mode.equals("plane-geometry") || mode.equals("plane-seam-geometry") || mode.equals("sheet-geometry") || mode.equals("sheet-render") || mode.equals("persistence-save") || mode.equals("cube-atlas") || mode.equals("cube-export")
                || mode.equals("cube-persistence-save") || mode.equals("cube-persistence-reopen")
                || mode.equals("cube-atlas-save") || mode.equals("cube-atlas-reopen") || mode.equals("warp-authoring") || mode.equals("source-warp-candidate") || mode.equals("source-warp-textured") || mode.equals("canonical-warp-textured") || mode.equals("canonical-warp-layered-textured")
                || mode.equals("persistence-reopen") || mode.equals("joint-pose") || mode.equals("cube-geometry") || mode.equals("cube-render") || mode.equals("whole-import"), "unknown mi3d mode: " + mode);
    }

    @Override public void disable() { active = false; if (worker != null) worker.interrupt(); }
    @Override public void shutdown() { disable(); }

    private void run() {
        String status = "FAIL"; Throwable failure = null;
        try {
            Path flag = context.paths().stateDir().resolve("exerciser.flag");
            long deadline = System.nanoTime() + 600_000_000_000L;
            while (!Files.isRegularFile(flag, LinkOption.NOFOLLOW_LINKS)) {
                if (!active || System.nanoTime() > deadline) throw new IllegalStateException("trigger unavailable");
                Thread.sleep(250);
            }
            String mode = System.getProperty("turboism.validation.mi3d.mode", "");
            report.data.put("mi3dMode", mode.isEmpty() ? "normal" : mode);
            DocumentBinding seed = awaitDocument(null, "SEED");
            report.data.put("seed", identity(seed));
            if ("persistence-reopen".equals(mode) || "cube-persistence-reopen".equals(mode) || "cube-atlas".equals(mode) || "cube-export".equals(mode)
                    || "cube-atlas-save".equals(mode) || "cube-atlas-reopen".equals(mode)) {
                // Use the launcher's isolated saved-artifact copy. Reopen modes
                // only observe; atlas-save packs this copy and saves a new file.
                taskDocument = seed.document().documentId();
                taskModel = seed.editorModelId();
                taskSnapshotModel = seed.document().model().orElseThrow().modelId();
                report.data.put("document", identity(seed));
                edt(() -> {
                    CubismModel model = current();
                    initialCounts = counts(model);
                    report.data.put("initialCounts", initialCounts);
                    initialHistory = history();
                    report.data.put("historyBefore", historyData(initialHistory));
                    return null;
                });
                if ("cube-atlas-save".equals(mode) || "cube-atlas-reopen".equals(mode)) {
                    var persistence = new LinkedHashMap<String,Object>();
                    report.data.put("cubeAtlasPersistence",persistence);
                    var layouts=edt(()->context.cubism().textureAtlasLayouts());
                    var commands=edt(()->context.services().require(dev.turboism.sdk.cubism.command.EditorCommandService.class));
                    byte[] input=persistenceInput();
                    if ("cube-atlas-save".equals(mode))
                        CubeAtlasProbe.run(input,this::current,this::history,this::restore,layouts,commands,
                            context.paths().stateDir(),report.cubeAtlas,
                            ()->CubeAtlasPersistenceProbe.savePacked(input,this::current,this::history,this::restore,
                                layouts,commands,context.userFiles(),context.paths().stateDir(),persistence));
                    else CubeAtlasPersistenceProbe.reopen(input,this::current,this::history,this::restore,
                        layouts,commands,context.paths().stateDir(),persistence);
                } else if ("cube-export".equals(mode)) {
                    byte[] input=persistenceInput();
                    var profile=CubePersistenceInput.identify(CubePersistenceInput.prepare(input).inputSha256);
                    CubeAtlasProbe.run(input,this::current,this::history,this::restore,
                        edt(()->context.cubism().textureAtlasLayouts()),edt(()->context.services().require(dev.turboism.sdk.cubism.command.EditorCommandService.class)),
                        context.paths().stateDir(),report.cubeAtlas,
                        ()->CubeMocExportProbe.run(this::current,this::history,context.paths().stateDir(),report.cubeExport,profile));
                } else if ("cube-atlas".equals(mode))
                    CubeAtlasProbe.run(persistenceInput(),this::current,this::history,this::restore,
                        edt(()->context.cubism().textureAtlasLayouts()),edt(()->context.services().require(dev.turboism.sdk.cubism.command.EditorCommandService.class)),
                        context.paths().stateDir(),report.cubeAtlas);
                else if ("cube-persistence-reopen".equals(mode))
                    CubePersistenceProbe.reopen(persistenceInput(), this::current, this::history, this::restore, context.paths().stateDir(), report.cubePersistence);
                else PixelSamplingProbe.reopen(this::current, this::history,
                    context.paths().stateDir(), report.persistenceReopen);
                status = "PASS";
                return;
            }
            byte[] cubeInput = null;
            byte[] sheetInput = null;
            byte[] planarInput = null;
            PlanarGeometryProbe.Profile planarProfile = null;
            if("plane-geometry".equals(mode) || "plane-seam-geometry".equals(mode) || "plane-render".equals(mode) || "plane-seam-render".equals(mode)) {
                planarProfile=PlanarGeometryProbe.Profile.forMode(mode);
                try(var stream=HostProbe.class.getResourceAsStream("fixtures/"+planarProfile.resource)) {
                    planarInput=PlanarGeometryProbe.readInput(stream,planarProfile);
                }
            }
            if("sheet-geometry".equals(mode) || "sheet-render".equals(mode)) {
                try(var stream=HostProbe.class.getResourceAsStream("fixtures/sheets.glb")) {
                    sheetInput=SheetGeometryProbe.readInput(stream);
                }
            }
            if ("cube-geometry".equals(mode) || "cube-render".equals(mode) || "whole-import".equals(mode) || "cube-persistence-save".equals(mode)) {
                cubeInput = "cube-persistence-save".equals(mode) ? persistenceInput() : cubeInput();
            }
            EditorCommandResult command = edt(() -> context.services().require(dev.turboism.sdk.cubism.command.EditorCommandService.class).execute(EditorCommand.NEW_MODEL));
            report.check("newCommand", command.status() == EditorCommandResult.Status.EXECUTED, "NEW_MODEL executed");
            DocumentBinding blank = awaitDocument(seed.document().documentId(), "NEW");
            taskDocument = blank.document().documentId(); taskModel = blank.editorModelId();
            taskSnapshotModel = blank.document().model().orElseThrow().modelId();
            report.data.put("document", identity(blank));
            report.check("newIdentity", !taskDocument.equals(seed.document().documentId())
                && !taskSnapshotModel.equals(seed.document().model().orElseThrow().modelId())
                && !taskModel.equals(seed.editorModelId()), "new document and both model identities differ from seed");
            if("canonical-warp-textured".equals(mode) || "canonical-warp-layered-textured".equals(mode)) {
                edt(()->{initialCounts=counts(current());initialHistory=history();report.data.put("initialCounts",initialCounts);report.data.put("historyBefore",historyData(initialHistory));return null;});
                edt(()->{
                    var resized=context.services().require(dev.turboism.sdk.cubism.command.EditorCommandService.class).execute(new EditorCanvasSettingsRequest(1024,1024));
                    NativeAssertions.require(resized.status()==EditorCommandResult.Status.EXECUTED,"canonical canvas setup failed");
                    var canvas=current().canvas();
                    NativeAssertions.require(canvas.widthPixels()==1024 && canvas.heightPixels()==1024,"canonical canvas setup readback");
                    report.data.put("canonicalCanvasSetup",Map.of("status",resized.status().name(),"width",canvas.widthPixels(),"height",canvas.heightPixels()));
                    return null;
                });
                var section=new LinkedHashMap<String,Object>();report.data.put("canonicalWarp",section);
                byte[] raw;
                try(var input=HostProbe.class.getResourceAsStream("/dev/turboism/validation/modelimport3d/host/fixtures/apple-textured.glb")) {
                    NativeAssertions.require(input!=null,"missing canonical source GLB");raw=input.readNBytes(16*1024*1024+1);
                }
                NativeAssertions.require(raw.length<=16*1024*1024 && java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw)).equals("3b31ef1167d6aa75c5746906838ad1e37447718ece116512c187337be0f87999"),"canonical source GLB identity");
                // Optional probe module is packaged only by the explicit canonical inventory.
                var entry=Class.forName("dev.turboism.validation.modelimport3d.host."+("canonical-warp-layered-textured".equals(mode)?"CanonicalWarpLayerProbe":"CanonicalWarpProbe")).getDeclaredMethod("runSource",byte[].class,java.util.function.Supplier.class,java.util.function.Supplier.class,java.util.function.IntConsumer.class,dev.turboism.sdk.cubism.transaction.AuthoringTransactionService.class,java.nio.file.Path.class,java.util.Map.class);
                try {
                entry.invoke(null,raw,(java.util.function.Supplier<CubismModel>)this::current,(java.util.function.Supplier<dev.turboism.sdk.cubism.history.HistorySnapshot>)this::history,(java.util.function.IntConsumer)this::restore,edt(()->context.cubism().authoringTransactions()),context.paths().stateDir(),section);
                } catch (java.lang.reflect.InvocationTargetException wrapped) {
                    // The optional-module boundary must not replace the actual admission error.
                    final Throwable cause=wrapped.getCause();
                    if(cause instanceof Exception primary)throw primary;
                    if(cause instanceof Error fatal)throw fatal;
                    throw wrapped;
                }
                status="PASS";return;
            }
            if("source-warp-candidate".equals(mode) || "source-warp-textured".equals(mode)) {
                edt(()->{initialCounts=counts(current());initialHistory=history();
                    report.data.put("initialCounts",initialCounts);report.data.put("historyBefore",historyData(initialHistory));return null;});
                var section=new LinkedHashMap<String,Object>();report.data.put("sourceWarpCandidate",section);
                String resource="/dev/turboism/validation/modelimport3d/host/fixtures/apple-warp.bin";
                var digest=java.security.MessageDigest.getInstance("SHA-256");long length=0;
                try(var input=HostProbe.class.getResourceAsStream(resource)) {
                    NativeAssertions.require(input!=null,"missing authorized source Warp resource");
                    byte[] block=new byte[65536];int read;
                    while((read=input.read(block))!=-1){length+=read;NativeAssertions.require(length<=73575376,"source Warp resource length");digest.update(block,0,read);}
                }
                String sha=java.util.HexFormat.of().formatHex(digest.digest());
                NativeAssertions.require(length==73575376 && sha.equals("9320e7ae7a44f905cfab5827fe84f334b370e7e0f3ea9057fe05c01e4bd69360"),"authorized source Warp resource identity");
                section.put("fixtureSha256",sha);
                SourceWarpFixture source;
                try(var input=HostProbe.class.getResourceAsStream(resource)){source=SourceWarpFixture.read(input,length);}
                SourceWarpAppearance.Plan appearance=null;
                if("source-warp-textured".equals(mode)) {
                    byte[] raw;
                    try(var input=HostProbe.class.getResourceAsStream("/dev/turboism/validation/modelimport3d/host/fixtures/apple-textured.glb")) {
                        NativeAssertions.require(input!=null,"missing apple source texture resource");
                        raw=input.readNBytes(16*1024*1024+1);
                    }
                    NativeAssertions.require(raw.length<=16*1024*1024,"bounded apple texture source");
                    String textureSha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw));
                    NativeAssertions.require(textureSha.equals("3b31ef1167d6aa75c5746906838ad1e37447718ece116512c187337be0f87999"),"apple source texture identity");
                    section.put("textureSourceSha256",textureSha);
                    appearance=SourceWarpAppearance.prepare(GlbSurfaceReader.read(raw),source);
                }
                SourceWarpCandidateProbe.run(source,this::current,this::history,this::restore,edt(()->context.cubism().authoringTransactions()),context.paths().stateDir(),section,appearance);
                status="PASS";return;
            }
            if("warp-authoring".equals(mode)) {
                edt(()->{initialCounts=counts(current());initialHistory=history();
                    report.data.put("initialCounts",initialCounts);report.data.put("historyBefore",historyData(initialHistory));return null;});
                var section=new LinkedHashMap<String,Object>();report.data.put("warpAuthoring",section);
                WarpAuthoringProbe.run(this::current,this::history,this::restore,edt(()->context.cubism().authoringTransactions()),section);
                status="PASS";return;
            }
            if(planarProfile!=null) {
                edt(()->{initialCounts=counts(current());initialHistory=history();
                    report.data.put("initialCounts",initialCounts);report.data.put("historyBefore",historyData(initialHistory));return null;});
                var section=new LinkedHashMap<String,Object>();report.data.put("planarGeometry",section);
                PlanarGeometryProbe.run(planarInput,planarProfile,this::current,this::history,this::restore,
                    edt(()->context.services().require(dev.turboism.sdk.cubism.command.EditorCommandService.class)),edt(()->context.cubism().authoringTransactions()),section,
                    mode.endsWith("-render")?context.paths().stateDir():null);
                status="PASS";return;
            }
            if("sheet-geometry".equals(mode) || "sheet-render".equals(mode)) {
                edt(()->{initialCounts=counts(current());initialHistory=history();
                    report.data.put("initialCounts",initialCounts);report.data.put("historyBefore",historyData(initialHistory));return null;});
                var section=new LinkedHashMap<String,Object>();report.data.put("sheetGeometry",section);
                SheetGeometryProbe.run(sheetInput,this::current,this::history,this::restore,
                    edt(()->context.services().require(dev.turboism.sdk.cubism.command.EditorCommandService.class)),edt(()->context.cubism().authoringTransactions()),section,
                    "sheet-render".equals(mode)?context.paths().stateDir():null);
                status="PASS";return;
            }
            if ("cube-persistence-save".equals(mode)) {
                edt(() -> {
                    initialCounts = counts(current()); initialHistory = history();
                    report.data.put("initialCounts", initialCounts);
                    report.data.put("historyBefore", historyData(initialHistory)); return null;
                });
                CubePersistenceProbe.save(cubeInput, this::current, this::history, this::restore,
                    edt(() -> context.cubism().authoringTransactions()), edt(() -> context.services().require(dev.turboism.sdk.cubism.command.EditorCommandService.class)),
                    edt(() -> context.userFiles()), context.paths().stateDir(), report.cubePersistence);
                status = "PASS"; return;
            }
            edt(() -> { matrix(); return null; });
            ShapeImportProbe.run(this::current, this::history, this::restore,
                edt(() -> context.cubism().authoringTransactions()), report.shapeImport);
            if ("joint-pose".equals(mode)) JointPoseProbe.run(this::current, this::history, this::restore,
                edt(() -> context.services().require(dev.turboism.sdk.cubism.command.EditorCommandService.class)), edt(() -> context.cubism().authoringTransactions()), report.jointPose);
            if ("whole-import".equals(mode)) WholeImportProbe.run(cubeInput, this::current, this::history, this::restore,
                edt(() -> context.cubism().authoringTransactions()), report.wholeImport);
            if ("cube-geometry".equals(mode)) CubeGeometryProbe.run(cubeInput, this::current, this::history, this::restore,
                edt(() -> context.services().require(dev.turboism.sdk.cubism.command.EditorCommandService.class)), edt(() -> context.cubism().authoringTransactions()), report.cubeGeometry);
            if ("cube-render".equals(mode)) CubeGeometryProbe.run(cubeInput, this::current, this::history, this::restore,
                edt(() -> context.services().require(dev.turboism.sdk.cubism.command.EditorCommandService.class)), edt(() -> context.cubism().authoringTransactions()), report.cubeGeometry,
                (surfaces, plan, created) -> CubeRenderProbe.run(this::current, this::history, this::restore,
                    surfaces, plan, created, context.paths().stateDir(), report.cubeRender), SurfaceImportGeometry.UvMode.FLAT_COLOR_INTERIOR);
            EvaluationProbe.run(this::current, this::history, this::restore, edt(() -> context.services().require(dev.turboism.sdk.cubism.command.EditorCommandService.class)),
                edt(() -> context.cubism().authoringTransactions()), report.evaluation);
            OrderProbe.run(this::current, this::history, this::restore, edt(() -> context.services().require(dev.turboism.sdk.cubism.command.EditorCommandService.class)),
                edt(() -> context.cubism().authoringTransactions()), report.order);
            MorphProbe.run(this::current, this::history, this::restore, edt(() -> context.services().require(dev.turboism.sdk.cubism.command.EditorCommandService.class)),
                edt(() -> context.cubism().authoringTransactions()), report.morph);
            MorphOrderProbe.run(this::current, this::history, this::restore, edt(() -> context.services().require(dev.turboism.sdk.cubism.command.EditorCommandService.class)),
                edt(() -> context.cubism().authoringTransactions()), report.morphOrder);
            ImageBindingProbe.run(this::current, this::history, this::restore, report.textureBinding);
            PixelSamplingProbe.run(this::current, this::history, this::restore,
                edt(() -> context.services().require(dev.turboism.sdk.cubism.command.EditorCommandService.class)),
                edt(() -> context.cubism().authoringTransactions()),
                edt(() -> context.userFiles()),
                context.paths().stateDir(), report.pixelSampling,
                "persistence-save".equals(mode) ? report.persistenceSave : null);
            status = "PASS";
        } catch (Throwable problem) {
            failure = problem;
            // The terminal FAIL marker is emitted only after recovery and report writing.
            // An early marker makes the wrapper collect/terminate before evidence exists.
            context.logger().error("MI3D_N2_EXCEPTION " + problem);
        } finally {
            if (initialHistory != null && active) {
                try {
                    edt(() -> {
                        CubismModel model = current();
                        restore(initialHistory.position());
                        Map<String, Object> counts = counts(model);
                        report.data.put("finallyCounts", counts);
                        NativeAssertions.require(counts.equals(initialCounts), "final recovery counts differ");
                        report.data.put("finallyRestored", true); return null;
                    });
                } catch (Throwable recovery) {
                    report.data.put("finallyRestored", false);
                    report.data.put("recoveryFailure", recovery.toString());
                    if (failure == null) failure = recovery; else failure.addSuppressed(recovery);
                    status = "FAIL";
                }
            }
            try {
                report.write(context.paths().stateDir(), status, failure);
                context.logger().info("MI3D_N2_" + status);
            } catch (Exception writeFailure) {
                context.logger().error("MI3D_N2_REPORT_FAILED " + writeFailure);
                status = "FAIL";
            }
            // Only this explicitly packaged task JVM; external Supervisor still proves scope cleanup.
            if (active) {
                try { Thread.sleep(2000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                if (active) Runtime.getRuntime().exit("PASS".equals(status) ? 0 : 2);
            }
        }
    }

    private static byte[] persistenceInput() throws Exception {
        try(var stream=HostProbe.class.getResourceAsStream("fixtures/cube-textured.glb")) {
            if(stream==null)return cubeInput();
            return CubePersistenceInput.readTexture(stream);
        }
    }

    private static byte[] cubeInput() throws Exception {
        try (var stream = HostProbe.class.getResourceAsStream("fixtures/cube.glb")) {
            NativeAssertions.require(stream != null, "cube mode requires frozen authorized input resource");
            byte[] bytes = stream.readNBytes(1937);
            NativeAssertions.require(bytes.length == 1936 && java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(CubeGeometryProbe.INPUT_SHA256),
                "packaged cube input differs from authorized file");
            return bytes;
        }
    }

    private void matrix() {
        CubismModel model = current();
        initialCounts = counts(model); report.data.put("initialCounts", initialCounts);
        report.check("blankMesh", model.drawables().all().isEmpty() && model.deformers().all().isEmpty(),
            "new model has no ArtMesh or deformer");
        initialHistory = history(); report.data.put("historyBefore", historyData(initialHistory));
        report.check("historyAvailable", initialHistory.availability() == HistorySnapshot.Availability.AVAILABLE
            && !initialHistory.documentBindingId().isEmpty(), "native history has current binding");
        current();
        var created = model.drawables().create("MI3D_N2_" + report.data.get("runId"), null, -1, NativeAssertions.GEOMETRY);
        ArtMeshId id = created.id();
        HistorySnapshot afterCreate = history(); report.data.put("historyCreated", historyData(afterCreate));
        report.check("createSingleUndo", afterCreate.position() == initialHistory.position() + 1,
            "mesh creation adds one history position");
        ArtMeshAuthoringSnapshot createdSnapshot = created.authoringSnapshot();
        NativeAssertions.created(createdSnapshot); report.data.put("created", NativeAssertions.snapshot(createdSnapshot));
        report.data.put("createdCounts", counts(model));
        report.data.put("historyAfterCreatedRead", historyData(history()));
        report.check("createdGeometry", model.drawables().all().size() == 1, "one fixed-geometry ArtMesh created");
        report.check("createdReadNoChange", afterCreate.equals(history()), "stored-form read leaves history unchanged");
        restore(initialHistory.position());
        report.check("createUndo", model.drawables().all().isEmpty(), "one Undo removes the created ArtMesh");
        report.data.put("createUndoCounts", counts(model));
        boolean stale = false;
        try { created.authoringSnapshot(); } catch (IllegalStateException | UnsupportedOperationException expected) { stale = true; }
        report.check("deletedHandle", stale, "removed mesh handle rejects read");
        restore(afterCreate.position());
        ArtMeshAuthoringSnapshot redone = model.drawables().find(id).authoringSnapshot();
        report.data.put("createdRedo", NativeAssertions.snapshot(redone));
        report.check("createRedo", createdSnapshot.equals(redone), "Redo restores GUIDs and full stored snapshot");
        current();
        var x = new ParameterId("ParamMI3DN2X"); var y = new ParameterId("ParamMI3DN2Y");
        model.parameters().createMany(List.of(
            new ParameterDefinition(x, "MI3D N2 X", -1, 0, 1, ParameterType.NORMAL, false),
            new ParameterDefinition(y, "MI3D N2 Y", -1, 0, 1, ParameterType.NORMAL, false)));
        var target = ParameterBindingTarget.artMesh(id);
        for (ParameterId parameter : List.of(x, y)) {
            current();
            model.parameterBindings(parameter).bind(target, NativeAssertions.KEYS.stream()
                .map(v -> new ParameterBindingPoint(new ParameterBindingPointId("N2_" + v), v)).toList());
        }
        HistorySnapshot gridHistory = history(); report.data.put("historyGrid", historyData(gridHistory));
        report.data.put("historyGridEntryIds", historyEntryIds(gridHistory));
        var grid = model.drawables().find(id).authoringSnapshot();
        report.data.put("grid", NativeAssertions.snapshot(grid));
        NativeAssertions.grid(grid, x.value(), y.value());
        report.check("gridTuples", true, "two declared axes with all nine complete tuples and unchanged geometry");
        report.data.put("gridCounts", counts(model));
        report.data.put("historyAfterGridRead", historyData(history()));
        report.check("gridReadNoChange", gridHistory.equals(history()), "grid read leaves history unchanged");
        restore(initialHistory.position());
        report.data.put("undoCounts", counts(model));
        report.check("allUndo", counts(model).equals(initialCounts), "Undo restores all initial object counts");
        restore(gridHistory.position());
        var gridRedo = model.drawables().find(id).authoringSnapshot();
        report.data.put("gridRedo", NativeAssertions.snapshot(gridRedo));
        report.check("allRedo", grid.equals(gridRedo), "Redo restores all grid and form identities/content");
        // Same-time independent anchor: native Undo may lazily populate old entries' redo payloads.
        HistorySnapshot gridRedoHistory = history();
        report.data.put("historyGridRedo", historyData(gridRedoHistory));
        report.data.put("historyGridRedoEntryIds", historyEntryIds(gridRedoHistory));
        current();
        OrdinaryWriteProbe.run(this::current, this::history, this::restore,
            context.cubism().authoringTransactions(), id, x, y, report.ordinaryWrite);
        restore(initialHistory.position());
        report.data.put("restoredCounts", counts(model)); report.data.put("historyRestored", historyData(history()));
        report.check("restored", counts(model).equals(initialCounts), "final native Undo restores initial model counts");
    }

    private static List<String> historyEntryIds(HistorySnapshot h) {
        return h.entries().stream().map(e -> e.entryId().map(i -> i.value()).orElse(null)).toList();
    }

    private CubismModel current() {
        NativeAssertions.require(active, "plugin scope inactive");
        NativeAssertions.require(SwingUtilities.isEventDispatchThread(), "task access requires EDT");
        DocumentBinding binding = observeDocument(() -> context.cubism().activeDocument().orElse(null),
            () -> context.cubism().model().active(), null);
        requireTaskBinding(binding, taskDocument, taskSnapshotModel, taskModel,
            diagnostic -> report.data.putIfAbsent("firstTaskIdentityFailure", diagnostic));
        return binding.model();
    }

    /** Record the same observation that rejects access; never re-read or adopt a new binding. */
    static void requireTaskBinding(DocumentBinding binding, String documentId, String snapshotModelId,
            String editorModelId, java.util.function.Consumer<Map<String,Object>> rejected) {
        String failure = !binding.ready() ? "active document/model is unstable; no writes permitted"
            : !documentId.equals(binding.document().documentId())
                || !snapshotModelId.equals(binding.document().model().orElseThrow().modelId())
                ? "active document/snapshot model changed; no writes permitted"
            : !editorModelId.equals(binding.editorModelId()) ? "active live model changed; no writes permitted" : null;
        if (failure == null) return;
        var diagnostic = new LinkedHashMap<String,Object>();
        diagnostic.put("protocol", "TASK_IDENTITY_FAILURE_V1");
        diagnostic.put("expected", Map.of("documentId", documentId, "snapshotModelId", snapshotModelId, "editorModelId", editorModelId));
        diagnostic.put("observed", binding.diagnostic());
        diagnostic.put("failure", failure);
        diagnostic.put("observedAtEpochMillis", System.currentTimeMillis());
        rejected.accept(diagnostic);
        throw new IllegalStateException(failure);
    }

    private HistorySnapshot history() { current(); return context.cubism().history().snapshot(); }

    private void restore(int position) {
        current();
        var h = history();
        NativeAssertions.require(h.documentBindingId().equals(initialHistory.documentBindingId())
            && h.managerBindingId().equals(initialHistory.managerBindingId())
            && context.cubism().history().isCurrentBinding(h), "history no longer belongs to this task model");
        if (h.position() != position) context.cubism().history().moveTo(h, position);
        NativeAssertions.require(history().position() == position, "history move did not reach requested position");
    }

    private DocumentBinding awaitDocument(String differentFrom, String phase) throws Exception {
        long deadline = System.nanoTime() + 90_000_000_000L;
        int attempts = 0;
        while (active && System.nanoTime() < deadline) {
            var state = new LinkedHashMap<String, Object>();
            state.put("phase", phase); state.put("attempts", ++attempts); state.put("reason", "OBSERVING");
            report.data.put("documentWait" + phase, state);
            final DocumentBinding binding;
            try {
                binding = edt(() -> observeDocument(() -> {
                    DocumentSnapshot document = context.cubism().activeDocument().orElse(null);
                    state.putAll(new DocumentBinding(document, null, null, "OBSERVING").diagnostic());
                    return document;
                }, () -> {
                    CubismModel model = context.cubism().model().active();
                    state.put("editorModelId", model.id().value()); return model;
                }, differentFrom));
            } catch (Exception failure) {
                state.put("reason", "OBSERVATION_ERROR"); state.put("error", failure.toString()); throw failure;
            }
            state.putAll(binding.diagnostic());
            if (binding.ready()) return binding;
            Thread.sleep(250);
        }
        throw new IllegalStateException("model document did not become available: phase=" + phase
            + " last=" + report.data.get("documentWait" + phase));
    }

    static boolean stableDocumentIdentity(DocumentSnapshot before, String editorId,
            DocumentSnapshot after, String editorAfter, String differentFrom) {
        // Compare within each namespace, never model-session-* to native GUID.
        return modelDocument(before) && modelDocument(after) && editorId != null && !editorId.isBlank()
            && !before.documentId().equals(differentFrom) && before.documentId().equals(after.documentId())
            && before.model().orElseThrow().modelId().equals(after.model().orElseThrow().modelId())
            && editorId.equals(editorAfter);
    }

    private static boolean modelDocument(DocumentSnapshot document) {
        return document != null && document.isModelDocument() && document.model().isPresent();
    }

    static DocumentBinding observeDocument(java.util.function.Supplier<DocumentSnapshot> documents,
            java.util.function.Supplier<CubismModel> models, String differentFrom) {
        DocumentSnapshot before = documents.get();
        if (!modelDocument(before)) return new DocumentBinding(before, null, null, "NO_MODEL_DOCUMENT");
        if (before.documentId().equals(differentFrom)) return new DocumentBinding(before, null, null, "WAITING_NEW_DOCUMENT");
        CubismModel model = models.get(); String editorId = model.id().value();
        DocumentSnapshot after = documents.get(); String editorAfter = models.get().id().value();
        boolean stable = stableDocumentIdentity(before, editorId, after, editorAfter, differentFrom);
        return new DocumentBinding(before, stable ? model : null, editorId, stable ? "READY" : "IDENTITY_CHANGED");
    }

    record DocumentBinding(DocumentSnapshot document, CubismModel model, String editorModelId, String reason) {
        boolean ready() { return reason.equals("READY"); }
        Map<String, Object> diagnostic() {
            var fields = new LinkedHashMap<String, Object>(); fields.put("reason", reason);
            fields.put("documentId", document == null ? null : document.documentId());
            fields.put("documentPresent", document != null);
            fields.put("kind", document == null ? null : document.kind().name());
            fields.put("modelPresent", document != null && document.model().isPresent());
            fields.put("snapshotModelId", document == null ? null : document.model().map(m -> m.modelId()).orElse(null));
            fields.put("editorModelId", editorModelId); return fields;
        }
    }

    private static Map<String, Object> counts(CubismModel m) {
        return Map.of("meshes", m.drawables().all().size(), "deformers", m.deformers().all().size(),
            "parameters", m.parameters().all().size(), "parts", m.parts().all().size());
    }

    private static Map<String, Object> identity(DocumentBinding binding) {
        DocumentSnapshot d = binding.document();
        return Map.of("documentId", d.documentId(), "modelId", d.model().orElseThrow().modelId(),
            "editorModelId", binding.editorModelId(), "kind", d.kind().name());
    }

    static Map<String, Object> historyData(HistorySnapshot h) {
        var data = new LinkedHashMap<String, Object>();
        data.put("availability", h.availability().name()); data.put("generation", h.generation());
        data.put("revision", h.revision()); data.put("position", h.position());
        data.put("documentBindingId", h.documentBindingId()); data.put("managerBindingId", h.managerBindingId());
        data.put("entries", h.entries().stream().map(Object::toString).toList()); return data;
    }

    static <T> T edt(Callable<T> operation) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return operation.call();
        FutureTask<T> task = new FutureTask<>(operation);
        SwingUtilities.invokeAndWait(task); return task.get();
    }
}
