package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.command.*;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import java.util.function.Supplier;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.plugin.*;
import java.awt.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Predicate;
import javax.swing.*;

/** Isolated task driver of the installed production plugin's actual Swing flow. */
public final class UserEntryProbe implements TurboismPlugin {
    private static final String CUBE_SHA="dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859";
    private static final String APPLE_SHA="3b31ef1167d6aa75c5746906838ad1e37447718ece116512c187337be0f87999";
    private boolean apple;
    private boolean rejection;
    private boolean surface;
    private boolean seam;
    private boolean surfaceSave;
    private boolean surfaceReopen;
    private boolean appleEditability;
    private boolean surfaceAtlasSave;
    private boolean surfaceAtlasReopen;
    private boolean appleSave;
    private boolean compact;
    private boolean appleReopen;
    private boolean appleExport;
    private boolean appleAtlasSave;
    private boolean appleAtlasReopen;
    private PluginContext context;
    private volatile boolean active;
    private Thread worker;
    private String document;
    private Target target;
    private final Map<String,Object> report=new LinkedHashMap<>();

    private static final List<String> ADMITTED_HOST_VERSIONS=List.of(
        "5000","5001","5002","5003","5004","5005","5006",
        "5100","5101","5102","5103",
        "5200","5201","5202","5203",
        "5300","5301","5302","5303","5304");

    @Override public void init(PluginContext context) { this.context=context; }
    @Override public void enable() {
        String run=System.getProperty("turboism.validation.runId","");
        require(run.matches("queue-[0-9a-f]{32}") && ADMITTED_HOST_VERSIONS.contains(System.getProperty("turboism.validation.hostVersion")),"exact task required");
        require(worker==null,"one task per process");
        apple=UserEntryProbe.class.getResource("apple.glb")!=null;
        rejection="menu-reject".equals(System.getProperty("turboism.validation.mi3d.mode"));
        require(!rejection || !apple,"dedicated rejection input profile required");
        surface=UserEntryProbe.class.getResource("surface.glb")!=null;
        String mode=System.getProperty("turboism.validation.mi3d.mode", "");
        appleEditability="apple-menu-editability-compact".equals(mode);
        require(!appleEditability || (apple && !surface && !rejection),"dedicated apple editability profile required");
        seam=List.of("seam-menu", "seam-menu-save", "seam-menu-reopen", "seam-menu-atlas-save", "seam-menu-atlas-reopen").contains(mode);
        surfaceSave=List.of("plane-menu-save", "seam-menu-save").contains(mode);
        surfaceAtlasSave=List.of("plane-menu-atlas-save", "seam-menu-atlas-save").contains(mode);
        surfaceAtlasReopen=List.of("plane-menu-atlas-reopen", "seam-menu-atlas-reopen").contains(mode);
        surfaceReopen=surfaceAtlasSave || surfaceAtlasReopen || List.of("plane-menu-reopen", "seam-menu-reopen").contains(mode);
        require(!surfaceSave || surface,"surface save requires frozen surface input");
        require(!surfaceReopen || surface,"surface reopen requires frozen surface input");
        require(!surface || (!apple && !rejection && (seam || List.of("plane-menu", "plane-menu-save", "plane-menu-reopen", "plane-menu-atlas-save", "plane-menu-atlas-reopen").contains(mode))),"exact surface profile required");
        require(!(surfaceSave || surfaceAtlasSave) || "true".equals(System.getProperty("turboism.validation.mi3d.persistenceSave")),"explicit surface save opt-in required");
        appleAtlasSave="apple-menu-atlas-save-compact".equals(System.getProperty("turboism.validation.mi3d.mode"));
        appleAtlasReopen="apple-menu-atlas-reopen-compact".equals(System.getProperty("turboism.validation.mi3d.mode"));
        appleExport=appleAtlasSave || "apple-menu-export-compact".equals(System.getProperty("turboism.validation.mi3d.mode"));
        appleReopen=appleExport || appleAtlasReopen || "apple-menu-reopen-compact".equals(System.getProperty("turboism.validation.mi3d.mode"));
        boolean applePlacement="apple-menu-placement-compact".equals(mode);
        require(!applePlacement || (apple && Boolean.getBoolean("turboism.validation.mi3d.placementProbe")
            && Boolean.getBoolean("turboism.validation.mi3d.xyzProbe")
            && !Boolean.getBoolean("turboism.validation.mi3d.persistenceSave")),"dedicated apple placement profile");
        compact=List.of("apple-xyz-export","apple-xyz-export-bytes","apple-all-mesh-edit").contains(mode) || appleReopen || applePlacement || "apple-menu-save-compact".equals(mode);
        appleSave=!List.of("apple-xyz-export","apple-xyz-export-bytes","apple-all-mesh-edit").contains(mode) && !appleReopen && !applePlacement && (compact || "apple-menu-save".equals(mode));
        require(!appleReopen || apple,"reopen requires apple profile");
        require(!appleSave || apple,"apple save requires frozen apple input");
        require(!(appleSave || appleAtlasSave) || "true".equals(System.getProperty("turboism.validation.mi3d.persistenceSave")),"explicit apple save opt-in required");
        report.put("schemaVersion",surface?"mi3d-surface-menu-v1":rejection?"mi3d-menu-rejection-v1":appleAtlasReopen?"mi3d-apple-menu-atlas-reopen-v1":appleExport?"mi3d-apple-menu-export-v1":appleReopen?"mi3d-apple-menu-reopen-v1":compact?"mi3d-apple-menu-save-compact-v1":appleSave?"mi3d-apple-menu-save-v1":apple?"mi3d-apple-user-entry-v1":"mi3d-user-entry-v1");report.put("runId",run);
        report.put("scope",surface?"REAL_MENU_IMPORT_UNDO_REDO_SOURCE_RENDER":rejection?"REAL_MENU_PREAUTHORING_REJECTION_ONLY":appleAtlasReopen?"OBSERVE_REOPENED_PACKED_SEED_ONLY":appleExport?"OFFICIAL_ATLAS_MOC_CAPTURE_FROM_REOPENED_SEED":appleReopen?"OBSERVE_REOPENED_SEED_ONLY":appleSave?"REAL_MENU_IMPORT_SAVE_ONLY":"REAL_MENU_IMPORT_UNDO_REDO_ONLY");
        if(surfaceReopen) {
            report.put("schemaVersion",surfaceAtlasReopen?"mi3d-surface-menu-atlas-reopen-v1":"mi3d-surface-menu-reopen-v1");
            report.put("scope",surfaceAtlasReopen?"OBSERVE_REOPENED_PACKED_SURFACE_SEED_ONLY":"OBSERVE_REOPENED_SURFACE_SEED_ONLY");
        }
        if(appleEditability) {
            report.put("schemaVersion","mi3d-apple-editability-observation-v1");
            report.put("scope","READ_ONLY_REOPENED_APPLE_EDITABILITY_FLAGS_NOT_UI_ACCEPTANCE");
        }
        report.put("textureLayout","EDGE_PADDED_POT");
        active=true;worker=new Thread(this::run,"mi3d-user-entry");worker.setDaemon(true);worker.start();
        context.logger().info("MI3D_UI_READY");
    }
    @Override public void disable() { active=false;if(worker!=null)worker.interrupt(); }
    @Override public void shutdown() { disable(); }

    private void run() {
        String status="FAIL";
        Path state=context.paths().stateDir();
        try {
            if(apple && Boolean.getBoolean("turboism.validation.mi3d.xyzProbe")) {
                Thread watchdog=new Thread(()->{
                    try {
                        for(int i=0;i<60 && active;i++) {
                            Thread.sleep(10000);
                            var text=new StringBuilder("sample "+i+" "+java.time.Instant.now()+"\n");
                            for(var entry:Thread.getAllStackTraces().entrySet())
                                if(entry.getKey().getName().startsWith("AWT-EventQueue")) {
                                    text.append(entry.getKey().getName()).append(' ').append(entry.getKey().getState()).append('\n');
                                    for(var frame:java.util.Arrays.stream(entry.getValue()).limit(24).toList())text.append(frame).append('\n');
                                }
                            Files.writeString(state.resolve("xyz-edt-stack.txt"),text,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                        }
                    } catch(Exception failure) {context.logger().warn("bounded XYZ diagnostic stopped: "+failure);}
                },"mi3d-bounded-edt-observer");watchdog.setDaemon(true);watchdog.start();
            }
            await(()->Files.isRegularFile(state.resolve("exerciser.flag")),600,"queue trigger");
            String interpolationMode=System.getProperty("turboism.validation.mi3d.mode","");
            if("apple-all-mesh-edit".equals(interpolationMode)) {
                require(apple && !surface && !rejection,"dedicated all-chart Apple input");
                target=awaitDocument(null,"all-mesh-edit-seed");document=target.documentId();
                report.put("schemaVersion","mi3d-apple-all-mesh-edit-v1");report.put("scope","ALL_98_ORIGINAL_MESH_NATIVE_EDITOR_ENTRY_EXIT_RESTORE_NATIVE_COMMIT_WITHOUT_VERTEX_WRITES");report.put("inputSha256",APPLE_SHA);
                if(Boolean.getBoolean("turboism.validation.mi3d.equivalentSlotProbe"))runEquivalentSlotEditingProbe();
                else runAllAppleMeshEditors();status="PASS";return;
            }
            if("apple-derived-save".equals(interpolationMode) || "apple-derived-reopen".equals(interpolationMode)) {
                require(apple && !surface && !rejection,"dedicated derived persistence probe");
                target=awaitDocument(null,"derived-persistence");document=target.documentId();
                for(var flag:List.of("equivalentSlotProbe","partitionBoundProbe","meshEditingProbe","xyzProbe"))
                    require(!Boolean.getBoolean("turboism.validation.mi3d."+flag),"derived persistence cannot combine editor probes");
                if("apple-derived-save".equals(interpolationMode)) {
                    require(Boolean.getBoolean("turboism.validation.mi3d.persistenceSave"),"dedicated derived save opt-in");
                    byte[] raw;try(var input=UserEntryProbe.class.getResourceAsStream("derived-bound-all98.bin")) {require(input!=null,"derived fixture required");raw=input.readAllBytes();}
                    require(AppleDerivedCapture.knownFixture(hash(raw)),"exact all98 derived fixture");runDerivedSaveProbe(raw);
                } else {
                    require(!Boolean.getBoolean("turboism.validation.mi3d.persistenceSave"),"derived reopen cannot save");
                    report.put("schemaVersion","mi3d-apple-derived-reopen-v1");report.put("scope","FRESH_EDITOR_FULL98_DERIVED_BANK_AND_FINITE_XYZ_FRAMES_NOT_FINAL_ACCEPTANCE");
                    var history=edt(()->context.cubism().history().snapshot());
                    var bank=edt(this::xyzState);requireDerivedInventory(bank);report.put("reopenedState",bank);
                    require(history.equals(edt(()->context.cubism().history().snapshot())),"derived read-only source capture changed history");
                    report.put("sourceReadHistoryUnchanged",true);captureXYZFrames(context.paths().stateDir(),bank);
                    require(bank.equals(edt(this::xyzState)),"derived reopen pose capture changed source bank");
                    var afterHistory=edt(()->context.cubism().history().snapshot());
                    require(afterHistory.position()==history.position()&&afterHistory.documentBindingId().equals(history.documentBindingId())&&afterHistory.managerBindingId().equals(history.managerBindingId()),"derived pose history position/document not restored");
                    report.put("historyBeforePoses",AppleTextureCapture.history(history));report.put("historyAfterPoses",AppleTextureCapture.history(afterHistory));
                    report.put("sourceUnchanged",true);report.put("historyPositionRestored",true);report.put("fullAppleEditingAccepted",false);
                    report.put("saveReopen","REOPENED_REQUIRES_INDEPENDENT_FULL98_PAIR_AND_PIXELS");
                }
                status="PASS";return;
            }
            if("apple-derived-edit".equals(interpolationMode)) {
                require(apple && !surface && !rejection,"dedicated full Apple derived source probe");
                for(var flag:List.of("persistenceSave","equivalentSlotProbe","partitionBoundProbe","meshEditingProbe","xyzProbe"))
                    require(!Boolean.getBoolean("turboism.validation.mi3d."+flag),"derived probe cannot combine other actions");
                target=awaitDocument(null,"derived-edit");document=target.documentId();
                byte[] raw;try(var input=UserEntryProbe.class.getResourceAsStream("derived-bound-all98.bin")) {
                    require(input!=null,"full original-face derived fixture required");raw=input.readAllBytes();
                }
                require(hash(raw).equals(AppleDerivedCapture.SHA),"exact independently verified derived fixture");
                runDerivedEditingProbe(raw);status="PASS";return;
            }
            if("apple-source-triangulation".equals(interpolationMode)) {
                require(apple && !surface && !rejection,"dedicated Apple source query");
                for(var flag:List.of("persistenceSave","equivalentSlotProbe","partitionBoundProbe","meshEditingProbe","xyzProbe"))
                    require(!Boolean.getBoolean("turboism.validation.mi3d."+flag),"source query must be read-only");
                target=awaitDocument(null,"source-triangulation");document=target.documentId();
                report.put("schemaVersion","mi3d-native-source-triangulation-query-v1");
                report.put("scope","DETACHED_SOURCE_TRIANGULATION_SDK_WITNESS_NOT_NATIVE_EDITOR_OR_IMPORT_ACCEPTANCE");
                var bank=edt(this::xyzState);var history=edt(()->context.cubism().history().snapshot());
                var source=edt(()->current().drawables().find(ApplePartitionBoundCapture.ORIGINAL).authoringSnapshot());
                byte[] raw;try(var stream=UserEntryProbe.class.getResourceAsStream("equivalent-chart26.bin")){require(stream!=null,"exact source partition fixture required");raw=stream.readAllBytes();}
                require(hash(raw).equals("906624bccb601f507a1531a9518c4efe9ab58027ac5a7eb9755ac8a0ba6fb1e1"),"reviewed source fixture required");
                var inputs=new LinkedHashMap<String,dev.turboism.sdk.cubism.model.ArtMeshGeometry>();
                inputs.put("original-chart0",edt(()->current().drawables().find(new dev.turboism.sdk.cubism.id.ArtMeshId("ArtMesh3D0")).authoringSnapshot().sourceGeometry()));
                inputs.put("original-chart26",source.sourceGeometry());
                var groups=ApplePartitionBoundCapture.parse(raw,source);for(int g=0;g<groups.size();g++)inputs.put("partition26-"+g,groups.get(g).geometry());
                var rows=new ArrayList<Map<String,Object>>();report.put("queries",rows);
                for(var item:inputs.entrySet()) {
                    var geometry=item.getValue();var result=edt(()->current().drawables().previewSourceTriangulation(geometry));
                    boolean points=geometry.positions().equals(result.positions());
                    boolean faces=orientedFaces(geometry.triangleIndices()).equals(orientedFaces(result.triangleIndices()));
                    rows.add(Map.of("id",item.getKey(),"inputPositions",geometry.positions().stream().map(v->List.of(v.x(),v.y())).toList(),"inputIndices",geometry.triangleIndices(),
                        "outputPositions",result.positions().stream().map(v->List.of(v.x(),v.y())).toList(),"outputIndices",result.triangleIndices(),"pointsExact",points,"orientedFacesExact",faces));
                    if(!item.getKey().equals("original-chart26"))require(points && faces,"expected exact native query changed "+item.getKey());
                    else require(!points || !faces,"original folded source failure concealed");
                }
                require(bank.equals(edt(this::xyzState)) && history.equals(edt(()->context.cubism().history().snapshot())),"source query changed source/history");
                report.put("sourceUnchanged",true);report.put("historyUnchanged",true);report.put("nativeMeshEditingAccepted",false);report.put("fullAppleEditingAccepted",false);
                status="PASS";return;
            }
            if("apple-bound-partition-reopen".equals(interpolationMode)) {
                require(apple && !surface && !rejection,"dedicated bound Apple reopen input");
                for(var flag:List.of("persistenceSave","equivalentSlotProbe","partitionBoundProbe","meshEditingProbe","xyzProbe"))
                    require(!Boolean.getBoolean("turboism.validation.mi3d."+flag),"bound reopen must be read-only");
                target=awaitDocument(null,"bound-partition-reopen");document=target.documentId();
                report.put("schemaVersion","mi3d-apple-bound-partition-reopen-v1");
                report.put("scope","READ_ONLY_FRESH_EDITOR_BOUND_CHART26_SAVED_SOURCE_BANK_NOT_FULL_APPLE_ACCEPTANCE");
                var history=edt(()->context.cubism().history().snapshot());
                var bank=edt(this::xyzState);requireXYZWarpBank(bank);report.put("reopenedState",bank);
                edt(()->{
                    var all=current().drawables().all();
                    require(all.size()==102 && all.stream().noneMatch(d->d.id().equals(ApplePartitionBoundCapture.ORIGINAL)),"exact saved partition inventory");
                    require(all.stream().mapToInt(d->d.authoringSnapshot().sourceGeometry().triangleIndices().size()/3).sum()==150784,"all saved original faces required");
                    var groups=all.stream().filter(d->d.id().value().startsWith("ArtMeshAppleChart26EditableGroup")).toList();
                    require(groups.size()==5,"exact five saved groups required");
                    report.put("reopenedPartitionMeshes",groups.stream().map(d->NativeAssertions.snapshot(d.authoringSnapshot())).toList());
                    return null;
                });
                report.put("reopenedNeutralPixelSha256",edt(()->pixelHash(current().renderPixels(1024,1024).argb())));
                require(bank.equals(edt(this::xyzState)) && history.equals(edt(()->context.cubism().history().snapshot())),"read-only bound reopen changed state/history");
                report.put("stateUnchanged",true);report.put("historyUnchanged",true);report.put("fullAppleEditingAccepted",false);
                status="PASS";return;
            }
            if(List.of("apple-xyz-export","apple-xyz-export-bytes","apple-derived-export","apple-derived-atlas-undo").contains(interpolationMode)) {
                require(apple && !surface && !rejection && !Boolean.getBoolean("turboism.validation.mi3d.persistenceSave"),"dedicated Apple XYZ export");
                target=awaitDocument(null,"xyz-export-seed");document=target.documentId();
                report.put("schemaVersion",interpolationMode.endsWith("-bytes")?"mi3d-apple-xyz-export-bytes-v1":"mi3d-apple-xyz-export-v1");report.put("scope",interpolationMode.endsWith("-bytes")?"OFFICIAL_XYZ_MOC_BYTE_RESULT_DIAGNOSTIC_WITHOUT_REPEATING_PRIOR_FRAMES":"REOPENED_XYZ_APPLE_ALL_MESH_FLAGS_OFFICIAL_ATLAS_EXPORT_NATIVE_FRAMES");report.put("inputSha256",APPLE_SHA);
                var seed=edt(()->{observeAppleMeshFlags();var value=xyzState();requireXYZWarpBank(value);return value;});report.put("imported",seed);
                if("apple-derived-atlas-undo".equals(interpolationMode)) {
                    requireDerivedInventory(seed);
                    require(((List<?>)seed.get("imageGroups")).size()==71,"exact unique derived image inventory");
                    report.put("schemaVersion","mi3d-apple-derived-atlas-undo-v1");report.put("scope","MULTIPAGE_ATLAS_UNDO_ONLY_NO_EXPORT_OR_POSE_CAPTURE");
                    AppleOfficialExport.runDerivedAtlasUndo(this::current,this::xyzState,()->context.cubism().history().snapshot(),
                        count->context.cubism().history().undo(count),context.cubism().textureAtlasLayouts(),context.editorCommands(),state,report);
                } else if("apple-derived-export".equals(interpolationMode)) {
                    requireDerivedInventory(seed);
                    require(((List<?>)seed.get("imageGroups")).size()==71,"exact cropped unique images plus original material library required");
                    report.put("schemaVersion","mi3d-apple-derived-export-v1");report.put("scope","FULL98_CROPPED_DERIVED_OFFICIAL_MULTIPAGE_ATLAS_MOC_AND_XYZ_FRAMES_NOT_CORE_ACCEPTANCE");
                    AppleOfficialExport.runDerivedXYZ(this::current,this::xyzState,()->context.cubism().history().snapshot(),
                        count->context.cubism().history().undo(count),context.cubism().textureAtlasLayouts(),context.editorCommands(),state,report,
                        ()->{var packed=edt(this::xyzState);report.put("packed",packed);captureXYZFrames(state,packed);});
                } else AppleOfficialExport.runXYZ(this::current,this::xyzState,()->context.cubism().history().snapshot(),
                    count->context.cubism().history().undo(count),context.cubism().textureAtlasLayouts(),context.editorCommands(),state,report,
                    ()->{var packed=edt(this::xyzState);report.put("packed",packed);if(interpolationMode.endsWith("-bytes"))report.put("frameCapture","NOT_REPEATED_PRIOR_R1_FRAMES_FROZEN_SEPARATELY");else captureXYZFrames(state,packed);});
                require(seed.equals(edt(this::xyzState)),"XYZ export source not restored");status="PASS";return;
            }
            if(List.of("cat-native-serialization","cat-default-serialization").contains(interpolationMode)) {
                require(!apple && !surface && !rejection && ("cat-default-serialization".equals(interpolationMode) == Boolean.getBoolean("turboism.validation.mi3d.persistenceSave")),"dedicated native Cat serialization");
                target=awaitDocument(null,"cat-native-serialization-seed");document=target.documentId();
                runCatNativeSerialization(state);status="PASS";return;
            }
            if(List.of("cat-vertex-drag","cat-optimized-drag","cat-optimized-short-drag","cat-adaptive-short-drag","cat-cost-drag").contains(interpolationMode)) {
                require(!apple && !surface && !rejection,"dedicated Cat drag profile");
                target=awaitDocument(null,"cat-drag-seed");document=target.documentId();
                runCatNativeDrags(state);status="PASS";return;
            }
            if("cat-vertex-drag-layout".equals(interpolationMode)) {
                require(!apple && !surface && !rejection,"dedicated Cat drag layout profile");
                target=awaitDocument(null,"cat-drag-layout-seed");document=target.documentId();
                runCatDragLayout(state);status="PASS";return;
            }
            if("cat-vertex-edit".equals(interpolationMode)) {
                require(!apple && !surface && !rejection && !Boolean.getBoolean("turboism.validation.mi3d.persistenceSave"),"dedicated Cat native editor diagnosis without save");
                report.put("schemaVersion","mi3d-cat-native-edit-diagnostic-v2");
                report.put("scope","THREE_PARTITION_NATIVE_COMMIT_FIELD_DIFF_RESTORE_NO_SAVE_NO_POSES_NOT_EDIT_ACCEPTANCE");
                report.put("inputSha256","4d0688298a366bfd5501918a7afac5dfd4f6aa35445a4f73077435045ba02ee7");
                target=awaitDocument(null,"cat-edit");document=target.documentId();
                runCatNativeEditors();status="PASS";return;
            }
            if("cat-vertex-bank".equals(interpolationMode)) {
                require(!apple && !surface && !rejection && !Boolean.getBoolean("turboism.validation.mi3d.persistenceSave"),"dedicated read-only Cat bank diagnosis");
                report.put("schemaVersion","mi3d-cat-vertex-bank-diagnostic-v1");
                report.put("scope","READ_ONLY_EXACT_CAT_BANK_GENERATION_DIAGNOSIS_NO_FRAMES_OR_SAVE");
                report.put("inputSha256","4d0688298a366bfd5501918a7afac5dfd4f6aa35445a4f73077435045ba02ee7");
                target=awaitDocument(null,"cat-bank");document=target.documentId();
                report.put("bankDiagnostic",edt(()->{CatVertexCapture.inventory(current());return CatVertexCapture.generationDiagnostic(current());}));
                status="PASS";return;
            }
            if(List.of("cat-vertex-reopen","cat-optimized-reopen").contains(interpolationMode)) {
                boolean optimized="cat-optimized-reopen".equals(interpolationMode);
                require(!apple && !surface && !rejection && !Boolean.getBoolean("turboism.validation.mi3d.persistenceSave"),"dedicated read-only Cat reopen");
                report.put("schemaVersion",optimized?"mi3d-cat-optimized-reopen-v1":"mi3d-cat-vertex-reopen-v1");
                report.put("scope","FRESH_EDITOR_CAT_PERSISTENCE_BANK_AND_NINE_NATIVE_FRAMES");
                report.put("inputSha256","4d0688298a366bfd5501918a7afac5dfd4f6aa35445a4f73077435045ba02ee7");
                if(optimized)report.put("inputSha256","ed1a27af5ee2724849d8c6ed3e76322579c3c88d92ecb11ffd99213ee0125d11");
                target=awaitDocument(null,"cat-reopen");document=target.documentId();
                var bank=edt(()->{if(optimized)CatVertexCapture.optimizedInventory(current());else CatVertexCapture.inventory(current());return CatVertexCapture.persistentBank(current());});
                report.put("persistentBank",bank);
                CatVertexCapture.frames(context,this::current,state,report);
                require(bank.equals(edt(()->CatVertexCapture.persistentBank(current()))),"Cat reopen frames changed source bank");
                status="PASS";return;
            }
            if(List.of("cat-vertex-menu","cat-vertex-frames","cat-vertex-save","cat-optimized-save","cat-adaptive-save","cat-cost-save").contains(interpolationMode)) {
                require(!apple && !surface && !rejection && UserEntryProbe.class.getResource(catInputResource(interpolationMode))!=null,"dedicated Cat profile");
                runCatVertexImport(state,"cat-vertex-menu".equals(interpolationMode));status="PASS";return;
            }
            if(List.of("interpolation-witness-save","interpolation-witness-reopen").contains(interpolationMode)) {
                require(!apple && !surface && !rejection,"dedicated interpolation persistence profile");
                boolean save=interpolationMode.endsWith("-save");
                Target seed=awaitDocument(null,"seed");
                if(save) {
                    require(Boolean.getBoolean("turboism.validation.mi3d.persistenceSave"),"explicit save opt-in");
                    edt(()->{require(seed.sameIdentity(observe()),"seed changed");executed(context.editorCommands().execute(EditorCommand.NEW_MODEL));return null;});
                    target=awaitDocument(seed.documentId(),"new");document=target.documentId();
                    edt(()->{executed(context.editorCommands().execute(new EditorCanvasSettingsRequest(1024,1024)));return null;});
                } else {target=seed;document=seed.documentId();}
                report.put("schemaVersion","mi3d-native-interpolation-persistence-v1");report.put("scope","EDITOR_INTERPOLATION_PERSISTENCE_ONLY");
                report.put("interpolationPersistence",edt(()->NativeInterpolationWitness.persistenceState(context,this::current,save)));
                if(save){var beforeBank=edt(()->current().drawables().all().get(0).authoringSnapshot());
                    var artifact=ApplePersistenceFile.save(context.userFiles(),context.editorCommands(),state,()->{require(current().drawables().all().get(0).authoringSnapshot().equals(beforeBank),"save source changed");},ApplePersistenceFile.Profile.INTERPOLATION);
                    report.put("savedArtifact",Map.of("relativePath",artifact.relativePath(),"bytes",artifact.bytes(),"sha256",artifact.sha256()));
                    require(beforeBank.equals(edt(()->current().drawables().all().get(0).authoringSnapshot())),"SAVE_AS changed source");}
                status="PASS";return;
            }
            if("interpolation-witness".equals(System.getProperty("turboism.validation.mi3d.mode"))) {
                require(!apple && !surface && !rejection,"dedicated interpolation input profile");
                Target seed=awaitDocument(null,"seed");
                edt(()->{require(seed.sameIdentity(observe()),"seed changed");executed(context.editorCommands().execute(EditorCommand.NEW_MODEL));return null;});
                target=awaitDocument(seed.documentId(),"new");document=target.documentId();
                edt(()->{executed(context.editorCommands().execute(new EditorCanvasSettingsRequest(1024,1024)));return null;});
                report.put("schemaVersion","mi3d-native-interpolation-witness-v1");report.put("scope","ACTUAL_EDITOR_SDK_INTERPOLATION_HISTORY_NOT_MODEL_ACCEPTANCE");
                report.put("interpolationWitness",edt(()->NativeInterpolationWitness.run(context,this::current,report)));
                status="PASS";return;
            }
            if(Boolean.getBoolean("turboism.validation.mi3d.xyzProbe")) {
                require(!surface && !rejection && (!apple || appleSave || appleReopen
                    || "apple-menu-placement-compact".equals(System.getProperty("turboism.validation.mi3d.mode"))),"dedicated XYZ input profile required");
                if(appleReopen || "cube-xyz-menu-reopen".equals(System.getProperty("turboism.validation.mi3d.mode"))) runXYZReopen(state);
                else runXYZImport(state);
                status="PASS";return;
            }
            if(appleEditability) {
                if(Boolean.getBoolean("turboism.validation.mi3d.originalMeshReopen")) {
                    require(!Boolean.getBoolean("turboism.validation.mi3d.meshEditingProbe")
                        && !Boolean.getBoolean("turboism.validation.mi3d.persistenceSave"),"mesh reopen must be read-only");
                    runAppleEditabilityLight();
                    report.put("scope","READ_ONLY_COMMITTED_APPLE_FULL_AUTHORING_BANK");
                    report.put("reopenedCommitted",edt(this::xyzState));
                    status="PASS";return;
                }
                if(Boolean.getBoolean("turboism.validation.mi3d.meshEditingProbe")) runAppleEditabilityLight();
                else runAppleEditability();
                if(Boolean.getBoolean("turboism.validation.mi3d.meshEditingProbe")) {
                    report.put("schemaVersion","mi3d-apple-editability-observation-v3");
                    report.put("scope",Boolean.getBoolean("turboism.validation.mi3d.originalMeshEditing")
                        ?"REOPENED_DELIVERED_APPLE_ORIGINAL_CHART_EDITING_IN_TASK_MEMORY"
                        :"READ_ONLY_REOPENED_APPLE_AND_ISOLATED_FULL_CHART_EDITING_COPY");
                    runMeshEditingProbe();
                }
                status="PASS";return;
            }
            if(rejection) {runRejections(state);status="PASS";return;}
            if(surfaceReopen) {
                runSurfaceReopen(state);
                if(surfaceAtlasSave)AppleOfficialExport.runSurface(this::current,this::state,()->context.cubism().history().snapshot(),
                    count->context.cubism().history().undo(count),context.cubism().textureAtlasLayouts(),context.editorCommands(),state,report,
                    count->context.cubism().history().redo(count),this::savePacked,2);
                status="PASS";return;
            }
            if(appleReopen) {
                runReopen(state);
                if(Boolean.getBoolean("turboism.validation.mi3d.meshEditingProbe")) {
                    require(!appleExport,"editing probe requires read-only reopen capture before new-document copy");
                    runMeshEditingProbe();
                }
                if(appleExport)AppleOfficialExport.run(this::current,this::state,()->context.cubism().history().snapshot(),
                    count->context.cubism().history().undo(count),context.cubism().textureAtlasLayouts(),context.editorCommands(),state,report,
                    appleAtlasSave?count->context.cubism().history().redo(count):null,appleAtlasSave?this::savePacked:null);
                status="PASS";return;
            }
            byte[] bytes;
            try(var stream=UserEntryProbe.class.getResourceAsStream(apple?"apple.glb":surface?"surface.glb":"cube.glb")) {
                require(stream!=null,"frozen input missing");bytes=stream.readNBytes(16*1024*1024+1);
            }
            require(bytes.length<=16*1024*1024 && hash(bytes).equals(apple?APPLE_SHA:surface?(seam?"c9605e030d4f174501d8f98f798dcad3a5a3851254b347b1b80e882fee8484dd":"8aa60f903cee58d123831d6b66315b0537bafffcf68a064bcb6a0b0132d1b997"):CUBE_SHA),"authorized cube identity");
            Path input=state.resolve(apple?"authorized-apple.glb":surface?"authorized-surface.glb":"authorized-cube.glb");
            Files.write(input,bytes,StandardOpenOption.CREATE_NEW);report.put("inputSha256",hash(bytes));
            report.put("stage","waiting-seed");
            Target seedTarget=awaitDocument(null,"seed");String seed=seedTarget.documentId();
            edt(()->{require(seedTarget.sameIdentity(observe()),"seed changed before NEW_MODEL");
                executed(context.editorCommands().execute(EditorCommand.NEW_MODEL));return null;});
            report.put("stage","waiting-new-document");
            target=awaitDocument(seed,"new");document=target.documentId();
            report.put("stage","menu-import");
            report.put("seedDocumentId",seed);report.put("targetDocumentId",document);
            edt(()->{require(current().drawables().all().isEmpty(),"new document is not empty");
                executed(context.editorCommands().execute(new EditorCanvasSettingsRequest(1024,1024)));return null;});
            var baseline=edt(this::state);report.put("baseline",baseline);
            int before=edt(()->context.cubism().history().snapshot().position());

            // The menu action, production dialogs, runtime chooser and actual
            // user-file service perform the import. No import-engine call here.
            JMenuItem item=awaitComponent(JMenuItem.class,c->"导入 3D 模型…".equals(c.getText()),false);
            edt(()->{require(item.isEnabled(),"menu disabled");item.doClick();return null;});
            report.put("menuInvoked",true);
            JButton color=awaitComponent(JButton.class,c->"保留原材质".equals(c.getText()),true);
            edt(()->{color.doClick();return null;});report.put("appearance","SOURCE_MATERIAL");
            JFileChooser chooser=awaitComponent(JFileChooser.class,c->true,true);
            edt(()->{report.put("chooserTitle",chooser.getDialogTitle());require("选择 GLB、glTF、OBJ 或模型 ZIP".equals(chooser.getDialogTitle()),"unexpected chooser");
                require(!chooser.isMultiSelectionEnabled(),"multiple files enabled");
                chooser.setSelectedFile(input.toFile());chooser.approveSelection();return null;});
            report.put("realChooserApproved",true);
            JOptionPane confirm=awaitComponent(JOptionPane.class,c->title(c).equals("确认导入"),true);
            edt(()->{String text=String.valueOf(confirm.getMessage());report.put("confirmation",text);
                require(surface?text.contains("2 个曲面网格") && text.contains("面 → 导入 ") && text.contains("9 点椭圆插值") && text.contains("Z 5 点") && (seam || text.contains("源模型 2 面 → 导入 2 面")):apple?text.contains("98 个曲面网格") && text.contains("17 / 17"):text.contains("6 个网格") && text.contains("12150") && text.contains("45 / 45"),"unexpected import plan");
                require(!compact || text.contains("遮挡层序采样点：9 / 9"),"explicit compact layer grid required");
                confirm.setValue(JOptionPane.OK_OPTION);return null;});
            JOptionPane success=awaitComponent(JOptionPane.class,c->title(c).equals("3D 模型"),true);
            edt(()->{String text=String.valueOf(success.getMessage());report.put("completionMessage",text);
                require(text.startsWith("导入完成"),"import failed");success.setValue(JOptionPane.OK_OPTION);return null;});

            var imported=edt(()->{
                var model=current();require(model.drawables().all().size()==(surface?2:apple?98:6),"complete native mesh inventory required");
                var locals=new ArrayList<Map<String,Object>>();if(apple)Files.createDirectory(state.resolve("apple-locals"));
                for(var mesh:model.drawables().all()) {
                    var snapshot=mesh.authoringSnapshot();
                    require(snapshot.axes().size()==2 && snapshot.axes().stream().allMatch(a->a.keys().size()==(surface?9:apple?(compact?9:17):45))
                        && snapshot.forms().size()==(surface?81:apple?(compact?81:289):2025) && snapshot.cells().size()==(surface?81:apple?(compact?81:289):2025),"dense native bindings");
                    if(apple) {
                        String artifact="apple-locals/"+locals.size()+".xy";var local=snapshot.forms().get(0).positions();
                        try(var out=new java.io.DataOutputStream(Files.newOutputStream(state.resolve(artifact),StandardOpenOption.CREATE_NEW))){for(var point:local){out.writeFloat(point.x());out.writeFloat(point.y());}}
                        locals.add(Map.of("id",mesh.id().value(),"artifact",artifact,"sha256",pointsHash(local)));
                    }
                    require(mesh.visible() && mesh.culling() && model.textures().textureInput(mesh.id()).isPresent(),"native face resources");
                }
                if(apple)report.put("layerLocalCaptures",locals);
                require(context.cubism().history().snapshot().position()==before+1,"single import undo entry");
                return state();
            });
            report.put("imported",imported);report.put("historyBefore",before);report.put("historyAfter",before+1);
            edt(()->{context.cubism().history().undo(1);return null;});
            var undone=edt(this::state);report.put("afterUndo",undone);
            report.put("historyAfterUndo",edt(()->context.cubism().history().snapshot().position()));
            require(undone.equals(baseline),"Undo did not restore full observed baseline");report.put("undoRestored",true);
            edt(()->{context.cubism().history().redo(1);return null;});
            var redone=edt(this::state);report.put("afterRedo",redone);
            report.put("historyAfterRedo",edt(()->context.cubism().history().snapshot().position()));
            require(redone.equals(imported),"Redo did not restore imported snapshots/resources");report.put("redoRestored",true);
            report.put("stage","source-texture-render");
            if(apple)AppleTextureCapture.run(this::current,this::state,()->context.cubism().history().snapshot(),
                count->context.cubism().history().undo(count),state,report);
            else SourceTextureCapture.run(this::current,this::state,()->context.cubism().history().snapshot(),
                count->context.cubism().history().undo(count),state,report,surface?2:6);
            report.put("renderVerification","CAPTURED_REQUIRES_INDEPENDENT_ORACLE");
            if(appleSave || surfaceSave) {
                report.put("stage","native-save");
                var beforeSave=edt(this::state);
                var historyBeforeSave=edt(()->context.cubism().history().snapshot());
                var profile=surfaceSave?ApplePersistenceFile.Profile.SURFACE:ApplePersistenceFile.Profile.APPLE;
                var artifact=ApplePersistenceFile.save(context.userFiles(),context.editorCommands(),state,()->{
                    require(current()!=null && state().equals(beforeSave),"authoring changed before SAVE_AS");
                },profile);
                var afterSave=edt(this::state);
                var historyAfterSave=edt(()->context.cubism().history().snapshot());
                require(beforeSave.equals(afterSave),"SAVE_AS changed authored state/resources/parameters");
                require(historyBeforeSave.equals(historyAfterSave),"SAVE_AS changed observed history");
                report.put("persistenceSave",Map.of("commandStatus","EXECUTED","artifact",artifact.relativePath(),
                    "artifactBytes",artifact.bytes(),"artifactSha256",artifact.sha256(),"maximumBytes",profile.maximumBytes,
                    "before",beforeSave,"after",afterSave,"historyUnchanged",true,
                    "historyBefore",AppleTextureCapture.history(historyBeforeSave),"historyAfter",AppleTextureCapture.history(historyAfterSave)));
                report.put("saveReopen","SAVED_REOPEN_NOT_RUN");
            }
            if(Boolean.getBoolean("turboism.validation.mi3d.meshEditingProbe")) {
                require(appleSave && compact,"mesh editing probe requires saved compact apple");
                runMeshEditingProbe();
            }
            status="PASS";
        } catch(Throwable failure) {
            report.put("failure",failure.toString());
            var trace=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(trace));
            report.put("failureTrace",trace.toString());
        } finally {
            report.put("status",status);report.putIfAbsent("renderVerification","NOT_RUN");report.putIfAbsent("saveReopen","NOT_RUN");
            try {
                Files.writeString(state.resolve("report.json"),NativeReport.json(report)+"\n",StandardOpenOption.CREATE_NEW);
                Files.writeString(state.resolve("result.properties"),"status="+status+"\n",StandardOpenOption.CREATE_NEW);
                if(status.equals("FAIL"))context.logger().error("MI3D_UI_FAILED "+report.get("failure"));
            } catch(Exception failure) {status="FAIL";context.logger().error("MI3D_UI_REPORT_FAILED "+failure);}
            // Dedicated queue task process only; supervisor still owns containment.
            if(active) {try {Thread.sleep(2000);}catch(InterruptedException ignored) {Thread.currentThread().interrupt();}
                if(active)Runtime.getRuntime().exit(status.equals("PASS")?0:2);}
        }
    }

    static String catInputResource(String mode) { return List.of("cat-optimized-save","cat-adaptive-save","cat-cost-save").contains(mode)?"cat.zip":"cat.glb"; }

    /** Dedicated production menu witness; source pixels still need independent acceptance. */
    private void runCatVertexImport(Path state,boolean historyProbe) throws Exception {
        boolean optimized=List.of("cat-optimized-save","cat-adaptive-save","cat-cost-save").contains(System.getProperty("turboism.validation.mi3d.mode",""));
        boolean editableCost=Boolean.getBoolean("turboism.validation.mi3d.editableTopology");
        if(editableCost)require("cat-cost-save".equals(System.getProperty("turboism.validation.mi3d.mode"))
            && Boolean.getBoolean("turboism.validation.mi3d.persistenceSave")
            && !Boolean.getBoolean("turboism.validation.mi3d.externalResources"),"dedicated editable cost save profile");
        report.put("schemaVersion",historyProbe?"mi3d-cat-vertex-menu-v1":"mi3d-cat-vertex-frames-v1");
        report.put("scope",historyProbe?"REAL_MENU_SOURCE_VERTEX_IMPORT_HISTORY_NATIVE_FRAMES_NOT_MODEL_ACCEPTANCE":"REAL_MENU_SOURCE_VERTEX_IMPORT_NATIVE_FRAMES_ONLY_NO_IMPORT_UNDO_REDO_SAVE_EXPORT");
        report.put("importHistoryProbe",historyProbe);
        editingCheckpoint("cat-seed");
        byte[] bytes;try(var stream=UserEntryProbe.class.getResourceAsStream(catInputResource(System.getProperty("turboism.validation.mi3d.mode")))){require(stream!=null,"Cat resource");bytes=stream.readAllBytes();}
        require(hash(bytes).equals(optimized?"ed1a27af5ee2724849d8c6ed3e76322579c3c88d92ecb11ffd99213ee0125d11":"4d0688298a366bfd5501918a7afac5dfd4f6aa35445a4f73077435045ba02ee7"),"authorized Cat identity");
        Path input=state.resolve(optimized?"authorized-cat.zip":"authorized-cat.glb");Files.write(input,bytes,StandardOpenOption.CREATE_NEW);report.put("inputSha256",hash(bytes));
        boolean external=Boolean.getBoolean("turboism.validation.mi3d.externalResources");
        var externalFiles=new LinkedHashMap<String,Path>();
        if(external) {
            require("cat-cost-save".equals(System.getProperty("turboism.validation.mi3d.mode")),"dedicated external Cat resource profile");
            try(var archive=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
                for(java.util.zip.ZipEntry entry;(entry=archive.getNextEntry())!=null;)if(!entry.isDirectory()) {
                    String name=entry.getName().substring(entry.getName().lastIndexOf('/')+1);
                    require(List.of("12221_Cat_v1_l3.obj","12221_Cat_v1_l3.mtl","Cat_diffuse.jpg","Cat_bump.jpg").contains(name) && !externalFiles.containsKey(name),"exact task-local Cat package inventory");
                    byte[] content=archive.readNBytes(16*1024*1024+1);require(content.length==entry.getSize() || entry.getSize()==-1,"Cat entry length");require(content.length<=16*1024*1024,"Cat entry budget");
                    Path selected=state.resolve(name);Files.write(selected,content,StandardOpenOption.CREATE_NEW);externalFiles.put(name,selected);
                }
            }
            require(externalFiles.size()==4,"Cat resource inventory");input=externalFiles.get("12221_Cat_v1_l3.obj");
            report.put("externalResourceInput",true);report.put("selectedModelSha256",hash(Files.readAllBytes(input)));
        }
        final Path selectedInput=input;
        var seed=awaitDocument(null,"cat-seed");
        edt(()->{executed(context.editorCommands().execute(EditorCommand.NEW_MODEL));return null;});
        target=awaitDocument(seed.documentId(),"cat-new");document=target.documentId();
        edt(()->{executed(context.editorCommands().execute(new EditorCanvasSettingsRequest(1024,1024)));return null;});
        var baseline=historyProbe?edt(()->CatVertexCapture.bank(current())):null;var h0=edt(()->context.cubism().history().snapshot());
        var menu=awaitComponent(JMenuItem.class,c->"导入 3D 模型…".equals(c.getText()),false);
        edt(()->{require(menu.isEnabled(),"import disabled");menu.doClick();return null;});
        var appearance=awaitComponent(JButton.class,c->"保留原材质".equals(c.getText()),true);edt(()->{appearance.doClick();return null;});
        var chooser=awaitComponent(JFileChooser.class,c->true,true);
        edt(()->{require("选择 GLB、glTF、OBJ 或模型 ZIP".equals(chooser.getDialogTitle()),"production chooser");chooser.setSelectedFile(selectedInput.toFile());chooser.approveSelection();return null;});
        if(external) {
            var grants=new ArrayList<Map<String,Object>>();report.put("externalResourceSelections",grants);
            for(String name:List.of("12221_Cat_v1_l3.mtl","Cat_diffuse.jpg")) {
                var resourceChooser=awaitComponent(JFileChooser.class,c->c.getDialogTitle().startsWith("选择模型引用的资源："),true);
                Path file=externalFiles.get(name);
                edt(()->{require(resourceChooser.getDialogTitle().endsWith(name),"exact declared resource reference");resourceChooser.setSelectedFile(file.toFile());resourceChooser.approveSelection();return null;});
                grants.add(Map.of("name",name,"sha256",hash(Files.readAllBytes(file)),"realChooserApproved",true));
            }
        }
        var confirm=awaitComponent(JOptionPane.class,c->title(c).equals("确认导入"),true);
        editingCheckpoint("cat-authoring");
        edt(()->{var text=String.valueOf(confirm.getMessage());report.put("confirmation",text);
            if(optimized)require(text.contains((editableCost?"615":"512")+" 个曲面网格") && text.contains(("cat-cost-save".equals(System.getProperty("turboism.validation.mi3d.mode"))?"源模型 70576 面 → 导入 51790 面":"源模型 70576 面 → 导入 48864 面")) && text.contains("各 9 点椭圆插值") && text.contains("Z 5 点") && text.contains("5.00 像素"),"optimized Cat confirmation");
            else require(text.contains("512 个曲面网格") && text.contains("70576 个三角形") && text.contains("椭圆插值") && text.contains("各 9") && text.contains("Z 旋转：5 点"),"direct Cat confirmation");SwingUtilities.invokeLater(()->confirm.setValue(JOptionPane.OK_OPTION));return null;});
        editingCheckpoint("cat-awaiting-completion");
        var success=awaitComponent(JOptionPane.class,c->String.valueOf(c.getMessage()).startsWith("导入完成。拖动新增的 X / Y / Z")
            || title(c).equals("无法导入 3D 模型"),true,1800);
        edt(()->{var text=String.valueOf(success.getMessage());report.put("completion",text);require(text.startsWith("导入完成") && text.contains("X / Y / Z"),"Cat import: "+text);success.setValue(JOptionPane.OK_OPTION);return null;});
        editingCheckpoint("cat-inventory");
        var imported=edt(()->{if(editableCost)CatVertexCapture.editableCostInventory(current());else if("cat-cost-save".equals(System.getProperty("turboism.validation.mi3d.mode")))CatVertexCapture.costInventory(current());else if("cat-adaptive-save".equals(System.getProperty("turboism.validation.mi3d.mode")))CatVertexCapture.adaptiveInventory(current());else if(optimized)CatVertexCapture.optimizedInventory(current());else CatVertexCapture.inventory(current());require(context.cubism().history().snapshot().position()==h0.position()+1,"one import history root");return CatVertexCapture.bank(current());});report.put("imported",imported);
        if(List.of("cat-adaptive-save","cat-cost-save").contains(System.getProperty("turboism.validation.mi3d.mode")))
            report.put("isolatedPatchParents",edt(()->editableCost?CatVertexCapture.editableIsolatedPatchParents(current()):CatVertexCapture.isolatedPatchParents(current())));
        if(historyProbe) {
            editingCheckpoint("cat-import-undo");
            edt(()->{context.cubism().history().undo(1);return null;});require(baseline.equals(edt(()->CatVertexCapture.bank(current()))),"Cat Undo bank");report.put("undoRestored",true);
            editingCheckpoint("cat-import-redo");
            edt(()->{context.cubism().history().redo(1);return null;});require(imported.equals(edt(()->CatVertexCapture.bank(current()))),"Cat Redo bank");report.put("redoRestored",true);
        } else report.put("importHistoryVerification","NOT_EXECUTED_SEPARATE_PERFORMANCE_WORK_REQUIRED");
        if(optimized || "cat-vertex-save".equals(System.getProperty("turboism.validation.mi3d.mode"))) {
            require(Boolean.getBoolean("turboism.validation.mi3d.persistenceSave"),"explicit Cat save opt-in");
            report.put("schemaVersion",editableCost?"mi3d-cat-editable-cost-save-v1":"cat-cost-save".equals(System.getProperty("turboism.validation.mi3d.mode"))?"mi3d-cat-cost-save-v1":"cat-adaptive-save".equals(System.getProperty("turboism.validation.mi3d.mode"))?"mi3d-cat-adaptive-save-v1":optimized?"mi3d-cat-optimized-save-v1":"mi3d-cat-vertex-save-v1");
            report.put("scope",editableCost?"REAL_CAT_MENU_IMPORT_REPRESENTATIVE_EDITORS_UNDO_NATIVE_SAVE_NOT_ALL_MESH_EDITING_OR_PERFORMANCE":"REAL_CAT_MENU_IMPORT_NATIVE_SAVE_BANK_ONLY_NO_REPEATED_HISTORY_OR_FRAMES");
            if(editableCost)runCatNativeEditors(true);
            var bank=edt(()->CatVertexCapture.persistentBank(current()));report.put("persistentBank",bank);
            editingCheckpoint("cat-saving");
            var artifact=ApplePersistenceFile.save(context.userFiles(),context.editorCommands(),state,()->{
                require(target.sameIdentity(observe()),"Cat save document changed");
            },ApplePersistenceFile.Profile.CAT);
            require(bank.equals(edt(()->CatVertexCapture.persistentBank(current()))),"Cat save changed source bank");
            report.put("persistenceSave",Map.of("commandStatus","EXECUTED","artifact",artifact.relativePath(),
                "artifactBytes",artifact.bytes(),"artifactSha256",artifact.sha256()));
            editingCheckpoint("cat-save-complete");return;
        }
        editingCheckpoint("cat-frames");
        CatVertexCapture.frames(context,this::current,state,report);
        require(imported.equals(edt(()->CatVertexCapture.bank(current()))),"Cat pose capture changed bank");
        editingCheckpoint("cat-frames-complete");
        report.put("nativeAccepted",false);
    }

    /** Full source-material import through the real menu, never a copied engine. */
    private void runXYZImport(Path state) throws Exception {
        report.put("schemaVersion",apple?"mi3d-apple-xyz360-v1":"mi3d-cube-xyz360-v1");
        report.put("scope","REAL_MENU_XYZ360_IMPORT_UNDO_REDO_AND_NATIVE_FRAMES; NOT_PERSISTENCE_ACCEPTANCE");
        editingCheckpoint("xyz-reading-input");byte[] bytes;
        try(var stream=UserEntryProbe.class.getResourceAsStream(apple?"apple.glb":"cube.glb")) {require(stream!=null,"cube input required");bytes=stream.readAllBytes();}
        require(hash(bytes).equals(apple?APPLE_SHA:CUBE_SHA),"exact authorized textured XYZ input");
        Path input=state.resolve("authorized-cube-xyz.glb");Files.write(input,bytes,StandardOpenOption.CREATE_NEW);
        report.put("inputSha256",hash(bytes));
        var seed=awaitDocument(null,"xyz-seed");
        edt(()->{executed(context.editorCommands().execute(EditorCommand.NEW_MODEL));return null;});
        target=awaitDocument(seed.documentId(),"xyz-new");document=target.documentId();
        edt(()->{executed(context.editorCommands().execute(new EditorCanvasSettingsRequest(1024,1024)));return null;});
        var baseline=edt(this::xyzState);var h0=edt(()->context.cubism().history().snapshot());
        var menu=awaitComponent(JMenuItem.class,c->"导入 3D 模型…".equals(c.getText()),false);
        edt(()->{require(menu.isEnabled(),"import menu disabled");menu.doClick();return null;});
        var appearance=awaitComponent(JButton.class,c->"保留原材质".equals(c.getText()),true);
        edt(()->{appearance.doClick();return null;});
        var chooser=awaitComponent(JFileChooser.class,c->true,true);
        edt(()->{require("选择 GLB、glTF、OBJ 或模型 ZIP".equals(chooser.getDialogTitle()) && !chooser.isMultiSelectionEnabled(),"exact production chooser");
            chooser.setSelectedFile(input.toFile());chooser.approveSelection();return null;});
        var confirm=awaitComponent(JOptionPane.class,c->title(c).equals("确认导入"),true);
        edt(()->{var text=String.valueOf(confirm.getMessage());report.put("confirmation",text);
            require(text.contains((apple?98:6)+" 个曲面网格") && text.contains("49 / 49") && text.contains("Z 旋转：5 点"),"XYZ source chart confirmation");
            confirm.setValue(JOptionPane.OK_OPTION);return null;});
        editingCheckpoint("xyz-authoring");
        var success=awaitComponent(JOptionPane.class,c->title(c).equals("3D 模型") || title(c).equals("无法导入 3D 模型"),true);
        edt(()->{var text=String.valueOf(success.getMessage());report.put("completion",text);
            require(text.startsWith("导入完成") && text.contains("X / Y / Z"),"XYZ import failed: "+text);success.setValue(JOptionPane.OK_OPTION);return null;});
        editingCheckpoint("xyz-imported");
        if(apple && Boolean.getBoolean("turboism.validation.mi3d.xyzSaveOnly")) {
            report.put("schemaVersion","mi3d-apple-xyz360-save-only-v1");
            report.put("scope","REAL_MENU_XYZ_IMPORT_NATIVE_SAVE_ONLY_NO_FULL_BANK_READS");
            edt(()->{
                require(target.sameIdentity(observe()),"native save document identity changed");
                var m=current();
                require(m.drawables().all().size()==98 && m.warpDeformers().all().size()==1
                    && m.rotationDeformers().all().size()==2,"apple XYZ native inventory");
                requireXYZPlacement();
                report.put("nativeCounts",Map.of("meshes",98,"warps",1,"rotations",2));
                report.put("parameters",m.parameters().all().stream().map(p->List.of(p.id().value(),
                    p.getMinimumValue(),p.getDefaultValue(),p.getMaximumValue(),p.getValue())).toList());
                return null;
            });
            editingCheckpoint("xyz-saving-without-full-bank-read");
            var artifact=ApplePersistenceFile.save(context.userFiles(),context.editorCommands(),state,()->{
                require(target.sameIdentity(observe()),"native save document identity changed");
            },ApplePersistenceFile.Profile.APPLE);
            report.put("persistenceSave",Map.of("commandStatus","EXECUTED","artifact",artifact.relativePath(),
                "artifactBytes",artifact.bytes(),"artifactSha256",artifact.sha256()));
            report.put("saveReopen","SAVED_FULL_CONTENT_AND_REOPEN_NOT_CHECKED");
            editingCheckpoint("xyz-save-only-complete");return;
        }
        var imported=edt(()->{
            var m=current();require(m.drawables().all().size()==(apple?98:6) && m.warpDeformers().all().size()==1 && m.rotationDeformers().all().size()==2,"XYZ object inventory");
            var roll=m.rotationDeformers().all().stream().filter(r->!r.authoringSnapshot().axes().isEmpty()).findFirst().orElseThrow().authoringSnapshot();
            requireXYZPlacement();
            require(roll.forms().size()==5 && roll.axes().size()==1 && roll.axes().get(0).keys().equals(List.of(-180f,-90f,0f,90f,180f)),"full native Roll bank");
            require(m.drawables().all().stream().allMatch(d->d.visible() && d.culling() && m.textures().textureInput(d.id()).isPresent()),"source materials and culling");
            require(context.cubism().history().snapshot().position()==h0.position()+1,"one native import undo root");
            var captured=xyzState();requireXYZWarpBank(captured);return captured;
        });report.put("imported",imported);
        edt(()->{context.cubism().history().undo(1);return null;});require(baseline.equals(edt(this::xyzState)),"XYZ Undo baseline not restored");
        report.put("undoRestored",true);
        edt(()->{context.cubism().history().redo(1);return null;});require(imported.equals(edt(this::xyzState)),"XYZ Redo source/forms not restored");
        report.put("redoRestored",true);editingCheckpoint("xyz-frames");
        if(Boolean.getBoolean("turboism.validation.mi3d.placementProbe")) {
            require(!Boolean.getBoolean("turboism.validation.mi3d.persistenceSave"),"dedicated placement probe without save");
            runXYZPlacement(imported);return;
        }
        captureXYZFrames(state,imported);
        if(appleSave || "cube-xyz-menu-save".equals(System.getProperty("turboism.validation.mi3d.mode"))) {
            require(Boolean.getBoolean("turboism.validation.mi3d.persistenceSave"),"explicit XYZ save opt-in required");
            editingCheckpoint("xyz-saving");
            var beforeSave=edt(this::xyzState);
            var historyBeforeSave=edt(()->context.cubism().history().snapshot());
            var artifact=ApplePersistenceFile.save(context.userFiles(),context.editorCommands(),state,()->{
                require(current()!=null && xyzState().equals(beforeSave),"XYZ authoring changed before SAVE_AS");
            },apple?ApplePersistenceFile.Profile.APPLE:ApplePersistenceFile.Profile.XYZ);
            var afterSave=edt(this::xyzState);
            var historyAfterSave=edt(()->context.cubism().history().snapshot());
            require(beforeSave.equals(afterSave) && historyBeforeSave.equals(historyAfterSave),"XYZ SAVE_AS changed authoring/history");
            report.put("persistenceSave",Map.of("commandStatus","EXECUTED","artifact",artifact.relativePath(),
                "artifactBytes",artifact.bytes(),"artifactSha256",artifact.sha256(),"before",beforeSave,"after",afterSave,"historyUnchanged",true));
            report.put("saveReopen","SAVED_REOPEN_NOT_RUN");
        }
        editingCheckpoint("xyz-complete");
    }
    private dev.turboism.sdk.cubism.model.RotationDeformer requireXYZPlacement() {
        var m=current();var rotations=m.rotationDeformers().all();
        var placement=rotations.stream().filter(r->r.authoringSnapshot().axes().isEmpty()).findFirst().orElseThrow();
        var roll=rotations.stream().filter(r->!r.authoringSnapshot().axes().isEmpty()).findFirst().orElseThrow();
        var bank=placement.authoringSnapshot();
        require(rotations.size()==2 && bank.forms().size()==1
            && bank.forms().get(0).value().equals(new dev.turboism.sdk.cubism.model.RotationDeformerForm(0,0,0,1,false,false))
            && placement.parentDeformerId().isEmpty() && roll.parentDeformerId().equals(Optional.of(placement.id()))
            && m.warpDeformers().all().get(0).parentDeformerId().equals(Optional.of(roll.id())),"outer rigid placement hierarchy");
        var form=bank.forms().get(0).value();
        report.put("outerPlacement",Map.of("id",placement.id().value(),"rollId",roll.id().value(),
            "warpId",m.warpDeformers().all().get(0).id().value(),"parent",placement.parentDeformerId().map(p->p.value()).orElse(""),
            "rollParent",roll.parentDeformerId().orElseThrow().value(),"warpParent",m.warpDeformers().all().get(0).parentDeformerId().orElseThrow().value(),
            "identityForm",List.of(form.angle(),form.originX(),form.originY(),form.scale(),form.reflectedX(),form.reflectedY())));
        return placement;
    }

    private void awaitPlacementPass(List<dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot> previous,
            Map<String,Object> evidence) throws Exception {
        await(()->edt(()->{
            var actual=current().drawables().all().stream().map(d->d.evaluationSnapshot()).toList();
            require(actual.size()==previous.size(),"placement evaluation inventory changed");
            boolean ready=true;
            for(int i=0;i<actual.size();i++) {
                var old=previous.get(i);var now=actual.get(i);
                require(now.artMeshId().equals(old.artMeshId()) && now.sourceGuid().equals(old.sourceGuid()),"placement evaluation identity changed");
                ready&=now.calculatedForm().isPresent() && now.passEntryTimeMillis()>old.passEntryTimeMillis()
                    && now.liveParameters().parameters().equals(now.consumedParameters().parameters());
            }
            evidence.put("passBefore",previous.stream().map(e->e.passEntryTimeMillis()).toList());
            evidence.put("passAfter",actual.stream().map(e->e.passEntryTimeMillis()).toList());
            return ready;
        }),15,"placement native recalculation");
    }

    private void runXYZPlacement(Map<String,Object> imported) throws Exception {
        report.put("scope","REAL_MENU_XYZ_OUTER_PLACEMENT_TRANSLATION_AND_SCALE");
        var rows=new ArrayList<Map<String,Object>>();report.put("placement",rows);
        for(float[] pose:List.of(new float[]{0,0,0},new float[]{35,-47,61},new float[]{-127,103,-155})) {
            var history=edt(()->context.cubism().history().snapshot());
            var requested=edt(()->{
                var m=current();
                for(var p:m.parameters().all()) {
                    String id=p.id().value();
                    if(id.startsWith("Param3DPitch"))p.setValue(pose[0]);
                    if(id.startsWith("Param3DYaw"))p.setValue(pose[1]);
                    if(id.startsWith("Param3DRoll"))p.setValue(pose[2]);
                }
                return m.drawables().all().stream().map(d->d.evaluationSnapshot()).toList();
            });
            if(!requested.stream().allMatch(e->e.calculatedForm().isPresent()
                    && e.liveParameters().parameters().equals(e.consumedParameters().parameters())))
                SourceTextureCapture.awaitConsumed(requested,()->edt(()->current().drawables().all().stream().map(d->d.evaluationSnapshot()).toList()),
                    new LinkedHashMap<>(),System::nanoTime,Thread::sleep,apple?98:6);
            for(float scale:List.of(1f,1.25f)) {
                var row=new LinkedHashMap<String,Object>();rows.add(row);
                row.put("X",pose[0]);row.put("Y",pose[1]);row.put("Z",pose[2]);row.put("scale",scale);row.put("dx",23);row.put("dy",-11);
                var before=edt(this::xyzState);
                var rollBanks=edt(()->current().rotationDeformers().all().stream().filter(r->!r.authoringSnapshot().axes().isEmpty()).map(r->r.authoringSnapshot()).toList());
                var vertices=edt(()->current().drawables().all().stream().map(d->d.evaluationSnapshot()).toList());
                edt(()->{requireXYZPlacement().replaceForm(new dev.turboism.sdk.cubism.model.RotationDeformerForm(0,23,-11,scale,false,false));return null;});
                awaitPlacementPass(vertices,row);
                edt(()->{
                    var m=current();var after=xyzState();
                    for(String field:before.keySet())if(!field.equals("rotations"))require(before.get(field).equals(after.get(field)),"placement changed child bank "+field);
                    require(rollBanks.equals(m.rotationDeformers().all().stream().filter(r->!r.authoringSnapshot().axes().isEmpty()).map(r->r.authoringSnapshot()).toList()),"placement changed bound Roll bank");
                    double maximum=0;
                    for(int mesh=0;mesh<vertices.size();mesh++) {
                        var original=vertices.get(mesh).calculatedForm().orElseThrow().positions();
                        var moved=m.drawables().all().get(mesh).evaluationSnapshot().calculatedForm().orElseThrow().positions();
                        require(moved.size()==original.size(),"placement changed vertex inventory");
                        for(int point=0;point<moved.size();point++) {
                            var p=original.get(point);var q=moved.get(point);
                            maximum=Math.max(maximum,Math.hypot(q.x()-(p.x()*scale+23),q.y()-(p.y()*scale-11)));
                        }
                    }
                    row.put("maximumErrorPixels",maximum);require(maximum<=.002,"outer placement is not rigid: "+maximum);return null;
                });
                var shifted=edt(()->current().drawables().all().stream().map(d->d.evaluationSnapshot()).toList());
                edt(()->{
                    current().rotationDeformers().all().stream().filter(r->r.authoringSnapshot().axes().isEmpty()).findFirst().orElseThrow()
                        .replaceForm(new dev.turboism.sdk.cubism.model.RotationDeformerForm(0,0,0,1,false,false));return null;
                });
                awaitPlacementPass(shifted,new LinkedHashMap<>());
                require(before.equals(edt(this::xyzState)),"placement restore changed full authoring state");
            }
            int delta=edt(()->context.cubism().history().snapshot().position())-history.position();
            require(delta>=0,"placement history moved backward");
            if(delta>0)edt(()->{context.cubism().history().undo(delta);return null;});
            require(imported.equals(edt(this::xyzState)),"placement pose undo did not restore imported state");
        }
        editingCheckpoint("xyz-placement-complete");
    }

    private void runXYZReopen(Path state) throws Exception {
        report.put("schemaVersion",apple?"mi3d-apple-xyz360-reopen-v1":"mi3d-cube-xyz360-reopen-v1");
        report.put("scope","OBSERVE_REOPENED_XYZ_SEED_ONLY");
        try(var input=UserEntryProbe.class.getResourceAsStream(apple?"apple.glb":"cube.glb")) {
            require(input!=null && hash(input.readAllBytes()).equals(apple?APPLE_SHA:CUBE_SHA),"reopen authorized cube identity");
            report.put("inputSha256",apple?APPLE_SHA:CUBE_SHA);
        }
        target=awaitDocument(null,"xyz-reopened-seed");document=target.documentId();
        var imported=edt(()->{
            var m=current();
            require(m.drawables().all().size()==(apple?98:6) && m.warpDeformers().all().size()==1
                && (m.rotationDeformers().all().size()==1 || m.rotationDeformers().all().size()==2),"reopened XYZ inventory");
            if(m.rotationDeformers().all().size()==2)requireXYZPlacement();
            var roll=m.rotationDeformers().all().stream().filter(r->!r.authoringSnapshot().axes().isEmpty()).findFirst().orElseThrow().authoringSnapshot();
            require(roll.forms().size()==5 && roll.axes().size()==1 && roll.axes().get(0).keys().equals(List.of(-180f,-90f,0f,90f,180f)),"reopened XYZ full Roll bank");
            require(m.drawables().all().stream().allMatch(d->d.visible() && d.culling() && m.textures().textureInput(d.id()).isPresent()),"reopened XYZ materials");
            var captured=xyzState();requireXYZWarpBank(captured);return captured;
        });
        report.put("imported",imported);
        captureXYZFrames(state,imported);
        report.put("saveReopen","REOPENED_REQUIRES_INDEPENDENT_PAIR_ORACLE");
        editingCheckpoint("xyz-reopen-complete");
    }

    private void captureXYZFrames(Path state,Map<String,Object> imported) throws Exception {
        var ids=edt(()->current().parameters().all().stream().map(p->p.id().value()).toList());
        String yaw=ids.stream().filter(id->id.matches("Param3DYaw[0-9a-f]{12}")).findFirst().orElseThrow();
        String pitch=ids.stream().filter(id->id.matches("Param3DPitch[0-9a-f]{12}")).findFirst().orElseThrow();
        String roll=ids.stream().filter(id->id.matches("Param3DRoll[0-9a-f]{12}")).findFirst().orElseThrow();
        report.put("axes",Map.of("X",pitch,"Y",yaw,"Z",roll));
        var poses=List.of(List.of(0f,0f,0f),List.of(-180f,0f,0f),List.of(180f,0f,0f),List.of(0f,-180f,0f),List.of(0f,180f,0f),
            List.of(0f,0f,-180f),List.of(0f,0f,180f),List.of(90f,0f,0f),List.of(0f,90f,0f),List.of(0f,0f,90f),List.of(37.5f,-112.5f,45f),List.of(-150f,75f,-135f));
        var frames=new ArrayList<Map<String,Object>>();report.put("frames",frames);Files.createDirectory(state.resolve("xyz-frames"));
        for(int i=0;i<poses.size();i++) {
            var pose=poses.get(i);var row=new LinkedHashMap<String,Object>();row.put("poseXYZ",pose);frames.add(row);
            var before=edt(()->context.cubism().history().snapshot());
            var requested=edt(()->{current().parameters().find(new ParameterId(pitch)).setValue(pose.get(0));
                current().parameters().find(new ParameterId(yaw)).setValue(pose.get(1));current().parameters().find(new ParameterId(roll)).setValue(pose.get(2));
                return current().drawables().all().stream().map(d->d.evaluationSnapshot()).toList();});
            // An unchanged pose can already be consumed; it creates no new native pass.
            // Changed poses still require the existing asynchronous-consumption witness.
            boolean alreadyConsumed=requested.stream().allMatch(e->e.calculatedForm().isPresent()
                && e.liveParameters().parameters().equals(e.consumedParameters().parameters()));
            if(alreadyConsumed) {
                row.put("waitReason","ALREADY_CONSUMED");
                row.put("lastObservation",requested.stream().map(SourceTextureCapture::evaluation).toList());
            }
            else SourceTextureCapture.awaitConsumed(requested,()->edt(()->current().drawables().all().stream().map(d->d.evaluationSnapshot()).toList()),row,System::nanoTime,Thread::sleep,apple?98:6);
            var pixels=edt(()->current().renderPixels(1024,1024));
            var image=new java.awt.image.BufferedImage(1024,1024,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0,0,1024,1024,pixels.argb(),0,1024);var png=new java.io.ByteArrayOutputStream();
            require(javax.imageio.ImageIO.write(image,"PNG",png),"native frame encoding");byte[] encoded=png.toByteArray();
            String path="xyz-frames/"+i+".png";Files.write(state.resolve(path),encoded,StandardOpenOption.CREATE_NEW);
            row.put("artifact",path);row.put("sha256",hash(encoded));
            row.put("evaluated",edt(()->current().drawables().all().stream().map(d->{var e=d.evaluationSnapshot();return Map.of("id",d.id().value(),
                "positions",e.calculatedForm().orElseThrow().positions().stream().map(p->List.of(p.x(),p.y())).toList(),"order",e.calculatedForm().orElseThrow().drawOrder());}).toList()));
            int delta=edt(()->context.cubism().history().snapshot().position())-before.position();require(delta>=0,"pose history moved backward");
            if(delta>0)edt(()->{context.cubism().history().undo(delta);return null;});
        }
        require(imported.equals(edt(this::xyzState)),"XYZ capture altered authoring or final parameters");
        report.put("framesCaptured",true);report.put("renderVerification","REQUIRES_INDEPENDENT_XYZ_GEOMETRY_PIXEL_ORACLE");
    }

    private Map<String,Object> xyzState() {
        var m=current();var s=new LinkedHashMap<>(state());
        // Apple state() already captures these complete stored banks in this EDT turn.
        if(apple)s.put("warps",s.get("deformers"));
        else {
            s.put("meshes",m.drawables().all().stream().map(d->compactMesh(d.authoringSnapshot())).toList());
            s.put("warps",m.warpDeformers().all().stream().map(w->compactWarp(w.authoringSnapshot())).toList());
        }
        s.put("rotations",m.rotationDeformers().all().stream().map(r->r.authoringSnapshot().toString()).toList());
        return s;
    }
    private static void requireXYZWarpBank(Map<String,Object> captured) {
        var banks=(List<?>)captured.get("warps");
        require(banks.size()==1 && Integer.valueOf(2401).equals(((Map<?,?>)banks.get(0)).get("formCount")),
            "full X/Y ordinary grid");
    }

    /** Real chart26 source-plane candidate; no startup seed edits or discarded faces. */
    private void runEquivalentSlotEditingProbe() throws Exception {
        require(apple && Integer.getInteger("turboism.validation.mi3d.meshStartIndex",0)==26
            && Integer.getInteger("turboism.validation.mi3d.meshEndIndex",0)==27,"exact chart26 candidate range");
        report.put("schemaVersion","mi3d-apple-equivalent-slot-edit-v1");
        report.put("scope","ISOLATED_REAL_APPLE_CHART26_EQUIVALENT_SOURCE_SLOTS_NATIVE_COMMIT_NOT_FULL_MODEL_ACCEPTANCE");
        byte[] raw;try(var input=UserEntryProbe.class.getResourceAsStream("equivalent-chart26.bin")) {
            require(input!=null,"frozen equivalent source fixture required");raw=input.readAllBytes();
        }
        if(hash(raw).equals("906624bccb601f507a1531a9518c4efe9ab58027ac5a7eb9755ac8a0ba6fb1e1")) {
            if(Boolean.getBoolean("turboism.validation.mi3d.partitionBoundProbe"))runBoundPartitionEditingProbe(raw);
            else runPartitionedSlotEditingProbe(raw);return;
        }
        require(hash(raw).equals("adebbb4e934804d9642da62f2383d813aae1b4378e912426e5055ab35f412daa"),"audited chart26 candidate identity");
        var positions=new ArrayList<dev.turboism.sdk.cubism.model.Point2>();
        var uvs=new ArrayList<dev.turboism.sdk.cubism.model.Point2>();var indices=new ArrayList<Integer>();
        try(var input=new java.io.DataInputStream(new java.io.ByteArrayInputStream(raw))) {
            int n=input.readInt(),k=input.readInt();require(n==1108 && k==6144 && raw.length==8+n*8+k*4,"exact full chart26 counts");
            for(int i=0;i<n;i++) {
                var point=new dev.turboism.sdk.cubism.model.Point2(input.readFloat(),input.readFloat());positions.add(point);
                uvs.add(new dev.turboism.sdk.cubism.model.Point2(point.x()/1024,point.y()/256));
            }
            for(int i=0;i<k;i++)indices.add(input.readInt());require(input.read()==-1,"no candidate trailing bytes");
        }
        var geometry=new dev.turboism.sdk.cubism.model.ArtMeshGeometry(positions,uvs,indices);
        report.put("candidateGeometry",Map.of("sourceSha256",hash(raw),"sourceVertices",1108,"sourceTriangles",2048,
            "positions",positions.stream().map(p->List.of(p.x(),p.y())).toList(),"uvs",uvs.stream().map(p->List.of(p.x(),p.y())).toList(),"indices",indices));
        String seedDocument=document;
        edt(()->{executed(context.editorCommands().execute(EditorCommand.NEW_MODEL));return null;});
        target=awaitDocument(seedDocument,"equivalent-chart26-copy");document=target.documentId();
        byte[] image;try(var input=UserEntryProbe.class.getResourceAsStream("mesh-edit-copy.png")) {
            require(input!=null,"same original source material required");image=input.readAllBytes();
        }
        var grant=dev.turboism.sdk.cubism.model.AuthorizedImageContent.ofDimensions(image,"Original apple source materials",1024,256);
        var id=edt(()->{
            require(current().drawables().all().isEmpty(),"new empty task model required");
            var drawable=current().drawables().create("Apple chart26 equivalent slots",null,-1,geometry);
            current().textures().registerImageAndBind(grant,drawable.id());
            require(drawable.authoringSnapshot().sourceGeometry().equals(geometry),"source image binding changed geometry");
            current().drawables().select(List.of(drawable.id()));return drawable.id();
        });
        report.put("candidateBefore",edt(()->NativeAssertions.snapshot(current().drawables().find(id).authoringSnapshot())));
        var button=awaitComponent(AbstractButton.class,b->"网格编辑".equals(b.getToolTipText()),true);
        edt(()->{require(button.isEnabled(),"candidate mesh editing disabled");SwingUtilities.invokeLater(button::doClick);return null;});
        await(()->edt(()->context.meshEdit().snapshot().points().size()==1108),15,"candidate native editor entry");
        var editing=edt(()->context.meshEdit().snapshot());require(!editing.edges().isEmpty(),"candidate missing native edges");
        report.put("nativeEditorEntry",Map.of("points",editing.points().size(),"edges",editing.edges().size(),"entered",true,"vertexWrites",false));
        edt(()->{SwingUtilities.invokeLater(button::doClick);return null;});
        await(()->edt(()->context.meshEdit().snapshot().points().isEmpty()),15,"candidate native editor exit");
        Thread.sleep(1000);
        var committed=edt(()->current().drawables().find(id).authoringSnapshot());
        report.put("candidateCommitted",NativeAssertions.snapshot(committed));
        report.put("nativeEditorEnded",true);report.put("committedVertices",committed.sourceGeometry().positions().size());
        report.put("committedTriangles",committed.sourceGeometry().triangleIndices().size()/3);
        require(!committed.sourceGeometry().triangleIndices().isEmpty(),"equivalent candidate still loses all native triangles");
        require(committed.sourceGeometry().positions().size()==1108,"native candidate still removes distinct source vertices");
        report.put("nonemptyNativeCommit",true);report.put("fullAppleEditingAccepted",false);
    }

    private void runDerivedSaveProbe(byte[] raw) throws Exception {
        report.put("schemaVersion","mi3d-apple-derived-save-v1");report.put("scope","FULL98_UNEDITED_DERIVED_SOURCE_FINITE_XYZ_FRAMES_SAVE_RESTORE_NOT_FINAL_ACCEPTANCE");
        report.put("textureLayout","DERIVED_PER_CHART_ORIGINAL_UV_MATERIAL_MAPPING");report.put("candidateSha256",hash(raw));
        var initial=edt(this::xyzState);report.put("initial",initial);var seedPixels=edt(()->current().renderPixels(1024,1024).argb());
        int initialHistory=edt(()->context.cubism().history().snapshot().position());
        var replacement=edt(()->AppleDerivedCapture.create(context,this::current,raw,report));
        edt(()->{for(var chart:replacement.charts())current().drawables().remove(current().drawables().find(chart.originalId()));return null;});
        var saved=edt(this::xyzState);requireDerivedInventory(saved);report.put("savedState",saved);report.put("testPointDeltaIncluded",false);
        captureXYZFrames(context.paths().stateDir(),saved);
        var artifact=ApplePersistenceFile.save(context.userFiles(),context.editorCommands(),context.paths().stateDir(),()->require(saved.equals(xyzState()),"derived source changed before save"));
        require(saved.equals(edt(this::xyzState)),"derived SAVE_AS changed source bank");
        report.put("derivedSave",Map.of("artifact",artifact.relativePath(),"bytes",artifact.bytes(),"sha256",artifact.sha256()));
        if(replacement.charts().stream().allMatch(c->c.imageSize()==576)) {
            AppleOfficialExport.runDerivedAtlasUndo(this::current,this::xyzState,()->context.cubism().history().snapshot(),
                count->context.cubism().history().undo(count),context.cubism().textureAtlasLayouts(),context.editorCommands(),context.paths().stateDir(),report);
            require(saved.equals(edt(this::xyzState)),"canonical derived atlas Undo changed saved source bank");
            report.put("derivedAtlasUndoVerified",true);
        }
        int added=edt(()->context.cubism().history().snapshot().position())-initialHistory;require(added>0,"derived import history missing");
        edt(()->{context.cubism().history().undo(added);return null;});var restored=edt(this::xyzState);report.put("restoredState",restored);
        require(sameBoundSourceContent(initial,restored),"derived save did not restore original source bank");
        Thread.sleep(250);var afterPixels=edt(()->current().renderPixels(1024,1024).argb());require(java.util.Arrays.equals(seedPixels,afterPixels),"derived save Undo changes original pixels");
        report.put("originalTaskSeedRestored",true);report.put("seedPixelsBeforeSha256",pixelHash(seedPixels));report.put("seedPixelsAfterSha256",pixelHash(afterPixels));
        report.put("fullAppleEditingAccepted",false);report.put("saveReopen","SAVED_UNEDITED_DERIVED_FULL98_REOPEN_NOT_RUN");
    }
    private static void requireDerivedInventory(Map<String,Object> bank) {
        requireXYZWarpBank(bank);var meshes=(List<?>)bank.get("meshes");require(meshes.size()==98,"exact full98 derived inventory");
        long slots=0,faces=0;for(var value:meshes){var row=(Map<?,?>)value;slots+=((Number)row.get("vertexCount")).longValue();faces+=((Number)row.get("triangleCount")).longValue();
            require(Integer.valueOf(49).equals(row.get("formCount"))&&Integer.valueOf(49).equals(row.get("cellCount"))&&Integer.valueOf(2).equals(row.get("axisCount")),"complete derived layer bank");}
        require(slots==80514&&faces==150784,"all original faces and derived slots required");
    }

    private void runDerivedEditingProbe(byte[] raw) throws Exception {
        report.put("schemaVersion","mi3d-apple-derived-bound-edit-v1");
        report.put("scope","FULL98_ORIGINAL_FACE_DERIVED_SOURCE_NATIVE_ENTRY_COMMIT_POINT_WRITE_RESTORE_NOT_FINAL_ACCEPTANCE");
        report.put("candidateSha256",hash(raw));
        var initial=edt(this::xyzState);var seedPixels=edt(()->current().renderPixels(1024,1024).argb());
        int initialHistory=edt(()->context.cubism().history().snapshot().position());
        var replacement=edt(()->AppleDerivedCapture.create(context,this::current,raw,report));
        var allParameters=edt(()->current().parameters().all().stream().map(p->p.id()).filter(p->p.value().startsWith("Param3D")).toList());
        require(allParameters.size()==3,"complete original XYZ axes required");
        var pitch=allParameters.stream().filter(p->p.value().startsWith("Param3DPitch")).findFirst().orElseThrow();
        var yaw=allParameters.stream().filter(p->p.value().startsWith("Param3DYaw")).findFirst().orElseThrow();
        var roll=allParameters.stream().filter(p->p.value().startsWith("Param3DRoll")).findFirst().orElseThrow();
        var pairs=new ArrayList<Map<String,Object>>();report.put("derivedXYZPairs",pairs);
        float[][] poses={{0,0,0},{90,0,0},{0,90,0},{0,0,90},{37,-61,113},{-123,67,-151},{180,0,0},{0,180,0},{0,0,180},{-180,-180,-180},{165,75,45},{-45,125,-75}};
        try {for(var pose:poses) {
            var pair=edt(()->{
                var m=current();m.parameters().find(pitch).setValue(pose[0]);m.parameters().find(yaw).setValue(pose[1]);m.parameters().find(roll).setValue(pose[2]);m.update();
                var rows=new ArrayList<Map<String,Object>>();double maximum=0;
                for(int chart=0;chart<98;chart++) {
                    var old=m.drawables().find(replacement.charts().get(chart).originalId()).evaluationSnapshot().calculatedForm().orElseThrow().positions();
                    var evaluation=m.drawables().find(replacement.ids().get(chart)).evaluationSnapshot();
                    for(int axis=0;axis<3;axis++) {var pid=List.of(pitch,yaw,roll).get(axis);float value=pose[axis];require(evaluation.consumedParameters().parameters().stream().anyMatch(p->p.parameterId().equals(pid)&&p.value()==value),"derived mesh did not consume requested XYZ");}
                    var actual=evaluation.calculatedForm().orElseThrow().positions();var representatives=replacement.charts().get(chart).representatives();
                    require(actual.size()==representatives.size(),"derived calculated slot domain");
                    for(int i=0;i<actual.size();i++)maximum=Math.max(maximum,Math.hypot((double)actual.get(i).x()-old.get(representatives.get(i)).x(),(double)actual.get(i).y()-old.get(representatives.get(i)).y()));
                    rows.add(Map.of("chart",chart,"reference",old.stream().map(p->List.of(p.x(),p.y())).toList(),"derived",actual.stream().map(p->List.of(p.x(),p.y())).toList()));
                }
                require(maximum<=0.125,"derived replacement changes XYZ projection beyond existing bound: "+maximum);
                return Map.<String,Object>of("pose",List.of(pose[0],pose[1],pose[2]),"charts",rows,"maximumPairPixels",maximum);
            });pairs.add(pair);
        }} finally {edt(()->{for(var id:List.of(pitch,yaw,roll))current().parameters().find(id).setValue(0);current().update();return null;});}
        edt(()->{
            for(var chart:replacement.charts())current().drawables().remove(current().drawables().find(chart.originalId()));
            require(current().drawables().all().size()==98,"all original charts must be replaced");
            require(current().drawables().all().stream().mapToInt(d->d.authoringSnapshot().sourceGeometry().triangleIndices().size()/3).sum()==150784,"all original Apple faces required");
            return null;
        });
        var rows=new ArrayList<Map<String,Object>>();report.put("derivedNativeEditors",rows);
        for(int chart=0;chart<98;chart++) {
            var id=replacement.ids().get(chart);var before=edt(()->current().drawables().find(id).authoringSnapshot());
            edt(()->{current().drawables().select(List.of(id));return null;});
            var button=awaitComponent(AbstractButton.class,b->"网格编辑".equals(b.getToolTipText()),true);
            edt(()->{require(button.isEnabled(),"derived native editing disabled");SwingUtilities.invokeLater(button::doClick);return null;});
            await(()->edt(()->context.meshEdit().snapshot().points().size()==before.sourceGeometry().positions().size()),15,"derived native entry");
            require(!edt(()->context.meshEdit().snapshot()).edges().isEmpty(),"derived native edges missing");
            edt(()->{SwingUtilities.invokeLater(button::doClick);return null;});
            await(()->edt(()->context.meshEdit().snapshot().points().isEmpty()),15,"derived native exit");Thread.sleep(250);
            var after=edt(()->current().drawables().find(id).authoringSnapshot());
            var row=new LinkedHashMap<String,Object>();row.put("chart",chart);row.put("before",compactMesh(before));row.put("after",compactMesh(after));
            row.put("beforeGeometry",geometryRecord(before.sourceGeometry()));row.put("afterGeometry",geometryRecord(after.sourceGeometry()));rows.add(row);
            require(before.sourceGeometry().positions().equals(after.sourceGeometry().positions())&&before.sourceGeometry().uvs().equals(after.sourceGeometry().uvs()),"derived native commit changes source attributes");
            require(AppleDerivedCapture.sameFaces(before.sourceGeometry().triangleIndices(),after.sourceGeometry().triangleIndices()),"derived native commit changes original faces");
            require(before.axes().equals(after.axes())&&before.cells().equals(after.cells())&&before.forms().equals(after.forms())&&before.morphKeys().equals(after.morphKeys()),"derived native commit changes layer bank");
            row.put("exactNativeCommit",true);
        }
        var editedId=replacement.ids().get(26);var beforeEdit=edt(()->current().drawables().find(editedId).authoringSnapshot());
        var others=edt(()->current().drawables().all().stream().filter(d->!d.id().equals(editedId)).collect(java.util.stream.Collectors.toMap(d->d.id(),d->compactMesh(d.authoringSnapshot()))));
        edt(()->{current().drawables().select(List.of(editedId));return null;});
        var button=awaitComponent(AbstractButton.class,b->"网格编辑".equals(b.getToolTipText()),true);
        edt(()->{SwingUtilities.invokeLater(button::doClick);return null;});
        await(()->edt(()->context.meshEdit().snapshot().points().size()==beforeEdit.sourceGeometry().positions().size()),15,"derived actual point entry");
        var point=edt(()->context.meshEdit().snapshot()).points().stream().min(java.util.Comparator.comparingDouble(p->p.x())).orElseThrow();
        var shifted=new dev.turboism.sdk.cubism.mesh.MeshPointRef(point.id(),point.x()-0.001f,point.y());
        var moved=edt(()->context.meshEdit().movePoints(List.of(shifted)));require(moved.accepted()&&moved.rejected().isEmpty(),"derived actual point move rejected");
        edt(()->{SwingUtilities.invokeLater(button::doClick);return null;});
        await(()->edt(()->context.meshEdit().snapshot().points().isEmpty()),15,"derived actual point exit");Thread.sleep(250);
        var committed=edt(()->current().drawables().find(editedId).authoringSnapshot());
        require(!committed.sourceGeometry().positions().equals(beforeEdit.sourceGeometry().positions()),"derived point move not committed");
        require(AppleDerivedCapture.sameFaces(beforeEdit.sourceGeometry().triangleIndices(),committed.sourceGeometry().triangleIndices()),"derived point move loses faces");
        require(beforeEdit.axes().equals(committed.axes())&&beforeEdit.cells().equals(committed.cells()),"derived point move changes binding domain");
        report.put("derivedPointEdit",Map.of("chart",26,"pointBefore",List.of(point.id(),point.x(),point.y()),"pointMoved",List.of(shifted.id(),shifted.x(),shifted.y()),"before",NativeAssertions.snapshot(beforeEdit),"committed",NativeAssertions.snapshot(committed)));
        edt(()->{for(var entry:others.entrySet())require(compactMesh(current().drawables().find(entry.getKey()).authoringSnapshot()).equals(entry.getValue()),"derived point changes other mesh");
            require(current().warpDeformers().find(replacement.warp().deformerId()).authoringSnapshot().equals(replacement.warp()),"derived edit changes original Warp bank");
            require(current().rotationDeformers().all().stream().map(r->r.authoringSnapshot()).toList().equals(replacement.rotations()),"derived edit changes original rotation banks");return null;});
        var history=edt(()->context.cubism().history().snapshot());int added=history.position()-initialHistory;require(added>0,"derived history missing");
        edt(()->{context.cubism().history().undo(added);return null;});
        require(sameBoundSourceContent(initial,edt(this::xyzState)),"derived diagnostic did not restore original source bank");
        Thread.sleep(250);var restoredPixels=edt(()->current().renderPixels(1024,1024).argb());
        require(java.util.Arrays.equals(seedPixels,restoredPixels),"derived diagnostic Undo changes original neutral pixels");
        report.put("seedPixelsBeforeSha256",pixelHash(seedPixels));report.put("seedPixelsAfterSha256",pixelHash(restoredPixels));
        report.put("originalTaskSeedRestored",true);report.put("all98NativeEntryCommitAccepted",true);report.put("fullAppleEditingAccepted",false);
        report.put("saveReopen","NOT_RUN_DERIVED_BOUND_EDIT_RESTORE_ONLY");
    }
    private static Map<String,Object> geometryRecord(dev.turboism.sdk.cubism.model.ArtMeshGeometry g) {
        return Map.of("positions",g.positions().stream().map(p->List.of(p.x(),p.y())).toList(),"uvs",g.uvs().stream().map(p->List.of(p.x(),p.y())).toList(),"indices",g.triangleIndices());
    }

    private void runBoundPartitionEditingProbe(byte[] raw) throws Exception {
        require(Boolean.getBoolean("turboism.validation.mi3d.persistenceSave"),"dedicated bound partition save opt-in");
        report.put("schemaVersion","mi3d-apple-bound-partition-edit-save-v1");
        report.put("scope","FULL_APPLE_REAL_CHART26_BOUND_PARTITION_POINT_EDIT_SAVE_NOT_FULL_EDIT_ACCEPTANCE");
        report.put("candidateSha256",hash(raw));
        var initial=edt(()->{observeAppleMeshFlags();var result=xyzState();requireXYZWarpBank(result);return result;});report.put("initial",initial);
        var seedPixels=edt(()->current().renderPixels(1024,1024).argb());
        report.put("seedPixelsBeforeSha256",pixelHash(seedPixels));
        int initialHistory=edt(()->context.cubism().history().snapshot().position());
        var replacement=edt(()->ApplePartitionBoundCapture.create(context,this::current,raw,report));
        captureBoundPartitionPoses(replacement);
        edt(()->{current().drawables().remove(current().drawables().find(ApplePartitionBoundCapture.ORIGINAL));
            var afterDeleteIds=current().drawables().all().stream().map(d->d.id().value()).toList();
            report.put("afterOriginalDelete",Map.of("count",afterDeleteIds.size(),"ids",afterDeleteIds));
            require(afterDeleteIds.size()==102&&!afterDeleteIds.contains(ApplePartitionBoundCapture.ORIGINAL.value()),"original chart must be replaced by five groups: "+afterDeleteIds);
            require(current().drawables().all().stream().mapToInt(d->d.authoringSnapshot().sourceGeometry().triangleIndices().size()/3).sum()==150784,"all original Apple faces required");return null;});
        var rows=new ArrayList<Map<String,Object>>();report.put("nativeBoundEditors",rows);
        for(var id:replacement.ids()) {
            var before=edt(()->current().drawables().find(id).authoringSnapshot());
            edt(()->{current().drawables().select(List.of(id));return null;});
            var button=awaitComponent(AbstractButton.class,b->"网格编辑".equals(b.getToolTipText()),true);
            edt(()->{require(button.isEnabled(),"bound partition editing disabled");SwingUtilities.invokeLater(button::doClick);return null;});
            await(()->edt(()->context.meshEdit().snapshot().points().size()==before.sourceGeometry().positions().size()),15,"bound partition entry");
            var entry=edt(()->context.meshEdit().snapshot());require(!entry.edges().isEmpty(),"bound partition edges missing");
            edt(()->{SwingUtilities.invokeLater(button::doClick);return null;});
            await(()->edt(()->context.meshEdit().snapshot().points().isEmpty()),15,"bound partition exit");Thread.sleep(1000);
            var after=edt(()->current().drawables().find(id).authoringSnapshot());
            var row=new LinkedHashMap<String,Object>();row.put("id",id.value());row.put("before",NativeAssertions.snapshot(before));row.put("after",NativeAssertions.snapshot(after));rows.add(row);
            require(before.sourceGeometry().positions().equals(after.sourceGeometry().positions()) && before.sourceGeometry().uvs().equals(after.sourceGeometry().uvs()),"bound partition source attributes changed");
            require(orientedFaces(before.sourceGeometry().triangleIndices()).equals(orientedFaces(after.sourceGeometry().triangleIndices())),"bound partition loses original oriented faces");
            require(before.axes().equals(after.axes()) && before.cells().equals(after.cells()) && before.forms().equals(after.forms()),"bound partition commit changes stored layer bank");
            row.put("exactNativeBoundCommit",true);
        }
        var editedId=replacement.ids().get(0);var beforeEdit=edt(()->current().drawables().find(editedId).authoringSnapshot());
        var beforeOthers=edt(()->current().drawables().all().stream().filter(d->!d.id().equals(editedId)).collect(java.util.stream.Collectors.toMap(d->d.id(),d->compactMesh(d.authoringSnapshot()))));
        edt(()->{current().drawables().select(List.of(editedId));return null;});
        var button=awaitComponent(AbstractButton.class,b->"网格编辑".equals(b.getToolTipText()),true);
        edt(()->{SwingUtilities.invokeLater(button::doClick);return null;});
        await(()->edt(()->context.meshEdit().snapshot().points().size()==beforeEdit.sourceGeometry().positions().size()),15,"actual bound point edit entry");
        var editing=edt(()->context.meshEdit().snapshot());var point=editing.points().stream().min(java.util.Comparator.comparingDouble(p->p.x())).orElseThrow();
        var shifted=new dev.turboism.sdk.cubism.mesh.MeshPointRef(point.id(),point.x()-0.001f,point.y());
        var moved=edt(()->context.meshEdit().movePoints(List.of(shifted)));require(moved.accepted() && moved.rejected().isEmpty(),"actual bound native point edit rejected");
        require(edt(()->context.meshEdit().snapshot().points().stream().anyMatch(p->p.equals(shifted))),"actual native point did not move");
        report.put("pointEdit",Map.of("id",editedId.value(),"pointBefore",List.of(point.id(),point.x(),point.y()),"pointMoved",List.of(shifted.id(),shifted.x(),shifted.y()),"before",NativeAssertions.snapshot(beforeEdit)));
        edt(()->{SwingUtilities.invokeLater(button::doClick);return null;});
        await(()->edt(()->context.meshEdit().snapshot().points().isEmpty()),15,"actual bound point commit");Thread.sleep(1000);
        var committed=edt(()->current().drawables().find(editedId).authoringSnapshot());report.put("pointEditCommitted",NativeAssertions.snapshot(committed));
        require(committed.sourceGeometry().positions().size()==beforeEdit.sourceGeometry().positions().size() && !committed.sourceGeometry().positions().equals(beforeEdit.sourceGeometry().positions()),"bound point move not committed");
        require(orientedFaces(committed.sourceGeometry().triangleIndices()).equals(orientedFaces(beforeEdit.sourceGeometry().triangleIndices())),"actual point edit removed original faces");
        require(committed.axes().equals(beforeEdit.axes()) && committed.cells().equals(beforeEdit.cells()) && committed.forms().size()==49,"actual point edit changed layer binding domain");
        edt(()->{for(var e:beforeOthers.entrySet())require(compactMesh(current().drawables().find(e.getKey()).authoringSnapshot()).equals(e.getValue()),"point edit changed unrelated mesh");
            require(current().warpDeformers().find(replacement.warp().deformerId()).authoringSnapshot().equals(replacement.warp()),"point edit changed original XYZ Warp bank");
            require(current().rotationDeformers().all().stream().map(r->r.authoringSnapshot()).toList().equals(replacement.rotations()),"point edit changed original Roll/placement banks");return null;});
        var saved=edt(this::xyzState);report.put("savedState",saved);
        var artifact=ApplePersistenceFile.save(context.userFiles(),context.editorCommands(),context.paths().stateDir(),()->{require(saved.equals(xyzState()),"bound edited state changed before save");});
        require(saved.equals(edt(this::xyzState)),"bound SAVE_AS changed source state");
        report.put("boundPartitionSave",Map.of("artifact",artifact.relativePath(),"bytes",artifact.bytes(),"sha256",artifact.sha256()));
        var historyBeforeUndo=edt(()->context.cubism().history().snapshot());
        int added=historyBeforeUndo.position()-initialHistory;require(added>0,"bound edits missing history");
        report.put("seedUndo",Map.of("initialHistoryPosition",initialHistory,"requestedSteps",added,"before",historyBeforeUndo.toString()));
        edt(()->{context.cubism().history().undo(added);return null;});
        var restored=edt(this::xyzState);report.put("restoredState",restored);
        report.put("historyAfterSeedUndo",edt(()->context.cubism().history().snapshot()).toString());
        report.put("seedUndoChangedFields",initial.keySet().stream().filter(k->!java.util.Objects.equals(initial.get(k),restored.get(k))).toList());
        require(sameBoundSourceContent(initial,restored),"bound save diagnostic did not restore original task seed source content");
        report.put("restoredMeshEnumerationChanged",!initial.get("meshes").equals(restored.get("meshes")));
        Thread.sleep(1000);
        var restoredPixels=edt(()->current().renderPixels(1024,1024).argb());
        report.put("seedPixelsAfterSha256",pixelHash(restoredPixels));
        require(java.util.Arrays.equals(seedPixels,restoredPixels),"native enumeration restoration changes neutral rendering");
        report.put("neutralSeedPixelsRestored",true);
        report.put("originalTaskSeedRestored",true);report.put("fullAppleEditingAccepted",false);report.put("saveReopen","SAVED_BOUND_CHART26_EDIT_REOPEN_NOT_RUN");
    }

    // Drawables.all() has no order contract. Compare complete stored banks by unique ID,
    // retaining native enumeration in the report and checking rendered pixels separately.
    static boolean sameBoundSourceContent(Map<String,Object> before,Map<String,Object> after) {
        if(!before.keySet().equals(after.keySet()))return false;
        for(var key:before.keySet()) {
            if(key.equals("meshes")||key.equals("meshMetadata")) {
                var left=meshBankById(before.get(key));var right=meshBankById(after.get(key));
                if(left==null||right==null||!left.equals(right))return false;
            } else if(!java.util.Objects.equals(before.get(key),after.get(key)))return false;
        }
        return true;
    }
    private static Map<String,Object> meshBankById(Object values) {
        if(!(values instanceof List<?> rows))return null;
        var bank=new HashMap<String,Object>();
        for(var value:rows) {
            if(!(value instanceof Map<?,?> row)||!(row.get("id") instanceof String id)||bank.put(id,row)!=null)return null;
        }
        return bank;
    }
    private static String pixelHash(int[] argb)throws Exception {
        var bytes=java.nio.ByteBuffer.allocate(argb.length*4);
        for(int pixel:argb)bytes.putInt(pixel);
        return hash(bytes.array());
    }

    private void captureBoundPartitionPoses(ApplePartitionBoundCapture.Replacement replacement) throws Exception {
        var ids=edt(()->current().parameters().all().stream().map(p->p.id()).filter(p->p.value().startsWith("Param3D")).toList());
        require(ids.size()==3,"exact XYZ import axes required");
        var pitch=ids.stream().filter(p->p.value().startsWith("Param3DPitch")).findFirst().orElseThrow();
        var yaw=ids.stream().filter(p->p.value().startsWith("Param3DYaw")).findFirst().orElseThrow();
        var roll=ids.stream().filter(p->p.value().startsWith("Param3DRoll")).findFirst().orElseThrow();
        var rows=new ArrayList<Map<String,Object>>();report.put("boundPartitionPoses",rows);
        float[][] poses={{0,0,0},{90,0,0},{0,90,0},{0,0,90},{37,-61,113},{-123,67,-151},{180,0,0},{0,180,0},{0,0,180},{-180,-180,-180},{165,75,45},{-45,125,-75}};
        try {for(float[] pose:poses) {
            var row=edt(()->{
                var m=current();m.parameters().find(pitch).setValue(pose[0]);m.parameters().find(yaw).setValue(pose[1]);m.parameters().find(roll).setValue(pose[2]);m.update();
                var reference=m.drawables().find(ApplePartitionBoundCapture.ORIGINAL).evaluationSnapshot();var referencePositions=reference.calculatedForm().orElseThrow().positions();
                var captured=new ArrayList<Map<String,Object>>();double maximum=0;
                for(int g=0;g<replacement.ids().size();g++) {
                    var e=m.drawables().find(replacement.ids().get(g)).evaluationSnapshot();
                    for(int axis=0;axis<3;axis++){var pid=List.of(pitch,yaw,roll).get(axis);float requested=pose[axis];require(e.consumedParameters().parameters().stream().anyMatch(p->p.parameterId().equals(pid)&&p.value()==requested),"bound group did not consume XYZ pose");}
                    var actual=e.calculatedForm().orElseThrow().positions();var slots=replacement.groups().get(g).originalSlots();require(actual.size()==slots.size(),"bound evaluation slot domain");
                    for(int i=0;i<actual.size();i++)maximum=Math.max(maximum,Math.hypot((double)actual.get(i).x()-referencePositions.get(slots.get(i)).x(),(double)actual.get(i).y()-referencePositions.get(slots.get(i)).y()));
                    captured.add(Map.of("group",g,"positions",actual.stream().map(p->List.of(p.x(),p.y())).toList()));
                }
                require(maximum<=0.125,"bound clone changed original projection by more than 0.125px: "+maximum);
                return Map.<String,Object>of("pose",List.of(pose[0],pose[1],pose[2]),"referencePositions",referencePositions.stream().map(p->List.of(p.x(),p.y())).toList(),"groups",captured,"maxPairPixels",maximum);
            });rows.add(row);
        }} finally {edt(()->{for(var pid:List.of(pitch,yaw,roll))current().parameters().find(pid).setValue(0);current().update();return null;});}
    }

    private void runPartitionedSlotEditingProbe(byte[] raw) throws Exception {
        report.put("schemaVersion","mi3d-apple-partitioned-slot-edit-v1");
        report.put("scope","ISOLATED_REAL_APPLE_CHART26_PARTITIONED_SOURCE_NATIVE_COMMIT_NOT_FULL_MODEL_ACCEPTANCE");
        report.put("candidateSha256",hash(raw));
        String seedDocument=document;
        edt(()->{executed(context.editorCommands().execute(EditorCommand.NEW_MODEL));return null;});
        target=awaitDocument(seedDocument,"partitioned-chart26-copy");document=target.documentId();
        byte[] image;try(var input=UserEntryProbe.class.getResourceAsStream("mesh-edit-copy.png")) {
            require(input!=null,"same original source material required");image=input.readAllBytes();
        }
        var rows=new ArrayList<Map<String,Object>>();var seen=new HashSet<Integer>();int totalFaces=0;
        report.put("groups",rows);
        try(var input=new java.io.DataInputStream(new java.io.ByteArrayInputStream(raw))) {
            require(input.readInt()==0x4d493344 && input.readInt()==5,"exact partitioned source header");
            for(int group=0;group<5;group++) {
                int n=input.readInt(),k=input.readInt();require(n>=3 && n<=1108 && k>=3 && k<=6144 && k%3==0,"bounded source group");
                var positions=new ArrayList<dev.turboism.sdk.cubism.model.Point2>();
                var uvs=new ArrayList<dev.turboism.sdk.cubism.model.Point2>();var indices=new ArrayList<Integer>();
                for(int i=0;i<n;i++) {var point=new dev.turboism.sdk.cubism.model.Point2(input.readFloat(),input.readFloat());positions.add(point);
                    uvs.add(new dev.turboism.sdk.cubism.model.Point2(point.x()/1024,point.y()/256));}
                for(int i=0;i<k;i++)indices.add(input.readInt());var originalFaces=new ArrayList<Integer>();
                for(int i=0;i<k/3;i++) {int face=input.readInt();require(face>=0 && face<2048 && seen.add(face),"each original face once");originalFaces.add(face);}
                var geometry=new dev.turboism.sdk.cubism.model.ArtMeshGeometry(positions,uvs,indices);
                var grant=dev.turboism.sdk.cubism.model.AuthorizedImageContent.ofDimensions(image,"Original apple source materials",1024,256);
                int groupIndex=group;
                var id=edt(()->{var drawable=current().drawables().create("Apple chart26 source group "+groupIndex,null,-1,geometry);
                    current().textures().registerImageAndBind(grant,drawable.id());
                    require(drawable.authoringSnapshot().sourceGeometry().equals(geometry),"partition image binding changed geometry");
                    current().drawables().select(List.of(drawable.id()));return drawable.id();});
                var row=new java.util.LinkedHashMap<String,Object>();row.put("group",group);row.put("originalFaces",originalFaces);
                row.put("before",edt(()->NativeAssertions.snapshot(current().drawables().find(id).authoringSnapshot())));rows.add(row);
                var button=awaitComponent(AbstractButton.class,b->"网格编辑".equals(b.getToolTipText()),true);
                edt(()->{require(button.isEnabled(),"partition editing disabled");SwingUtilities.invokeLater(button::doClick);return null;});
                await(()->edt(()->context.meshEdit().snapshot().points().size()==n),15,"partition native editor entry");
                var entry=edt(()->context.meshEdit().snapshot());require(!entry.edges().isEmpty(),"partition native edges missing");
                row.put("nativeEntry",Map.of("points",entry.points().size(),"edges",entry.edges().size(),"vertexWrites",false));
                edt(()->{SwingUtilities.invokeLater(button::doClick);return null;});
                await(()->edt(()->context.meshEdit().snapshot().points().isEmpty()),15,"partition native editor exit");Thread.sleep(1000);
                var committed=edt(()->current().drawables().find(id).authoringSnapshot());row.put("committed",NativeAssertions.snapshot(committed));
                require(committed.sourceGeometry().positions().equals(positions) && committed.sourceGeometry().uvs().equals(uvs),"partition source attributes changed");
                require(orientedFaces(indices).equals(orientedFaces(committed.sourceGeometry().triangleIndices())),"partition native topology changed");
                row.put("exactOrientedFaces",true);totalFaces+=k/3;
            }
            require(input.read()==-1 && seen.size()==2048 && totalFaces==2048,"complete original chart face domain");
        }
        report.put("sourceTriangles",totalFaces);report.put("exactNativeCommit",true);report.put("fullAppleEditingAccepted",false);
    }
    static boolean sameOrientedFaces(List<Integer> before,List<Integer> after) {
        return orientedFaces(before).equals(orientedFaces(after));
    }
    private static Map<List<Integer>,Integer> orientedFaces(List<Integer> indices) {
        require(indices.size()%3==0,"complete oriented face domain");var result=new HashMap<List<Integer>,Integer>();
        for(int i=0;i<indices.size();i+=3) {int a=indices.get(i),b=indices.get(i+1),c=indices.get(i+2);
            var face=a<=b && a<=c?List.of(a,b,c):b<=a && b<=c?List.of(b,c,a):List.of(c,a,b);result.merge(face,1,Integer::sum);}
        return result;
    }

    /** Exercise the native editor on an isolated copy of one full apple source chart.
     * The saved delivery document is left untouched. No vertices or faces are removed. */
    private void runMeshEditingProbe() throws Exception {
        final boolean commitSave=Boolean.getBoolean("turboism.validation.mi3d.meshEditingCommitSave");
        require(!commitSave || (appleEditability && Boolean.getBoolean("turboism.validation.mi3d.originalMeshEditing")
            && Boolean.getBoolean("turboism.validation.mi3d.persistenceSave")),"dedicated original mesh commit/save required");
        final Map<String,Object> protectedBefore=commitSave?edt(this::xyzState):Map.of();
        editingCheckpoint("reading-first-chart");
        report.put("stage","native-mesh-editing");
        final var geometry=edt(()->current().drawables().all().get(0).authoringSnapshot().sourceGeometry());
        editingCheckpoint("first-chart-read");
        final String savedDocument=document;
        final boolean original=Boolean.getBoolean("turboism.validation.mi3d.originalMeshEditing");
        final dev.turboism.sdk.cubism.id.ArtMeshId copied;
        if(original) {
            require(appleEditability,"original editing requires dedicated reopened apple profile");
            copied=edt(()->current().drawables().all().get(0).id());
            report.put("meshEditingTarget",copied.value());
            report.put("meshEditingOriginalSeed",true);
        } else {
        edt(()->{executed(context.editorCommands().execute(EditorCommand.NEW_MODEL));return null;});
        target=awaitDocument(savedDocument,"mesh-editing-copy");document=target.documentId();
        editingCheckpoint("new-document-ready");
        byte[] sourceImage;
        try(var input=UserEntryProbe.class.getResourceAsStream("mesh-edit-copy.png")) {
            require(input!=null,"isolated edit copy requires authorized source materials");sourceImage=input.readAllBytes();
        }
        final var imageGrant=dev.turboism.sdk.cubism.model.AuthorizedImageContent.ofDimensions(sourceImage,
            "Lossless apple GLB source materials for isolated mesh edit copy",1024,256);
        copied=edt(()->{
            require(current().drawables().all().isEmpty(),"mesh probe document must be empty");
            var mesh=current().drawables().create("Apple source chart editing probe",null,-1,geometry);
            var binding=current().textures().registerImageAndBind(imageGrant,mesh.id());
            require(mesh.authoringSnapshot().sourceGeometry().equals(geometry),"source material binding changed chart geometry");
            report.put("meshEditingMaterial",Map.of("pngSha256",hash(sourceImage),"width",1024,"height",256,
                "modelImage",binding.modelImageId().value()));
            report.put("meshEditingTarget",mesh.id().value());return mesh.id();
        });
        }
        editingCheckpoint("chart-created");
        // Session runtime selection is intentionally unavailable (HostSessionSnapshotSource).
        // Select natively in a separate EDT turn and verify the actual editor below.
        edt(()->{
            current().drawables().select(List.of(copied));
            report.put("meshEditingSelectionObservation","UNAVAILABLE_IN_SESSION_RUNTIME");return null;
        });
        editingCheckpoint("chart-created-and-selected");
        var editButton=awaitComponent(AbstractButton.class,c->"网格编辑".equals(c.getToolTipText()),true);
        edt(()->{require(editButton.isEnabled(),"native mesh editing button disabled");
            SwingUtilities.invokeLater(editButton::doClick);return null;});
        editingCheckpoint("mesh-editor-button-clicked");
        Thread.sleep(500);
        edt(()->{
            var windows=new ArrayList<Map<String,Object>>();
            for(var window:Window.getWindows())if(window.isShowing()) {
                windows.add(Map.of("class",window.getClass().getName(),"bounds",window.getBounds().toString()));
            }
            report.put("meshEditingWindows",windows);
            var screen=java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
            require((long)screen.width*screen.height<=8_000_000,"bounded isolated UI screenshot");
            var ui=new java.awt.Robot().createScreenCapture(screen);
            var output=context.paths().stateDir().resolve("mesh-editor-ui.png");
            require(!Files.exists(output),"fresh UI screenshot required");
            require(javax.imageio.ImageIO.write(ui,"PNG",output.toFile()),"UI screenshot encoding");
            report.put("meshEditingUIArtifact","mesh-editor-ui.png");return null;
        });
        await(()->edt(()->{
            var snapshot=context.meshEdit().snapshot();
            report.put("meshEditingSnapshot",Map.of("points",snapshot.points().size(),"edges",snapshot.edges().size(),"expectedPoints",geometry.positions().size()));
            var panes=new ArrayList<JOptionPane>();var buttons=new ArrayList<AbstractButton>();
            for(var window:Window.getWindows())if(window.isShowing()) {
                walk(window,JOptionPane.class,c->true,true,panes,Collections.newSetFromMap(new IdentityHashMap<>()));
                walk(window,AbstractButton.class,c->c.getToolTipText()!=null || (c.getText()!=null && c.getText().contains("网格")),true,buttons,Collections.newSetFromMap(new IdentityHashMap<>()));
            }
            report.put("meshEditingDialogs",panes.stream().map(p->Map.of("title",title(p),"message",String.valueOf(p.getMessage()))).toList());
            for(var pane:panes) {
                String message=String.valueOf(pane.getMessage());
                if(message.contains("请先选择") || message.contains("不可编辑网格")) {
                    var dialog=SwingUtilities.getWindowAncestor(pane);
                    if(dialog!=null)dialog.dispose();
                    throw new IllegalStateException("native mesh editor refused selection: "+message);
                }
            }
            report.put("meshEditingButtons",buttons.stream().map(b->Map.of("text",String.valueOf(b.getText()),"tooltip",String.valueOf(b.getToolTipText()),"enabled",b.isEnabled())).toList());
            return snapshot.points().size()==geometry.positions().size();
        }),15,"native chart mesh edit mode");
        final var before=edt(()->context.meshEdit().snapshot());
        report.put("meshEditingSnapshot",Map.of("points",before.points().size(),"edges",before.edges().size(),
            "expectedPoints",geometry.positions().size()));
        require(before.points().size()==geometry.positions().size() && !before.edges().isEmpty(),
            "native mesh editor rejected original apple chart");
        final var point=before.points().get(0);
        final var shifted=new dev.turboism.sdk.cubism.mesh.MeshPointRef(point.id(),point.x()+0.25f,point.y());
        final var moved=edt(()->context.meshEdit().movePoints(List.of(shifted)));
        report.put("meshEditingMoveResult",Map.of("accepted",moved.accepted(),"rejected",moved.rejected(),
            "failureReason",String.valueOf(moved.failureReason())));
        require(moved.accepted() && moved.rejected().isEmpty(),"native mesh point edit refused: "+moved.failureReason());
        final var after=edt(()->context.meshEdit().snapshot());
        require(after.points().stream().anyMatch(p->p.equals(shifted)) && !after.equals(before),"native vertex did not move");
        if(commitSave) {
            editingCheckpoint("ending-original-mesh-edit");
            edt(()->{SwingUtilities.invokeLater(editButton::doClick);return null;});
            await(()->edt(()->context.meshEdit().snapshot().points().isEmpty()),30,"native mesh edit commit");
            final var committed=edt(this::xyzState);
            for(String field:protectedBefore.keySet())if(!field.equals("meshes"))
                require(protectedBefore.get(field).equals(committed.get(field)),"mesh commit changed protected "+field);
            final var oldMeshes=(List<?>)protectedBefore.get("meshes");final var newMeshes=(List<?>)committed.get("meshes");
            require(oldMeshes.size()==newMeshes.size() && !oldMeshes.get(0).equals(newMeshes.get(0)),"native mesh edit did not commit to source");
            for(int i=1;i<oldMeshes.size();i++)require(oldMeshes.get(i).equals(newMeshes.get(i)),"mesh edit changed unrelated chart "+i);
            final var oldChart=(Map<?,?>)oldMeshes.get(0);final var newChart=(Map<?,?>)newMeshes.get(0);
            for(String field:List.of("id","sourceGuid","vertexCount","triangleCount","axisCount","formCount","cellCount","axes","forms"))
                require(oldChart.get(field).equals(newChart.get(field)),"mesh edit changed chart binding/topology count "+field);
            report.put("meshEditingCommit",Map.of("before",protectedBefore,"after",committed,
                "pointBefore",List.of(point.id(),point.x(),point.y()),"pointMoved",List.of(shifted.id(),shifted.x(),shifted.y()),
                "nativeEditorEnded",true,"changedCharts",List.of(copied.value())));
            editingCheckpoint("saving-committed-mesh");
            var artifact=ApplePersistenceFile.save(context.userFiles(),context.editorCommands(),context.paths().stateDir(),()->{
                require(committed.equals(xyzState()),"committed model changed before SAVE_AS");
            });
            require(committed.equals(edt(this::xyzState)),"SAVE_AS changed committed model");
            report.put("meshEditingSave",Map.of("artifact",artifact.relativePath(),"bytes",artifact.bytes(),"sha256",artifact.sha256(),
                "scope","FIRST_ORIGINAL_CHART_COMMITTED_SAVED_REOPEN_NOT_VERIFIED"));
            editingCheckpoint("original-mesh-committed-saved");
            return;
        }
        final var restored=edt(()->context.meshEdit().movePoints(List.of(point)));
        require(restored.accepted() && restored.rejected().isEmpty()
            && before.equals(edt(()->context.meshEdit().snapshot())),"native vertex restoration failed");
        report.put("meshEditingProbe",Map.of("scope",original?"ORIGINAL_CHART_OF_REOPENED_DELIVERED_XYZ_APPLE_NO_FILE_WRITE":"ISOLATED_FULL_APPLE_SOURCE_CHART_COPY_AFTER_SAVE",
            "document",document,"savedDocument",savedDocument,"points",before.points().size(),
            "edges",before.edges().size(),"nativeEditorEntered",true,"vertexMoved",true,"verticesRestored",true));
    }

    private void runRejections(Path state) throws Exception {
        var seed=awaitDocument(null,"seed");
        edt(()->{require(seed.sameIdentity(observe()),"seed changed before NEW_MODEL");
            executed(context.editorCommands().execute(EditorCommand.NEW_MODEL));return null;});
        target=awaitDocument(seed.documentId(),"new");document=target.documentId();
        edt(()->{require(current().drawables().all().isEmpty(),"rejection target must be empty");
            executed(context.editorCommands().execute(new EditorCanvasSettingsRequest(1024,1024)));return null;});
        report.put("seedDocumentId",seed.documentId());report.put("targetDocumentId",document);
        var cases=new ArrayList<Map<String,Object>>();report.put("rejections",cases);
        String[][] fixtures={
            {"intersecting-sheets.glb","dfae2a74e43fb58ac7faf76ab43230fa84293df8f1dc16017665a147da6232fc","closed oriented two-manifold edge required"},
            {"cyclic-sheets.glb","6374ee20102596be9f0d9fb25536b9631ccb0202471acf125ffedbd2002f474a","source Warp candidate requires single-sided primitives"}};
        for(var fixture:fixtures) {
            byte[] bytes;
            try(var stream=UserEntryProbe.class.getResourceAsStream(fixture[0])) {
                require(stream!=null,"frozen rejection input missing");bytes=stream.readNBytes(1024*1024+1);
            }
            require(bytes.length<=1024*1024 && hash(bytes).equals(fixture[1]),"exact rejection fixture identity");
            Path input=state.resolve(fixture[0]);Files.write(input,bytes,StandardOpenOption.CREATE_NEW);
            for(String appearance:List.of("保留原材质","按面区分颜色")) {
                var row=new LinkedHashMap<String,Object>();cases.add(row);
                row.put("input",fixture[0]);row.put("inputSha256",fixture[1]);row.put("appearance",appearance);
                var before=edt(this::state);var h0=edt(()->context.cubism().history().snapshot());
                require(edt(()->current().drawables().all().isEmpty() && current().warpDeformers().all().isEmpty()
                    && current().textures().textureAtlases().isEmpty()),"empty refusal baseline required");
                row.put("before",before);row.put("historyBefore",AppleTextureCapture.history(h0));
                row.put("canUndoBefore",h0.canUndo());row.put("canRedoBefore",h0.canRedo());
                var menu=awaitComponent(JMenuItem.class,c->"导入 3D 模型…".equals(c.getText()),false);
                edt(()->{require(menu.isEnabled(),"import menu disabled");menu.doClick();return null;});row.put("menuInvoked",true);
                var choice=awaitComponent(JButton.class,c->appearance.equals(c.getText()),true);
                edt(()->{choice.doClick();return null;});
                var chooser=awaitComponent(JFileChooser.class,c->true,true);
                edt(()->{require("选择 GLB、glTF、OBJ 或模型 ZIP".equals(chooser.getDialogTitle()) && !chooser.isMultiSelectionEnabled(),"exact production chooser");
                    chooser.setSelectedFile(input.toFile());chooser.approveSelection();return null;});row.put("realChooserApproved",true);
                var error=awaitComponent(JOptionPane.class,c->title(c).equals("无法导入 3D 模型"),true);
                edt(()->{String text=String.valueOf(error.getMessage());row.put("errorTitle",title(error));row.put("errorMessage",text);
                    require(text.equals(appearance.equals("保留原材质")?fixture[2]:"nonconvex or inward-facing triangle"),"expected explicit structural rejection");
                    error.setValue(JOptionPane.OK_OPTION);return null;});
                var after=edt(this::state);var h1=edt(()->context.cubism().history().snapshot());
                require(before.equals(after) && h0.equals(h1),"rejected import changed authoring/resources/history");
                require(hash(Files.readAllBytes(input)).equals(fixture[1]),"selected source changed");
                row.put("after",after);row.put("historyAfter",AppleTextureCapture.history(h1));row.put("canUndoAfter",h1.canUndo());row.put("canRedoAfter",h1.canRedo());
                row.put("stateUnchanged",true);row.put("historyUnchanged",true);row.put("inputUnchanged",true);row.put("status","REJECTED_BEFORE_AUTHORING");
            }
        }
        require(cases.size()==4,"complete rejection matrix");
    }

    private Map<String,Object> savePacked() throws Exception {
        var before=edt(this::state);var h0=edt(()->context.cubism().history().snapshot());
        var profile=surface?ApplePersistenceFile.Profile.SURFACE:ApplePersistenceFile.Profile.APPLE;
        var artifact=ApplePersistenceFile.save(context.userFiles(),context.editorCommands(),context.paths().stateDir(),()->{
            require(state().equals(before),"packed authoring changed before SAVE_AS");
        },profile);
        var after=edt(this::state);var h1=edt(()->context.cubism().history().snapshot());
        require(before.equals(after) && h0.equals(h1),"packed SAVE_AS changed state/history");
        return Map.of("commandStatus","EXECUTED","artifact",artifact.relativePath(),"artifactBytes",artifact.bytes(),
            "artifactSha256",artifact.sha256(),"maximumBytes",profile.maximumBytes,
            "before",before,"after",after,"historyBefore",AppleTextureCapture.history(h0),"historyAfter",AppleTextureCapture.history(h1));
    }

    private void runReopen(Path state) throws Exception {
        report.put("stage","waiting-reopened-seed");
        target=awaitDocument(null,"reopen");document=target.documentId();
        if(appleAtlasReopen)edt(()->{
            require(current().textures().textureAtlases().size()==1,"one reopened packed atlas required");
            executed(context.editorCommands().execute(EditorCommand.SHOW_MODEL_BY_TEXTURE_ATLAS));return null;
        });
        var reopened=edt(()->{
            var model=current();
            require(model.drawables().all().size()==98 && model.warpDeformers().all().size()==1,"reopened complete apple inventory");
            require(model.drawables().all().stream().allMatch(m->m.authoringSnapshot().forms().size()==81),"reopened dense mesh forms");
            require(model.warpDeformers().all().get(0).authoringSnapshot().forms().size()==289,"reopened dense warp forms");
            return state();
        });
        report.put("reopened",reopened);report.put("targetDocumentId",document);
        if(appleAtlasReopen)edt(()->{
            require(current().textures().textureAtlases().size()==1,"one reopened packed atlas required");
            report.put("reopenedAtlasLayout",AppleOfficialExport.layout(context.cubism().textureAtlasLayouts()));
            report.put("reopenedAtlases",current().textures().textureAtlases().stream()
                .map(a->List.of(a.id().value(),a.name(),a.width(),a.height(),a.modelImageCount())).toList());return null;
        });
        AppleTextureCapture.run(this::current,this::state,()->context.cubism().history().snapshot(),
            count->context.cubism().history().undo(count),state,report);
        var after=edt(this::state);
        require(after.equals(reopened),"reopen capture changed durable state");
        report.put("afterReopenCapture",after);
        report.put("renderVerification","CAPTURED_REQUIRES_INDEPENDENT_ORACLE");
        report.put("saveReopen","REOPENED_SEED_CAPTURED_REQUIRES_PAIR_CHECK");
    }

    private void editingCheckpoint(String stage) throws Exception {
        Files.writeString(context.paths().stateDir().resolve("mesh-editing-stage.txt"),stage+"\n");
    }
    private void runCatNativeSerialization(Path state) throws Exception {
        report.put("schemaVersion","mi3d-cat-native-serialization-v1");
        report.put("scope","DEDICATED_NATIVE_DOCUMENT_SAVE_AND_BACKUP_MANAGER_NO_GEOMETRY_EDIT_NO_POSE_REPLAY");
        var snapshot=edt(()->{var meshes=current().drawables().all();require(meshes.size()==512,"exact Cat mesh inventory");return meshes.get(0).authoringSnapshot();});
        var history=edt(()->context.cubism().history().snapshot());
        var request=new Properties();request.setProperty("sourceGuid",snapshot.sourceGuid());
        request.setProperty("seedSha256",System.getProperty("turboism.validation.mi3d.serializationSeedSha",""));
        Path temporary=state.resolve("serialization-request.tmp");
        try(var out=Files.newOutputStream(temporary,StandardOpenOption.CREATE_NEW)){request.store(out,"native save and backup request");}
        Files.move(temporary,state.resolve("serialization-request.properties"));
        var path=state.resolve("serialization-response.properties");await(()->Files.isRegularFile(path),900,"native Cat save and backup response");
        var response=new Properties();try(var input=Files.newInputStream(path)){response.load(input);}
        var row=new LinkedHashMap<String,String>();for(String key:response.stringPropertyNames())row.put(key,response.getProperty(key));
        report.put("native",row);require("PASS".equals(row.get("status")),"native serialization failed: "+row.get("error"));
        require("mi3d-cat-native-serialization-v1".equals(row.get("schemaVersion")),"native serialization schema");
        require(snapshot.equals(edt(()->current().drawables().all().get(0).authoringSnapshot())),"native save changed source geometry or original bindings");
        require(history.equals(edt(()->context.cubism().history().snapshot())),"native serialization changed Undo history");
        report.put("geometryAndBindingsUnchanged",true);report.put("historyUnchanged",true);
        if("cat-default-serialization".equals(System.getProperty("turboism.validation.mi3d.mode"))) {
            var bank=edt(()->CatVertexCapture.persistentBank(current()));
            long start=System.nanoTime();
            var artifact=ApplePersistenceFile.save(context.userFiles(),context.editorCommands(),state,
                ()->require(snapshot.equals(current().drawables().all().get(0).authoringSnapshot()),"SDK save source identity"),ApplePersistenceFile.Profile.CAT);
            report.put("sdkSaveNs",System.nanoTime()-start);
            report.put("sdkSavedArtifact",Map.of("relativePath",artifact.relativePath(),"bytes",artifact.bytes(),"sha256",artifact.sha256()));
            require(bank.equals(edt(()->CatVertexCapture.persistentBank(current()))),"SDK SAVE_AS changed geometry or bindings");
            require(history.equals(edt(()->context.cubism().history().snapshot())),"SDK SAVE_AS changed Undo history");
            report.put("sdkSaveBankAndHistoryUnchanged",true);
        }
    }

    private Map<String,String> nativeDragRequest(Path state,int sequence,String action,String guid,float x,float y) throws Exception {
        var request=new Properties();request.setProperty("action",action);request.setProperty("sourceGuid",guid);
        if(List.of("cat-optimized-short-drag","cat-adaptive-short-drag").contains(System.getProperty("turboism.validation.mi3d.mode")))request.setProperty("steps","3");
        request.setProperty("documentX",Float.toString(x));request.setProperty("documentY",Float.toString(y));
        Path temporary=state.resolve("drag-request-"+sequence+".tmp");
        try(var output=Files.newOutputStream(temporary,StandardOpenOption.CREATE_NEW)){request.store(output,"bounded Cat drag");}
        Files.move(temporary,state.resolve("drag-request-"+sequence+".properties"));
        var path=state.resolve("drag-response-"+sequence+".properties");
        await(()->Files.isRegularFile(path),List.of("cat-optimized-short-drag","cat-adaptive-short-drag").contains(System.getProperty("turboism.validation.mi3d.mode"))?600:180,"native Cat "+action+" response");
        var response=new Properties();try(var input=Files.newInputStream(path)){response.load(input);}
        var row=new LinkedHashMap<String,String>();for(var key:response.stringPropertyNames())row.put(key,response.getProperty(key));
        return row;
    }

    private void runCatNativeDrags(Path state) throws Exception {
        boolean shortDrag=List.of("cat-optimized-short-drag","cat-adaptive-short-drag").contains(System.getProperty("turboism.validation.mi3d.mode"));
        boolean costDrag="cat-cost-drag".equals(System.getProperty("turboism.validation.mi3d.mode"));
        boolean editableDrag=Boolean.getBoolean("turboism.validation.mi3d.editableDrag");
        boolean dragTrace=Boolean.getBoolean("turboism.validation.mi3d.dragTrace");
        require(!dragTrace || (editableDrag && costDrag),"trace requires editable cost drag");
        require(!editableDrag || costDrag,"editable drag requires dedicated cost profile");
        boolean optimized=costDrag || shortDrag || "cat-optimized-drag".equals(System.getProperty("turboism.validation.mi3d.mode"));
        report.put("schemaVersion",dragTrace?"mi3d-cat-editable-cost-drag-trace-v1":editableDrag?"mi3d-cat-editable-cost-largest-native-drag-v1":costDrag?"mi3d-cat-cost-largest-native-drag-v1":"cat-adaptive-short-drag".equals(System.getProperty("turboism.validation.mi3d.mode"))?"mi3d-cat-adaptive-native-short-drag-v1":shortDrag?"mi3d-cat-optimized-native-short-drag-v1":optimized?"mi3d-cat-optimized-native-drag-v2":"mi3d-cat-native-drag-v1");
        if(optimized)edt(()->{if(editableDrag)CatVertexCapture.editableCostInventory(current());else if(costDrag)CatVertexCapture.costInventory(current());else if("cat-adaptive-short-drag".equals(System.getProperty("turboism.validation.mi3d.mode")))CatVertexCapture.adaptiveInventory(current());else CatVertexCapture.optimizedInventory(current());return null;});
        report.put("scope",shortDrag?"ISOLATED_OFFICIAL_VIEW_THREE_STEP_MOUSE_EVENT_BASELINE_CANDIDATE_UNDO_NOT_OS_INPUT":optimized?"ISOLATED_OFFICIAL_VIEW_MOUSE_EVENT_BASELINE_CANDIDATE_UNDO_NOT_OS_INPUT":"ISOLATED_CANVAS_AWT_EVENT_BASELINE_CANDIDATE_UNDO_NOT_OS_INPUT");
        var id=edt(()->{var meshes=current().drawables().all();require(meshes.size()==(editableDrag?615:512),"Cat drag mesh domain");
            require(!current().defaultKeyformLocked(),"Cat default form locked");var mesh=costDrag?meshes.stream().max(java.util.Comparator.comparingInt(d->d.authoringSnapshot().sourceGeometry().positions().size())).orElseThrow():meshes.get(0);
            report.put("selectedMesh",mesh.id().value());report.put("selectedSourceVertices",mesh.authoringSnapshot().sourceGeometry().positions().size());report.put("selectionPolicy",costDrag?"LARGEST_SOURCE_VERTEX_COUNT":"FIRST_MESH");
            var chosen=mesh.id();current().drawables().select(List.of(chosen));current().update();return chosen;});
        var source=edt(()->current().drawables().find(id).authoringSnapshot());
        var evaluated=edt(()->current().drawables().find(id).evaluationSnapshot().calculatedForm().orElseThrow());
        require(evaluated.coordinateType().equals("Canvas"),"document-space current form required");
        float x=(float)evaluated.positions().stream().mapToDouble(p->p.x()).average().orElseThrow();
        float y=(float)evaluated.positions().stream().mapToDouble(p->p.y()).average().orElseThrow();
        var map=nativeDragRequest(state,0,"map",source.sourceGuid(),x,y);report.put("nativeMapping",map);
        require("PASS".equals(map.get("status")),"native camera mapping failed: "+map.get("error"));
        var bank=edt(()->CatVertexCapture.bank(current()));report.put("beforeBank",bank);
        var phases=new ArrayList<Map<String,Object>>();report.put("phases",phases);
        for(int sequence=1;sequence<=(dragTrace?1:2);sequence++) {
            String action=dragTrace || sequence==2?"candidate":"baseline";var row=new LinkedHashMap<String,Object>();row.put("action",action);phases.add(row);
            int history=edt(()->context.cubism().history().snapshot().position());
            try {
                var measured=nativeDragRequest(state,sequence,action,source.sourceGuid(),x,y);row.put("native",measured);
                require("PASS".equals(measured.get("status")),"native drag failed: "+measured.get("error"));
                var after=edt(()->{current().update();return current().drawables().find(id).authoringSnapshot();});
                row.put("sourceGeometryUnchanged",source.sourceGeometry().equals(after.sourceGeometry()));
                row.put("axesUnchanged",source.axes().equals(after.axes()));row.put("cellsUnchanged",source.cells().equals(after.cells()));
                require(source.sourceGeometry().equals(after.sourceGeometry()) && source.axes().equals(after.axes()) && source.cells().equals(after.cells()),"whole-mesh drag changed source geometry or parameter bindings");
                var changed=new ArrayList<String>();
                for(var beforeForm:source.forms())if(!beforeForm.equals(after.forms().stream().filter(f->f.formGuid().equals(beforeForm.formGuid())).findFirst().orElseThrow()))changed.add(beforeForm.formGuid());
                row.put("changedForms",changed);require(!changed.isEmpty(),"native mouse events did not move selected mesh");
                var moved=edt(()->current().drawables().find(id).evaluationSnapshot().calculatedForm().orElseThrow());
                require(moved.positions().size()==evaluated.positions().size(),"drag changed vertex count");
                double dx=moved.positions().get(0).x()-evaluated.positions().get(0).x(),dy=moved.positions().get(0).y()-evaluated.positions().get(0).y(),spread=0;
                for(int i=0;i<moved.positions().size();i++)spread=Math.max(spread,Math.hypot(moved.positions().get(i).x()-evaluated.positions().get(i).x()-dx,moved.positions().get(i).y()-evaluated.positions().get(i).y()-dy));
                row.put("deltaCanvas",List.of(dx,dy));row.put("translationSpreadPixels",spread);
                double expectedX=Double.parseDouble(measured.get("expectedDeltaCanvasX")),expectedY=Double.parseDouble(measured.get("expectedDeltaCanvasY"));
                row.put("expectedDeltaCanvas",List.of(expectedX,expectedY));
                require(Math.hypot(dx-expectedX,dy-expectedY)<=.01,"native drag displacement differs from camera-mapped 20px gesture");
                if(costDrag)require(spread<=.01,"whole-mesh drag distorted vertices");
                row.put("vertexDistortionObserved",spread>.01);row.put("movedPositions",moved.positions().stream().map(p->List.of(p.x(),p.y())).toList());
                if(action.equals("candidate"))require(Long.parseLong(measured.get("acceptedDelta"))>0,"candidate not exercised by drag");
            } finally {
                int delta=edt(()->context.cubism().history().snapshot().position())-history;row.put("historyDelta",delta);
                require(delta>=0 && delta<=4,"unexpected drag history delta");
                if(delta>0)edt(()->{context.cubism().history().undo(delta);current().update();return null;});
                var restored=edt(()->CatVertexCapture.bank(current()));row.put("restoredBank",restored);
                require(bank.equals(restored),"drag Undo did not restore complete source bank");
            }
        }
        report.put("sourceUnchanged",true);report.put("osMouseInputTested",false);report.put("performanceAccepted",false);
        report.put("reviewRequired","Raw event metrics, uniform translation and pair comparison require independent review");
    }

    private void runCatDragLayout(Path state) throws Exception {
        report.put("schemaVersion","mi3d-cat-drag-layout-v1");
        report.put("scope","ISOLATED_EDITOR_CANVAS_LAYOUT_DISCOVERY_NOT_DRAG_OR_PERFORMANCE_ACCEPTANCE");
        require(Boolean.getBoolean("turboism.meshInterpolation.enabled"),"explicit interpolation candidate required");
        var selected=edt(()->{
            var model=current();var meshes=model.drawables().all();require(meshes.size()==512,"exact Cat mesh domain");
            var mesh=meshes.get(0);require(mesh.authoringSnapshot().forms().size()==81,"ordinary Cat form domain");
            require(!model.defaultKeyformLocked(),"Cat default form locked");
            model.drawables().select(List.of(mesh.id()));return mesh.id();
        });
        var before=edt(()->current().drawables().find(selected).authoringSnapshot());
        int history=edt(()->context.cubism().history().snapshot().position());
        Thread.sleep(500);
        edt(()->{
            var rows=new ArrayList<Map<String,Object>>();
            var components=new ArrayList<Component>();var seen=Collections.newSetFromMap(new IdentityHashMap<Component,Boolean>());
            for(var window:Window.getWindows())if(window.isShowing())walk(window,Component.class,Component::isShowing,true,components,seen);
            require(components.size()<=4096,"bounded UI layout inventory");
            for(var component:components) {
                var origin=component.getLocationOnScreen();var row=new LinkedHashMap<String,Object>();
                row.put("class",component.getClass().getName());row.put("name",String.valueOf(component.getName()));
                row.put("screenBounds",List.of(origin.x,origin.y,component.getWidth(),component.getHeight()));
                row.put("awtCanvas",component instanceof Canvas);row.put("focusable",component.isFocusable());
                if(component instanceof JComponent swing)row.put("tooltip",String.valueOf(swing.getToolTipText()));
                if(component instanceof AbstractButton button)row.put("text",String.valueOf(button.getText()));
                rows.add(row);
            }
            report.put("uiLayout",rows);report.put("selectedMesh",selected.value());
            report.put("selectedSource",NativeAssertions.snapshot(before));
            report.put("selectedEvaluation",current().drawables().find(selected).evaluationSnapshot().calculatedForm()
                .map(form->Map.of("positions",form.positions().stream().map(p->List.of(p.x(),p.y())).toList(),"coordinateType",form.coordinateType())).orElse(Map.of()));
            return null;
        });
        var bounds=GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        require((long)bounds.width*bounds.height<=8_000_000,"bounded dedicated Editor capture");
        var output=state.resolve("cat-drag-layout.png");require(!Files.exists(output),"fresh layout screenshot");
        require(javax.imageio.ImageIO.write(new Robot().createScreenCapture(bounds),"PNG",output.toFile()),"layout image encoding");
        report.put("screenshot",output.getFileName().toString());report.put("screenBounds",List.of(bounds.x,bounds.y,bounds.width,bounds.height));
        report.put("heapUsedBytes",java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        require(before.equals(edt(()->current().drawables().find(selected).authoringSnapshot())),"layout observation changed source");
        require(history==edt(()->context.cubism().history().snapshot().position()),"layout observation changed history");
        report.put("mouseDragExecuted",false);report.put("editorDragAccepted",false);report.put("sourceUnchanged",true);
    }

    private void runCatNativeEditors() throws Exception {
        runCatNativeEditors(false);
    }
    private void runCatNativeEditors(boolean editableCost) throws Exception {
        var before=edt(()->{if(editableCost)CatVertexCapture.editableCostInventory(current());else CatVertexCapture.inventory(current());return CatVertexCapture.persistentBank(current());});
        report.put("beforeBank",before);
        var ids=edt(()->current().drawables().all().stream().map(d->d.id()).toList());
        var rows=new ArrayList<Map<String,Object>>();report.put("meshEditors",rows);
        for(int index:editableCost?new int[]{13,0,270,271,562,563,148}:new int[]{0,256,511}) {
            var id=ids.get(index);var row=new LinkedHashMap<String,Object>();rows.add(row);
            row.put("index",index);row.put("id",id.value());editingCheckpoint("cat-mesh-entry-"+index);
            var source=edt(()->current().drawables().find(id).authoringSnapshot());
            row.put("sourceVertices",source.sourceGeometry().positions().size());
            row.put("sourceTriangles",source.sourceGeometry().triangleIndices().size()/3);
            var entryHistory=edt(()->context.cubism().history().snapshot());
            edt(()->{require(!current().defaultKeyformLocked(),"Cat default keyform locked");current().drawables().select(List.of(id));return null;});
            var button=awaitComponent(AbstractButton.class,b->"网格编辑".equals(b.getToolTipText()),true);
            row.put("buttonEnabled",edt(button::isEnabled));
            edt(()->{require(button.isEnabled(),"Cat mesh editor disabled "+id.value());SwingUtilities.invokeLater(button::doClick);return null;});
            await(()->edt(()->{
                var panes=new ArrayList<JOptionPane>();
                for(var w:Window.getWindows())if(w.isShowing())walk(w,JOptionPane.class,c->true,true,panes,Collections.newSetFromMap(new IdentityHashMap<>()));
                row.put("dialogs",panes.stream().map(p->String.valueOf(p.getMessage())).toList());
                for(var pane:panes){var text=String.valueOf(pane.getMessage());require(!text.contains("请先选择") && !text.contains("不可编辑网格"),"Cat native editing refused "+id.value()+": "+text);}
                int points=context.meshEdit().snapshot().points().size();row.put("nativePoints",points);
                return points==source.sourceGeometry().positions().size();
            }),15,"Cat mesh editor entry "+id.value());
            var mesh=edt(()->context.meshEdit().snapshot());require(!mesh.edges().isEmpty(),"Cat native edges missing");
            row.put("nativeEdges",mesh.edges().size());row.put("nativeEditorEntered",true);row.put("vertexWrites",false);
            edt(()->{SwingUtilities.invokeLater(button::doClick);return null;});
            await(()->edt(()->context.meshEdit().snapshot().points().isEmpty()),15,"Cat mesh editor exit "+id.value());
            row.put("nativeEditorEnded",true);
            try {
                var ended=edt(()->current().drawables().find(id).authoringSnapshot());
                row.put("geometryEqualAfterCommit",source.sourceGeometry().equals(ended.sourceGeometry()));
                row.put("positionsEqualAfterCommit",source.sourceGeometry().positions().equals(ended.sourceGeometry().positions()));
                row.put("uvsEqualAfterCommit",source.sourceGeometry().uvs().equals(ended.sourceGeometry().uvs()));
                row.put("indicesEqualAfterCommit",source.sourceGeometry().triangleIndices().equals(ended.sourceGeometry().triangleIndices()));
                row.put("orientedFacesEqualAfterCommit",sameOrientedFaces(source.sourceGeometry().triangleIndices(),ended.sourceGeometry().triangleIndices()));
                row.put("identityEqualAfterCommit",source.artMeshId().equals(ended.artMeshId()) && source.sourceGuid().equals(ended.sourceGuid()) && source.morphKeys().equals(ended.morphKeys()));
                row.put("geometryBefore",Map.of("positions",source.sourceGeometry().positions().stream().map(p->List.of(p.x(),p.y())).toList(),"uvs",source.sourceGeometry().uvs().stream().map(p->List.of(p.x(),p.y())).toList(),"indices",source.sourceGeometry().triangleIndices()));
                row.put("geometryAfter",Map.of("positions",ended.sourceGeometry().positions().stream().map(p->List.of(p.x(),p.y())).toList(),"uvs",ended.sourceGeometry().uvs().stream().map(p->List.of(p.x(),p.y())).toList(),"indices",ended.sourceGeometry().triangleIndices()));
                row.put("formsEqualAfterCommit",source.forms().equals(ended.forms()));
                row.put("axesEqualAfterCommit",source.axes().equals(ended.axes()));
                row.put("cellsEqualAfterCommit",source.cells().equals(ended.cells()));
                row.put("committedVertices",ended.sourceGeometry().positions().size());
                row.put("committedTriangles",ended.sourceGeometry().triangleIndices().size()/3);
                row.put("sourceEqualAfterCommit",source.equals(ended));
            } finally {
                int delta=edt(()->context.cubism().history().snapshot().position())-entryHistory.position();
                row.put("commitHistoryDelta",delta);require(delta>=0 && delta<=4,"unexpected Cat commit history delta");
                if(delta>0)edt(()->{context.cubism().history().undo(delta);return null;});
                row.put("sourceRestored",source.equals(edt(()->current().drawables().find(id).authoringSnapshot())));
                require(Boolean.TRUE.equals(row.get("sourceRestored")),"Cat native edit source restoration failed");
            }
            // A changed no-write native commit is a diagnostic observation, never edit acceptance.
            require(Boolean.TRUE.equals(row.get("formsEqualAfterCommit")) && Boolean.TRUE.equals(row.get("axesEqualAfterCommit")) && Boolean.TRUE.equals(row.get("cellsEqualAfterCommit")),"Cat native commit changed parameter bank");
            if(editableCost)require(Boolean.TRUE.equals(row.get("positionsEqualAfterCommit"))
                && Boolean.TRUE.equals(row.get("uvsEqualAfterCommit"))
                && Boolean.TRUE.equals(row.get("orientedFacesEqualAfterCommit"))
                && Boolean.TRUE.equals(row.get("identityEqualAfterCommit")),"editable Cat no-write native commit changed source geometry or identity");
        }
        var after=edt(()->CatVertexCapture.persistentBank(current()));report.put("afterBank",after);
        require(before.equals(after),"Cat native edit changed full durable bank");
        report.put("sourceUnchanged",true);report.put("all512NativeEditorAccepted",false);
        if(editableCost){report.put("representative615NativeEditorsAccepted",true);report.put("all615NativeEditorsAccepted",false);}
        editingCheckpoint("cat-three-mesh-edit-complete");
    }
    private void runAllAppleMeshEditors() throws Exception {
        var before=edt(()->{observeAppleMeshFlags();return xyzState();});report.put("before",before);
        var ids=edt(()->current().drawables().all().stream().map(d->d.id()).toList());
        var rows=new ArrayList<Map<String,Object>>();report.put("meshEditors",rows);
        final int startIndex=Integer.getInteger("turboism.validation.mi3d.meshStartIndex",0);
        require(startIndex==0 || startIndex==26,"explicit source chart start required");report.put("meshStartIndex",startIndex);
        final int endIndex=Integer.getInteger("turboism.validation.mi3d.meshEndIndex",ids.size());
        require(endIndex==ids.size() || (startIndex==26 && endIndex==27),"explicit single chart diagnostic required");
        report.put("meshEndIndex",endIndex);
        if(startIndex!=0)report.put("scope","REMAINING_ORIGINAL_MESH_NATIVE_EDITOR_ENTRY_EXIT_RESTORE_WITHOUT_VERTEX_WRITES");
        if(endIndex==27)report.put("scope","SINGLE_CHART_NATIVE_COMMIT_SETTLE_DIAGNOSTIC_NOT_EDIT_ACCEPTANCE");
        for(var id:ids.subList(startIndex,endIndex)) {
            editingCheckpoint("all-mesh-entry-"+(startIndex+rows.size()));
            var source=edt(()->current().drawables().find(id).authoringSnapshot());
            var entryHistory=edt(()->context.cubism().history().snapshot());
            edt(()->{current().drawables().select(List.of(id));return null;});
            var button=awaitComponent(AbstractButton.class,b->"网格编辑".equals(b.getToolTipText()),true);
            edt(()->{require(button.isEnabled(),"mesh editor disabled for "+id.value());SwingUtilities.invokeLater(button::doClick);return null;});
            await(()->edt(()->{
                var panes=new ArrayList<JOptionPane>();
                for(var w:Window.getWindows())if(w.isShowing())walk(w,JOptionPane.class,c->true,true,panes,Collections.newSetFromMap(new IdentityHashMap<>()));
                for(var pane:panes){var text=String.valueOf(pane.getMessage());require(!text.contains("请先选择") && !text.contains("不可编辑网格"),"native editing refused "+id.value()+": "+text);}
                int actualPoints=context.meshEdit().snapshot().points().size();
                report.put("lastMeshEntryObservation",Map.of("id",id.value(),"actualPoints",actualPoints,"expectedPoints",source.sourceGeometry().positions().size(),"dialogs",panes.stream().map(p->String.valueOf(p.getMessage())).toList()));
                return actualPoints==source.sourceGeometry().positions().size();
            }),15,"mesh editor entry "+id.value());
            var mesh=edt(()->context.meshEdit().snapshot());
            require(!mesh.edges().isEmpty(),"missing native edges "+id.value());
            if(endIndex==27) {
                report.put("nativeEditingPoints",mesh.points().stream().map(p->List.of(p.id(),p.x(),p.y())).toList());
                report.put("nativeEditingEdges",mesh.edges().stream().map(e->List.of(e.startPointId(),e.endPointId(),e.kind().name())).toList());
            }
            edt(()->{SwingUtilities.invokeLater(button::doClick);return null;});
            await(()->edt(()->context.meshEdit().snapshot().points().isEmpty()),15,"mesh editor exit "+id.value());
            if(endIndex==27) {
                var samples=new ArrayList<Map<String,Object>>();report.put("commitSettleSamples",samples);
                long started=System.nanoTime();
                for(long milliseconds:new long[]{0,100,500,1500,5000,10000}) {
                    long remaining=milliseconds-(System.nanoTime()-started)/1000000;
                    if(remaining>0)Thread.sleep(remaining);
                    var sample=new LinkedHashMap<String,Object>();sample.put("milliseconds",(System.nanoTime()-started)/1000000);
                    sample.put("history",edt(()->context.cubism().history().snapshot()).position());
                    try {
                        var observed=edt(()->current().drawables().find(id).authoringSnapshot());
                        sample.put("sourceVertices",observed.sourceGeometry().positions().size());
                        sample.put("sourceTriangles",observed.sourceGeometry().triangleIndices().size()/3);
                        sample.put("sourceEqualsOriginal",source.equals(observed));
                        sample.put("geometry",NativeAssertions.snapshot(observed));
                    } catch(Exception problem){sample.put("readError",problem.toString());}
                    samples.add(sample);
                }
            }
            final dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot ended;
            try {ended=edt(()->current().drawables().find(id).authoringSnapshot());}
            catch(Exception invalidSource) {
                report.put("failedCommittedSourceRead",invalidSource.toString());
                int failedDelta=edt(()->context.cubism().history().snapshot().position())-entryHistory.position();
                report.put("failedCommitHistoryDelta",failedDelta);
                require(failedDelta>0 && failedDelta<=4,"invalid native commit cannot be safely restored");
                edt(()->{context.cubism().history().undo(failedDelta);return null;});
                report.put("failedCommitSourceRestored",source.equals(edt(()->current().drawables().find(id).authoringSnapshot())));
                report.put("failedCommitWholeModelRestored",before.equals(edt(this::xyzState)));
                throw invalidSource;
            }
            boolean commitChanged=!source.equals(ended);int delta=edt(()->context.cubism().history().snapshot().position())-entryHistory.position();
            var row=new LinkedHashMap<String,Object>();rows.add(row);
            row.put("id",id.value());row.put("vertices",mesh.points().size());row.put("edges",mesh.edges().size());row.put("nativeEditorEntered",true);row.put("nativeEditorEnded",true);row.put("vertexWrites",false);
            row.put("nativeCommitChangedSource",commitChanged);row.put("commitHistoryDelta",delta);
            row.put("geometryEqualAfterNativeCommit",source.sourceGeometry().equals(ended.sourceGeometry()));row.put("axesEqualAfterNativeCommit",source.axes().equals(ended.axes()));row.put("cellsEqualAfterNativeCommit",source.cells().equals(ended.cells()));row.put("formsEqualAfterNativeCommit",source.forms().equals(ended.forms()));
            row.put("committedVertices",ended.sourceGeometry().positions().size());
            row.put("committedTriangles",ended.sourceGeometry().triangleIndices().size()/3);
            if(commitChanged && rows.size()==1){report.put("firstNativeCommitBefore",NativeAssertions.snapshot(source));report.put("firstNativeCommitAfter",NativeAssertions.snapshot(ended));}
            require(delta>=0 && delta<=4,"unexpected native editor history delta "+id.value());
            if(commitChanged){require(delta>0,"changed native commit has no Undo entry "+id.value());edt(()->{context.cubism().history().undo(delta);return null;});}
            require(source.equals(edt(()->current().drawables().find(id).authoringSnapshot())),"native editor restoration failed "+id.value());
            row.put("sourceRestored",true);
        }
        require(ids.size()==98 && before.equals(edt(this::xyzState)),"all-chart editing changed durable source");
        report.put("sourceUnchanged",true);report.put("all98NativeEditorAccepted",startIndex==0 && endIndex==ids.size());report.put("nativeEditorsCompleted",rows.size());
        editingCheckpoint("all-mesh-edit-complete");
    }
    private void observeAppleMeshFlags() {
        var m=current();require(m.drawables().all().size()==98,"all Apple charts required");
        require(!m.defaultKeyformLocked(),"default keyform locked");
        var rows=m.drawables().all().stream().map(d->{
            require(!d.locked() && !d.lockedInHierarchy(),"locked Apple chart "+d.id().value());
            return Map.of("id",d.id().value(),"locked",d.locked(),"lockedInHierarchy",d.lockedInHierarchy(),"visible",d.visible(),"sourceVertices",d.authoringSnapshot().sourceGeometry().positions().size());
        }).toList();report.put("allMeshFlags",rows);report.put("defaultKeyformLocked",m.defaultKeyformLocked());
        report.put("meshEditorCommandAvailable",context.editorCommands().available().contains(EditorCommand.START_OR_END_MESH_EDITOR));
    }
    private void runAppleEditabilityLight() throws Exception {
        editingCheckpoint("waiting-seed");
        target=awaitDocument(null,"editability");document=target.documentId();
        edt(()->{
            var m=current();
            require(m.drawables().all().size()==98 && m.warpDeformers().all().size()==1
                && m.textures().textureAtlases().isEmpty(),"exact source-image apple seed inventory");
            observeAppleMeshFlags();
            report.put("targetDocumentId",document);
            report.put("seedObservation","READ_ONLY_INVENTORY_AND_FIRST_FULL_SOURCE_CHART; WRAPPER_HASH_GUARDS_COMPLETE_SEED");
            return null;
        });
        editingCheckpoint("seed-observed");
    }

    private void runAppleEditability() throws Exception {
        report.put("stage","waiting-editability-seed");
        target=awaitDocument(null,"editability");document=target.documentId();
        edt(()->{
            var m=current();
            require(m.drawables().all().size()==98 && m.warpDeformers().all().size()==1
                && m.textures().textureAtlases().size()==(Boolean.getBoolean("turboism.validation.mi3d.meshEditingProbe")?0:1),
                "exact explicit apple inventory");
            var before=state();var history=context.cubism().history().snapshot();
            report.put("targetDocumentId",document);
            report.put("defaultKeyformLocked",m.defaultKeyformLocked());
            report.put("parameters",m.parameters().all().stream().map(p->Map.of("id",p.id().value(),"name",p.name().orElse(""),"value",p.getValue())).toList());
            report.put("meshes",m.drawables().all().stream().map(d->Map.of("id",d.id().value(),"name",d.name(),
                "locked",d.locked(),"lockedInHierarchy",d.lockedInHierarchy(),"visible",d.visible(),
                "targetDeformer",d.parentDeformerId().map(id->id.value()).orElse(""),
                "vertices",d.geometry().positions().size(),"forms",d.authoringSnapshot().forms().size())).toList());
            report.put("deformers",m.deformers().all().stream().map(d->Map.of("id",d.id().value(),"name",d.name(),
                "locked",d.locked(),"lockedInHierarchy",d.lockedInHierarchy())).toList());
            report.put("meshEditorCommandAvailable",context.editorCommands().available().contains(EditorCommand.START_OR_END_MESH_EDITOR));
            require(before.equals(state()) && history.equals(context.cubism().history().snapshot()),"read-only editability observation changed authoring");
            report.put("stateUnchanged",true);report.put("historyUnchanged",true);report.put("uiEditingAccepted",false);
            return null;
        });
    }

    private void runSurfaceReopen(Path state) throws Exception {
        report.put("stage","waiting-reopened-surface");
        target=awaitDocument(null,"surface-reopen");document=target.documentId();
        byte[] bytes;
        try(var stream=UserEntryProbe.class.getResourceAsStream("surface.glb")) {
            require(stream!=null,"frozen surface missing");bytes=stream.readNBytes(1024*1024+1);
        }
        require(bytes.length<=1024*1024 && hash(bytes).equals(seam?
            "c9605e030d4f174501d8f98f798dcad3a5a3851254b347b1b80e882fee8484dd":
            "8aa60f903cee58d123831d6b66315b0537bafffcf68a064bcb6a0b0132d1b997"),"exact reopen source");
        Files.write(state.resolve("authorized-surface.glb"),bytes,StandardOpenOption.CREATE_NEW);
        report.put("inputSha256",hash(bytes));report.put("targetDocumentId",document);
        if(surfaceAtlasReopen)edt(()->{
            require(current().textures().textureAtlases().size()==1,"one reopened packed surface atlas required");
            executed(context.editorCommands().execute(EditorCommand.SHOW_MODEL_BY_TEXTURE_ATLAS));return null;
        });
        var reopened=edt(()->{
            var model=current();
            require(model.drawables().all().size()==2 && model.warpDeformers().all().isEmpty()
                && (surfaceAtlasReopen?model.textures().textureAtlases().size()==1:model.textures().textureAtlases().isEmpty()),"complete reopened surface inventory");
            require(model.drawables().all().stream().allMatch(m->m.authoringSnapshot().forms().size()==81
                && m.authoringSnapshot().axes().size()==2 && m.authoringSnapshot().axes().stream().allMatch(a->a.keys().size()==9)),"reopened dense surface forms");
            return state();
        });
        report.put("reopened",reopened);
        if(surfaceAtlasReopen)report.put("reopenedAtlasLayout",edt(()->AppleOfficialExport.layout(context.cubism().textureAtlasLayouts())));
        SourceTextureCapture.run(this::current,this::state,()->context.cubism().history().snapshot(),
            count->context.cubism().history().undo(count),state,report,2);
        var after=edt(this::state);require(reopened.equals(after),"reopen capture changed durable surface state");
        report.put("afterReopenCapture",after);report.put("renderVerification","CAPTURED_REQUIRES_INDEPENDENT_ORACLE");
        report.put("saveReopen",surfaceAtlasReopen?"REOPENED_PACKED_SURFACE_SEED_CAPTURED_REQUIRES_PAIR_CHECK":"REOPENED_SURFACE_SEED_CAPTURED_REQUIRES_PAIR_CHECK");
    }

    private CubismModel current() {
        require(active && SwingUtilities.isEventDispatchThread(),"active EDT access required");
        Target observed=observe();require(target!=null && target.sameIdentity(observed),"target switched or unavailable");
        return observed.model();
    }
    record Target(String documentId,String snapshotModelId,String editorModelId,CubismModel model) {
        boolean sameIdentity(Target other) {return other!=null && documentId.equals(other.documentId)
            && snapshotModelId.equals(other.snapshotModelId) && editorModelId.equals(other.editorModelId);}
    }
    static Target observe(Supplier<DocumentSnapshot> documents,Supplier<CubismModel> models,String excluded) {
        var before=documents.get();
        if(before==null || !before.isModelDocument() || before.model().isEmpty() || before.documentId().equals(excluded))return null;
        var model=models.get();String id=model.id().value();var after=documents.get();String next=models.get().id().value();
        if(after==null || !after.isModelDocument() || after.model().isEmpty() || !before.documentId().equals(after.documentId())
                || !before.model().orElseThrow().modelId().equals(after.model().orElseThrow().modelId()) || !id.equals(next))return null;
        return new Target(before.documentId(),before.model().orElseThrow().modelId(),id,model);
    }
    private Target observe() {return observe(()->context.cubism().activeDocument().orElse(null),()->context.cubism().model().active(),null);}
    private Target awaitDocument(String excluded,String phase)throws Exception {
        var found=new java.util.concurrent.atomic.AtomicReference<Target>();
        await(()->edt(()->{
            var observed=observe(()->context.cubism().activeDocument().orElse(null),()->context.cubism().model().active(),excluded);
            report.put("documentWait-"+phase,observed==null ? "NOT_READY" : "READY");
            found.set(observed);return observed!=null;
        }),documentWaitSeconds(appleReopen,System.getProperty("turboism.validation.mi3d.mode")),"model document "+phase);
        return found.get();
    }
    static int documentWaitSeconds(boolean appleReopen,String mode) {
        return appleReopen || List.of("cat-vertex-reopen","cat-vertex-bank","cat-vertex-edit","cat-vertex-drag-layout","cat-vertex-drag",
            "cat-optimized-reopen","cat-optimized-drag","cat-optimized-short-drag","cat-adaptive-short-drag","cat-cost-drag","cat-native-serialization","cat-default-serialization").contains(mode == null ? "" : mode) ? 300 : 90;
    }
    private Map<String,Object> state() {
        var m=current();
        var result=new LinkedHashMap<String,Object>(Map.of("meshes",m.drawables().all().stream().map(d->{
            if(apple)context.logger().info("XYZ_STORED_READ mesh "+d.id().value());
            var snapshot=d.authoringSnapshot();
            var captured=apple?compactMesh(snapshot):NativeAssertions.snapshot(snapshot);
            if(apple)context.logger().info("XYZ_STORED_READ mesh complete "+d.id().value());
            return captured;
        }).toList(),
            "meshMetadata",m.drawables().all().stream().map(d->Map.of("id",d.id().value(),"culling",d.culling(),"visible",d.visible(),
                "parent",d.parentPartId().map(i->i.value()).orElse(""),"input",m.textures().textureInput(d.id()).map(i->Map.of(
                    "kind",i.kind().name(),"image",i.modelImageId().map(v->v.value()).orElse(""),
                    "atlas",i.textureAtlasId().map(v->v.value()).orElse(""))).orElse(Map.of()))).toList(),
            "parameters",m.parameters().all().stream().map(p->List.of(p.id().value(),p.getMinimumValue(),p.getDefaultValue(),p.getMaximumValue(),p.getValue())).toList(),
            "rawImages",m.textures().rawImages().stream().map(i->List.of(i.id().value(),i.name(),i.width(),i.height())).toList(),
            "imageGroups",m.textures().modelImageGroups().stream().map(g->Map.of("name",g.groupName(),"images",
                g.modelImages().stream().map(i->List.of(i.id().value(),i.name(),i.width(),i.height())).toList())).toList(),
            "parts",m.parts().all().stream().map(p->List.of(p.id().value(),p.name(),p.parentId().map(i->i.value()).orElse(""))).toList()));
        if(apple || rejection || surface)result.put("deformers",m.warpDeformers().all().stream().map(w->{
            if(apple)context.logger().info("XYZ_STORED_READ warp snapshot");
            var snapshot=w.authoringSnapshot();
            if(apple)context.logger().info("XYZ_STORED_READ warp hash");
            var captured=compactWarp(snapshot);
            if(apple)context.logger().info("XYZ_STORED_READ warp complete");
            return captured;
        }).toList());
        if(rejection || surface) {
            result.put("atlases",m.textures().textureAtlases().stream().map(a->List.of(a.id().value(),a.name(),a.width(),a.height(),a.modelImageCount())).toList());
            result.put("canvas",List.of(m.canvas().widthPixels(),m.canvas().heightPixels()));
        }
        return result;
    }
    static Map<String,Object> compactMesh(dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot s) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            try(var out=new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest),65536))) {
                out.writeUTF(s.artMeshId().value());out.writeUTF(s.sourceGuid());out.writeInt(s.sourceGeometry().positions().size());out.writeInt(s.sourceGeometry().triangleIndices().size());
                for(var point:s.sourceGeometry().positions()){out.writeFloat(point.x());out.writeFloat(point.y());}
                for(var point:s.sourceGeometry().uvs()){out.writeFloat(point.x());out.writeFloat(point.y());}
                for(int index:s.sourceGeometry().triangleIndices())out.writeInt(index);
                out.writeUTF(s.axes().toString());out.writeInt(s.cells().size());out.writeInt(s.forms().size());
                for(var cell:s.cells()){out.writeUTF(cell.formGuid());for(var c:cell.coordinates()){out.writeUTF(c.parameterGuid());out.writeFloat(c.value());}}
                for(var form:s.forms()){out.writeUTF(form.formGuid());out.writeInt(form.drawOrder());for(var point:form.positions()){out.writeFloat(point.x());out.writeFloat(point.y());}}
                out.writeUTF(s.morphKeys().toString());
            }
            var local=s.forms().get(0).positions();require(s.forms().stream().allMatch(f->f.positions().equals(local)),"layer local coordinates differ across forms");
            var byGuid=new HashMap<String,dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form>();s.forms().forEach(f->byGuid.put(f.formGuid(),f));
            var result=new LinkedHashMap<String,Object>();result.put("id",s.artMeshId().value());result.put("sourceGuid",s.sourceGuid());
            result.put("vertexCount",s.sourceGeometry().positions().size());result.put("triangleCount",s.sourceGeometry().triangleIndices().size()/3);
            result.put("axisCount",s.axes().size());result.put("formCount",s.forms().size());result.put("cellCount",s.cells().size());
            result.put("authoringSha256",HexFormat.of().formatHex(digest.digest()));
            result.put("geometrySha256",geometryHash(s));result.put("localSha256",pointsHash(local));
            result.put("axes",s.axes().stream().map(a->Map.of("id",a.parameterId().value(),"guid",a.parameterGuid(),"keys",a.keys())).toList());
            result.put("forms",s.cells().stream().map(c->Map.of("guid",c.formGuid(),"coordinates",c.coordinates().stream().map(v->List.of(v.parameterGuid(),v.value())).toList(),"drawOrder",byGuid.get(c.formGuid()).drawOrder())).toList());
            return result;
        } catch(java.io.IOException | java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static String geometryHash(dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot s)throws java.io.IOException,java.security.NoSuchAlgorithmException {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var out=new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest),65536))) {
            for(var p:s.sourceGeometry().positions()){out.writeFloat(p.x());out.writeFloat(p.y());}
            for(var p:s.sourceGeometry().uvs()){out.writeFloat(p.x());out.writeFloat(p.y());}
            for(int index:s.sourceGeometry().triangleIndices())out.writeInt(index);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String pointsHash(List<dev.turboism.sdk.cubism.model.Point2> points)throws java.io.IOException,java.security.NoSuchAlgorithmException {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var out=new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest),65536))) {
            for(var p:points){out.writeFloat(p.x());out.writeFloat(p.y());}
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static Map<String,Object> compactWarp(dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot s) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            try(var out=new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest),65536))) {
                out.writeUTF(s.deformerId().value());out.writeUTF(s.sourceGuid());out.writeInt(s.rows());out.writeInt(s.columns());out.writeBoolean(s.quadTransform());
                out.writeUTF(s.axes().toString());for(var c:s.cells()){out.writeUTF(c.formGuid());for(var v:c.coordinates()){out.writeUTF(v.parameterGuid());out.writeFloat(v.value());}}
                for(var f:s.forms()){out.writeUTF(f.formGuid());for(var p:f.controlPoints()){out.writeFloat(p.x());out.writeFloat(p.y());}}
            }
            return Map.of("id",s.deformerId().value(),"sourceGuid",s.sourceGuid(),"rows",s.rows(),"columns",s.columns(),"formCount",s.forms().size(),"axes",s.axes().stream().map(a->Map.of("id",a.parameterId().value(),"guid",a.parameterGuid(),"keys",a.keys())).toList(),"authoringSha256",HexFormat.of().formatHex(digest.digest()),"forms",s.cells().stream().map(c->Map.of("guid",c.formGuid(),"coordinates",c.coordinates().stream().map(v->List.of(v.parameterGuid(),v.value())).toList(),"xySha256",warpPointsHash(s,c.formGuid()))).toList());
        } catch(java.io.IOException | java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static String warpPointsHash(dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot s,String guid) {
        try {return pointsHash(s.forms().stream().filter(f->f.formGuid().equals(guid)).findFirst().orElseThrow().controlPoints());}
        catch(Exception failure){throw new IllegalStateException(failure);}
    }
    private <T extends Component>T awaitComponent(Class<T> type,Predicate<T> match,boolean showing)throws Exception {
        return awaitComponent(type,match,showing,60);
    }
    private <T extends Component>T awaitComponent(Class<T> type,Predicate<T> match,boolean showing,int seconds)throws Exception {
        var found=new java.util.concurrent.atomic.AtomicReference<T>();
        await(()->edt(()->{
            var matches=new ArrayList<T>();var seen=Collections.newSetFromMap(new IdentityHashMap<Component,Boolean>());
            for(var window:Window.getWindows())if(window.isShowing())walk(window,type,match,showing,matches,seen);
            require(matches.size()<=1,"ambiguous UI target "+type.getSimpleName());
            if(matches.size()==1)found.set(matches.get(0));return found.get()!=null;
        }),seconds,"UI target "+type.getSimpleName());return found.get();
    }
    static <T extends Component>void walk(Component c,Class<T> type,Predicate<T> match,boolean showing,List<T> out,Set<Component> seen) {
        if(!seen.add(c))return;
        if(type.isInstance(c) && (!showing || c.isShowing()) && match.test(type.cast(c)))out.add(type.cast(c));
        if(c instanceof JMenu menu)for(var child:menu.getMenuComponents())walk(child,type,match,showing,out,seen);
        if(c instanceof Container parent)for(var child:parent.getComponents())walk(child,type,match,showing,out,seen);
    }
    private static String title(Component c) {Window w=SwingUtilities.getWindowAncestor(c);return w instanceof Dialog d?d.getTitle():"";}
    private void await(Callable<Boolean> check,int seconds,String label)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(active && System.nanoTime()<deadline) {if(check.call())return;Thread.sleep(100);}
        throw new IllegalStateException("timeout/disabled: "+label);
    }
    private static <T>T edt(Callable<T> action)throws Exception {var task=new FutureTask<T>(action);SwingUtilities.invokeAndWait(task);return task.get();}
    private static void executed(EditorCommandResult result) {require(result.status()==EditorCommandResult.Status.EXECUTED,"command "+result.status());}
    private static void require(boolean ok,String message) {if(!ok)throw new IllegalStateException(message);}
    private static String hash(byte[] bytes)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
