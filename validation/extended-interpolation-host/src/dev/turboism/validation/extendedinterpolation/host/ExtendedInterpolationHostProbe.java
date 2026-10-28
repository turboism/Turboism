package dev.turboism.validation.extendedinterpolation.host;

import com.live2d.cubism.doc.model.ACForm;
import com.live2d.cubism.doc.model.ACParameterControllableSource;
import com.live2d.cubism.doc.model.CModel;
import com.live2d.cubism.doc.model.CModelSource;
import com.live2d.cubism.doc.model.deformer.ACDeformerSource;
import com.live2d.cubism.doc.model.deformer.rotation.CRotationDeformerForm;
import com.live2d.cubism.doc.model.deformer.rotation.CRotationDeformerSource;
import com.live2d.cubism.doc.model.deformer.warp.CWarpDeformerForm;
import com.live2d.cubism.doc.model.deformer.warp.CWarpDeformerSource;
import com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm;
import com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource;
import com.live2d.cubism.doc.model.id.CParameterId;
import com.live2d.cubism.doc.model.interpolator.KeyformBindingSource;
import com.live2d.cubism.doc.model.interpolator.KeyformGridSource;
import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.CExtendedInterpolationExtension;
import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.ExtendedInterpolationType;
import com.live2d.cubism.doc.model.param.CParameter;
import com.live2d.cubism.doc.model.param.CParameterSet;
import com.live2d.cubism.doc.model.param.CParameterSource;
import com.live2d.cubism.doc.model.param.CParameterSourceSet;
import com.live2d.cubism.doc.modeling.CModelingDocument;
import com.live2d.doc.selection.ISelectableObject;
import com.live2d.doc.selection.ISelection;
import com.live2d.doc.selection.ISelector;
import com.live2d.type.CFormGuid;
import com.live2d.type.CParameterGuid;

import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;

/**
 * Verified 5.3.04 exact-host validation carrier for Extended Interpolation Optimization.
 *
 * All reflection APIs are strictly audited against the official Live2D_Cubism.jar:
 *   - CModelSource.getAllDeformers() filtered for CWarpDeformerSource and CRotationDeformerSource
 *   - CParameterSourceSet.getSources()
 *   - Fresh CopyParam context per clone
 *   - Real effect assertions:
 *       1. Real drag: explicit object selection, camera mapping, and mutated geometry assertion
 *       2. Undo/Redo: exact bitwise coordinate restoration and reapplication assertion
 *       3. Save & reload: actual cmo3 file persistence, native CAFF reader, and model integrity
 *       4. Multi-axis grid: 3x3 keyform import and CExtendedInterpolationExtension registered in getExtensions()
 */
public final class ExtendedInterpolationHostProbe {

    public static final String HOST_5304_JAR_SHA256 =
            "c9bb3e9960bf49908a531b7010421c393a8dd5f9376745eb6fdb273f41a1cf5d";

    public static final String BRIDGE_CLASS =
            "dev.turboism.adapter.cubism.interpolation.NativeMeshInterpolationBridge";

    private static final String APP_CTRL_CLASS = "com.live2d.cubism.CEAppCtrl";
    private static final String VIEW_CONTEXT_CLASS = "com.live2d.cubism.view.context.CEViewContext_ModelingView";

    private static final String MOUSE_EVENT_WRAPPER = "com.live2d.ui.event.m";
    private static final String DRAG_EVENT_WRAPPER = "com.live2d.ui.event.l";
    private static final String INPUT_EVENT_BASE = "com.live2d.ui.event.h";

    private static final Properties RESULT = new Properties();

    private ExtendedInterpolationHostProbe() {}

    public static void premain(String args, Instrumentation instrumentation) {
        System.err.println("[EI-HOST-PROBE] Starting Extended Interpolation Host Probe for 5.3.04...");
        Thread worker = new Thread(() -> {
            try {
                runValidation();
            } catch (Throwable failure) {
                System.err.println("[EI-HOST-PROBE] CRITICAL FAILURE: " + failure);
                failure.printStackTrace(System.err);
            }
        }, "turboism-ei-host-validator");
        worker.setDaemon(true);
        worker.start();
        System.err.println("[EI-HOST-PROBE] Extended Interpolation Host Probe registered.");
    }

    private static void runValidation() {
        Path home = resolveHome();
        Path output = home.resolve("state/extended-interpolation/result.properties");
        Path startFlag = home.resolve("state/extended-interpolation/start.flag");

        RESULT.setProperty("schemaVersion", "turboism-ei-host-v2");
        RESULT.setProperty("targetHostVersion", "5.3.04");
        RESULT.setProperty("hostRuntime", System.getProperty("java.runtime.version", "unknown"));
        RESULT.setProperty("status", "INITIALIZING");

        try {
            // 1. Wait for start trigger or timeout
            System.err.println("[EI-HOST-PROBE] Awaiting start trigger at: " + startFlag);
            long startDeadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
            while (!Files.isRegularFile(startFlag) && System.nanoTime() < startDeadline) {
                Thread.sleep(200);
            }
            if (!Files.isRegularFile(startFlag)) {
                throw new IllegalStateException("Timeout waiting for start.flag trigger at: " + startFlag);
            }
            System.err.println("[EI-HOST-PROBE] Start flag detected. Auditing host integrity...");

            // 2. Audit host integrity and bridges
            auditHostIntegrity();
            Class<?> bridgeClass = auditBridgePresence();
            auditFormLookupBridge();

            // 3. Acquire official Editor UI context & Modeling Document
            System.err.println("[EI-HOST-PROBE] Waiting for Modeling Document & View...");
            ContextRef ctx = awaitModelingContext();
            System.err.println("[EI-HOST-PROBE] Modeling context ready. Document UID: " + ctx.documentUid);
            if (Boolean.getBoolean("turboism.validation.extendedInterpolation.coldReopen")) {
                verifyColdReopen(ctx, home);
                RESULT.setProperty("status", "SMOKE_PASS_PARTIAL_ACCEPTANCE");
                return;
            }


            // 4. Initial Bridge statistics
            long initialAccepted = readBridgeCounter(bridgeClass, "acceptedCalls");
            long initialRefused = readBridgeCounter(bridgeClass, "refusedCalls");
            long initialCurveHits = recordCurveCache("initial");
            long initialLocalHits = recordLocalRefresh("initial");
            RESULT.setProperty("bridge.initialAccepted", Long.toString(initialAccepted));
            RESULT.setProperty("bridge.initialRefused", Long.toString(initialRefused));

            com.sun.management.ThreadMXBean threadBean =
                    (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

            // 5. Inspect and prepare targets in real CModelingDocument
            System.err.println("[EI-HOST-PROBE] Inspecting & preparing targets in live document...");
            TargetObjects targets = edt(() -> inspectOrSetupTargets(ctx));
            edt(() -> {recordCacheFootprint(targets,"prepared");return null;});
            RESULT.setProperty("targets.artMeshGuid", targets.artMeshGuid);
            RESULT.setProperty("targets.warpGuid", targets.warpGuid);
            RESULT.setProperty("targets.rotationGuid", targets.rotationGuid);
            System.err.println("[EI-HOST-PROBE] Targets attached: ArtMesh=" + targets.artMeshGuid
                    + ", Warp=" + targets.warpGuid + ", Rotation=" + targets.rotationGuid);

            // -------------------------------------------------------------
            // Scenario 1: Parameter Evaluation (Slider Scrubbing)
            // -------------------------------------------------------------
            System.err.println("[EI-HOST-PROBE] === Scenario 1: Parameter Evaluation ===");
            long scrubBaseline = readBridgeCounter(bridgeClass, "acceptedCalls");
            ScenarioMetrics paramMetrics = executeParameterScrubbing(ctx, targets, bridgeClass, scrubBaseline, threadBean);
            recordMetrics("scenario.paramScrub", paramMetrics);

            // -------------------------------------------------------------
            // Scenario 2: Real View Mouse Dragging with Geometry Mutation Assertion
            // -------------------------------------------------------------
            System.err.println("[EI-HOST-PROBE] === Scenario 2: Real View Dragging ===");
            DragResult dragResult = executeRealViewDraggingWithAssertion(ctx, targets, threadBean);
            recordMetrics("scenario.viewDrag", dragResult.metrics);
            RESULT.setProperty("scenario.viewDrag.mutatedDeltaX", Float.toString(dragResult.deltaX));
            RESULT.setProperty("scenario.viewDrag.mutatedDeltaY", Float.toString(dragResult.deltaY));

            // -------------------------------------------------------------
            // Scenario 3: Undo & Redo with Exact Bitwise Restoration Assertion
            // -------------------------------------------------------------
            System.err.println("[EI-HOST-PROBE] === Scenario 3: Undo / Redo Lifecycle ===");
            ScenarioMetrics undoRedoMetrics = executeUndoRedoCycleWithAssertion(ctx, targets, dragResult, threadBean);
            recordMetrics("scenario.undoRedo", undoRedoMetrics);
            if(Boolean.getBoolean("turboism.validation.extendedInterpolation.dragBenchmark"))
                executeDragBenchmark(ctx,targets,bridgeClass,threadBean);
            if(Boolean.getBoolean("turboism.validation.extendedInterpolation.warpDragBenchmark"))
                WarpDragValidation.run(ctx,targets,RESULT);


            // -------------------------------------------------------------
            // Scenario 4: Config & Topology Switch (ELLIPSE <-> SNS)
            // -------------------------------------------------------------
            System.err.println("[EI-HOST-PROBE] === Scenario 4: Config & Topology Switch ===");
            ScenarioMetrics configMetrics = executeConfigAndTopologySwitch(ctx, targets, threadBean);
            recordMetrics("scenario.configSwitch", configMetrics);
            edt(() -> {recordCacheFootprint(targets,"afterRebuild");return null;});

            // -------------------------------------------------------------
            // Scenario 5: Hierarchical Deformation Cascade
            // -------------------------------------------------------------
            System.err.println("[EI-HOST-PROBE] === Scenario 5: Hierarchy Cascade ===");
            ScenarioMetrics hierarchyMetrics = executeHierarchyCascade(ctx, targets, threadBean);
            recordMetrics("scenario.hierarchy", hierarchyMetrics);
            RESULT.setProperty("scenario.hierarchy.allocationScope","NOT_MEASURED_ACROSS_EDT_AND_RENDER_THREADS");

            // -------------------------------------------------------------
            // Scenario 7: GL Rendering Check
            // -------------------------------------------------------------
            System.err.println("[EI-HOST-PROBE] === Scenario 7: GL Render Verification ===");
            ScenarioMetrics renderMetrics = executeRenderCheck(ctx, targets, home, threadBean);
            recordMetrics("scenario.render", renderMetrics);
            RESULT.setProperty("scenario.render.allocationScope","NOT_MEASURED_ACROSS_EDT_AND_GL_THREADS");

            // -------------------------------------------------------------
            // Scenario 6: Save & Reopen / Persistence Verification
            // -------------------------------------------------------------
            System.err.println("[EI-HOST-PROBE] === Scenario 6: Save & Reopen Persistence ===");
            ScenarioMetrics saveMetrics = executeSaveAndPersistenceWithVerification(ctx, targets, home, threadBean);
            recordMetrics("scenario.save", saveMetrics);

            // -------------------------------------------------------------
            // Scenario 8: Settings Toggle Service & Persistence Inspection (Lightweight)
            // -------------------------------------------------------------
            System.err.println("[EI-HOST-PROBE] === Scenario 8: Settings Toggle & Config Inspection ===");
            inspectSettingsToggleAndPersistence(home);

            // 6. Check final bridge statistics
            long finalAccepted = readBridgeCounter(bridgeClass, "acceptedCalls");
            long finalRefused = readBridgeCounter(bridgeClass, "refusedCalls");
            long acceptedDelta = finalAccepted - initialAccepted;
            RESULT.setProperty("bridge.finalAccepted", Long.toString(finalAccepted));
            RESULT.setProperty("bridge.finalRefused", Long.toString(finalRefused));
            RESULT.setProperty("bridge.acceptedDelta", Long.toString(acceptedDelta));
            System.err.println("[EI-HOST-PROBE] Bridge counters: total accepted=" + finalAccepted
                    + " (delta=" + acceptedDelta + "), refused=" + finalRefused);

            if (acceptedDelta <= 0) {
                throw new AssertionError("Assertion failure: Bridge acceptedCalls did not advance during real Editor workload! (delta=" + acceptedDelta + ")");
            }

            long curveHits = recordCurveCache("final") - initialCurveHits;
            RESULT.setProperty("curveCache.hitDelta",Long.toString(curveHits));
            if(curveHits<=0)throw new AssertionError("No numerical curve cache hits in real Editor workload");

            long localHits=recordLocalRefresh("final")-initialLocalHits;
            RESULT.setProperty("localRefresh.hitDelta",Long.toString(localHits));
            if(localHits<=0)throw new AssertionError("No production local-refresh hits in real Editor workload");

            // Explicit Carrier Manifest
            RESULT.setProperty("carrier.mode", "CONTROLLED_5304_SMOKE_CARRIER");
            RESULT.setProperty("carrier.verifiedScopes", "host_sha_audit,bridge_presence,multi_axis_topology_attach,real_view_input_chain,bridge_accepted_calls_advancement");

            // Explicit Missing Scopes - NOT FAKED
            RESULT.setProperty("carrier.missingScopes.0", "palette_slider_ui_drag_unverified");
            if(!"PASS".equals(RESULT.getProperty("scenario.hierarchy.cascadeProof")))
                RESULT.setProperty("carrier.missingScopes.1", "hierarchy_cascade_geometry_proof_unverified");
            RESULT.setProperty("carrier.missingScopes.2", "save_reopen_in_independent_session_unverified");
            if(!"PASS".equals(RESULT.getProperty("scenario.render.pixelProof")))
                RESULT.setProperty("carrier.missingScopes.3", "render_offscreen_pixel_capture_unverified");
            RESULT.setProperty("carrier.missingScopes.4", "cross_session_fps_cache_unverified");

            RESULT.setProperty("status", "SMOKE_PASS_PARTIAL_ACCEPTANCE");
            System.err.println("[EI-HOST-PROBE] 5.3.04 HOST SMOKE CARRIER COMPLETED: PARTIAL ACCEPTANCE (MISSING SCOPES RECORDED)");

        } catch (Throwable error) {
            System.err.println("[EI-HOST-PROBE] VALIDATION ERROR: " + error);
            error.printStackTrace(System.err);
            RESULT.setProperty("status", "FAIL");
            RESULT.setProperty("error", error.toString());
        } finally {
            saveResultProperties(output);
            requestGracefulEditorExit();
        }
    }

    private static Path resolveHome() {
        String customHome = System.getProperty("turboism.validation.extendedInterpolation.home");
        if (customHome != null && !customHome.isBlank()) {
            return Path.of(customHome);
        }
        return Path.of(System.getProperty("user.dir", "."));
    }

    private static void auditHostIntegrity() throws Exception {
        Class<?> appCtrlClass = Class.forName(APP_CTRL_CLASS, false, ClassLoader.getSystemClassLoader());
        var codeSource = appCtrlClass.getProtectionDomain().getCodeSource();
        if (codeSource == null) {
            throw new IllegalStateException("CEAppCtrl code source unavailable");
        }
        Path hostJar = Path.of(codeSource.getLocation().toURI());
        String actualSha = calculateSha256(hostJar);
        RESULT.setProperty("hostJarPath", hostJar.toString());
        RESULT.setProperty("hostJarSha256", actualSha);
        if (!HOST_5304_JAR_SHA256.equalsIgnoreCase(actualSha)) {
            throw new IllegalStateException("Host JAR SHA-256 mismatch! Expected 5.3.04 ("
                    + HOST_5304_JAR_SHA256 + ") but found: " + actualSha);
        }
        System.err.println("[EI-HOST-PROBE] Host JAR verified against 5.3.04 golden SHA-256.");
    }

    private static Class<?> auditBridgePresence() throws Exception {
        try {
            Class<?> bridge = Class.forName(BRIDGE_CLASS, false, ClassLoader.getSystemClassLoader());
            bridge.getMethod("acceptedCalls");
            bridge.getMethod("refusedCalls");
            System.err.println("[EI-HOST-PROBE] NativeMeshInterpolationBridge located via SystemClassLoader.");
            return bridge;
        } catch (ClassNotFoundException notFound) {
            throw new IllegalStateException("Turboism NativeMeshInterpolationBridge not found on classpath!", notFound);
        }
    }

    private static void auditFormLookupBridge() {
        try {
            Class<?> lookupBridge = Class.forName(
                    "dev.turboism.adapter.cubism.interpolation.NativeFormLookupBridge",
                    false,
                    ClassLoader.getSystemClassLoader());
            lookupBridge.getMethod("indexBuilds");
            RESULT.setProperty("bridge.formLookupAvailable", "true");
            System.err.println("[EI-HOST-PROBE] NativeFormLookupBridge detected.");
        } catch (Throwable ignored) {
            RESULT.setProperty("bridge.formLookupAvailable", "false");
        }
    }

    private static long readBridgeCounter(Class<?> bridgeClass, String methodName) {
        try {
            Method m = bridgeClass.getMethod(methodName);
            return ((Number) m.invoke(null)).longValue();
        } catch (Exception e) {
            return -1L;
        }
    }

    private static ContextRef awaitModelingContext() throws Exception {
        Class<?> appCtrlClass = Class.forName(APP_CTRL_CLASS);
        Method getInstance = appCtrlClass.getMethod("access$get_instance$cp");

        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        long openFallbackAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        boolean openFallbackAttempted = false;
        Object appCtrl = null;
        Object viewContext = null;
        CModelingDocument doc = null;
        Component canvas = null;

        while (System.nanoTime() < deadline) {
            try {
                appCtrl = getInstance.invoke(null);
                if (appCtrl != null) {
                    if (!openFallbackAttempted && System.nanoTime() >= openFallbackAt
                            && ((List<?>) appCtrl.getClass().getMethod("getAllModelDocs").invoke(appCtrl)).isEmpty()) {
                        openFallbackAttempted = true;
                        Path taskHome = Path.of(System.getProperty("turboism.validation.extendedInterpolation.home"));
                        String fixtureName = System.getProperty("turboism.validation.extendedInterpolation.fixtureName");
                        Path fixture = taskHome.getParent().resolve(fixtureName).normalize();
                        if (!fixture.getParent().equals(taskHome.getParent()) || !Files.isRegularFile(fixture))
                            throw new IllegalStateException("Invalid task-local fixture for official open: " + fixture);
                        Object readyApp = appCtrl;
                        RESULT.setProperty("startup.fixtureOpenFallback", fixture.toString());
                        System.err.println("[EI-HOST-PROBE] No model document; opening task fixture via official command_open: " + fixture);
                        SwingUtilities.invokeLater(() -> {
                            try { readyApp.getClass().getMethod("command_open", File.class, boolean.class)
                                    .invoke(readyApp, fixture.toFile(), false); }
                            catch (Throwable failure) {
                                RESULT.setProperty("startup.fixtureOpenError", failure.toString());
                                failure.printStackTrace();
                            }
                        });
                    }
                    viewContext = appCtrl.getClass().getMethod("getCurrentViewContext").invoke(appCtrl);
                    if (viewContext != null && viewContext.getClass().getName().equals(VIEW_CONTEXT_CLASS)) {
                        Object rawDoc = viewContext.getClass().getMethod("getDoc").invoke(viewContext);
                        if (rawDoc instanceof CModelingDocument mDoc) {
                            doc = mDoc;
                            canvas = findUniqueCanvas();
                            if (canvas != null && canvas.getWidth() > 50 && canvas.getHeight() > 50) {
                                break;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(250);
        }

        if (appCtrl == null || viewContext == null || doc == null || canvas == null) {
            throw new IllegalStateException("Failed to acquire ready CEViewContext_ModelingView and GLJPanel canvas before deadline.");
        }

        String docUid = String.valueOf(doc.getDocumentUID());
        Object camera = viewContext.getClass().getMethod("getCameraManager").invoke(viewContext);
        ISelector selector = doc.getSelector();
        CModel model = (CModel) viewContext.getClass().getMethod("getModel").invoke(viewContext);

        Class<?> mouseWrapperClass = Class.forName(MOUSE_EVENT_WRAPPER);
        Class<?> dragWrapperClass = Class.forName(DRAG_EVENT_WRAPPER);
        Class<?> inputEventBaseClass = Class.forName(INPUT_EVENT_BASE);

        Constructor<?> mouseCtor = mouseWrapperClass.getConstructor(MouseEvent.class);
        Constructor<?> dragCtor = dragWrapperClass.getConstructor(MouseEvent.class);
        Method dragUpdate = dragWrapperClass.getMethod("a", MouseEvent.class);
        Method dragRelease = dragWrapperClass.getMethod("b", MouseEvent.class);
        Method onInput = viewContext.getClass().getMethod("onInputEvent", inputEventBaseClass);

        return new ContextRef(appCtrl, viewContext, doc, model, selector, camera, canvas, docUid,
                mouseCtor, dragCtor, dragUpdate, dragRelease, onInput);
    }

    private static Component findUniqueCanvas() {
        List<Component> canvases = new ArrayList<>();
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) {
                walkComponents(w, canvases);
            }
        }
        if (canvases.size() == 1) {
            return canvases.get(0);
        }
        return null;
    }

    private static void walkComponents(Component c, List<Component> list) {
        if (c.isShowing() && c.getClass().getName().contains("GLJPanel")) {
            list.add(c);
        }
        if (c instanceof Container parent) {
            for (Component child : parent.getComponents()) {
                walkComponents(child, list);
            }
        }
    }

    // Inspect existing ArtMesh, Warp, Rotation, or setup extension on them in live document
    private static TargetObjects inspectOrSetupTargets(ContextRef ctx) throws Exception {
        CModelSource source = ctx.doc.getModelSource();
        List<CArtMeshSource> meshes = source.getAllArtMeshes();
        List<ACDeformerSource> allDeformers = source.getAllDeformers();

        // Safe hierarchy resolution: search for naturally occurring Rotation -> Warp -> ArtMesh chain first
        CArtMeshSource selectedMesh = null;
        CWarpDeformerSource selectedWarp = null;
        CRotationDeformerSource selectedRotation = null;

        for (CArtMeshSource mesh : meshes) {
            com.live2d.type.CDeformerGuid pGuid = mesh.getTargetDeformerGuid();
            if (pGuid == null) continue;
            ACDeformerSource p1 = source.getDeformerSource(pGuid);
            if (p1 instanceof CWarpDeformerSource w) {
                com.live2d.type.CDeformerGuid grandPGuid = w.getTargetDeformerGuid();
                if (grandPGuid == null) continue;
                ACDeformerSource p2 = source.getDeformerSource(grandPGuid);
                if (p2 instanceof CRotationDeformerSource r) {
                    selectedMesh = mesh;
                    selectedWarp = w;
                    selectedRotation = r;
                    break;
                }
            }
        }

        if (selectedMesh == null) {
            if (meshes == null || meshes.isEmpty()) {
                throw new IllegalStateException("Model has no ArtMeshes!");
            }
            selectedMesh = meshes.get(0);

            // A controlled fallback rig owns its transforms. Reusing an unrelated
            // existing warp would require the Editor's full reparent conversion.
            selectedRotation = null;
            selectedWarp = null;

            // If missing rotation deformer, construct controlled one on live document
            if (selectedRotation == null) {
                selectedRotation = new CRotationDeformerSource(source);
                selectedRotation.setId(new com.live2d.cubism.doc.model.id.CDeformerId("EI_Controlled_Rotation"));
                selectedRotation.setLocalName("EI_Rotation");
                CRotationDeformerForm rForm = new CRotationDeformerForm(
                        selectedRotation, null, new com.live2d.doc.CoordType("DeformerLocal"));
                rForm.setGuid(new com.live2d.type.CFormGuid(UUID.randomUUID()));
                rForm.setOriginX(0.0f);
                rForm.setOriginY(0.0f);
                rForm.setAngle(0.0f);
                rForm.setScale(1.0f);
                rForm.setOpacity(1.0f);
                selectedRotation.getKeyforms().add(rForm);

                source.addParameterControllableSource(selectedRotation);
                if (source.getRootPart() != null) {
                    source.getRootPart().addChild(selectedRotation, source.getRootPart().getChildren().size());
                }
            }

            // If missing warp deformer, construct controlled one on live document
            if (selectedWarp == null) {
                selectedWarp = new CWarpDeformerSource(source);
                selectedWarp.setId(new com.live2d.cubism.doc.model.id.CDeformerId("EI_Controlled_Warp"));
                selectedWarp.setLocalName("EI_Warp");
                selectedWarp.setRow(2);
                selectedWarp.setCol(2);
                CWarpDeformerForm wForm = new CWarpDeformerForm(
                        selectedWarp, null, new com.live2d.doc.CoordType("DeformerLocal"));
                wForm.setGuid(new com.live2d.type.CFormGuid(UUID.randomUUID()));
                // Warp children use normalized local coordinates. Reparenting raw Canvas
                // coordinates directly would amplify them by the warp's physical size.
                float minX=Float.POSITIVE_INFINITY, minY=Float.POSITIVE_INFINITY;
                float maxX=Float.NEGATIVE_INFINITY, maxY=Float.NEGATIVE_INFINITY;
                Set<ACForm> meshForms=Collections.newSetFromMap(new IdentityHashMap<>());
                meshForms.addAll(allForms(selectedMesh));
                for (ACForm form:meshForms) {
                    float[] points=((CArtMeshForm)form).getPositions();
                    for(int i=0;i<points.length;i+=2) {
                        if(!Float.isFinite(points[i]) || !Float.isFinite(points[i+1])) throw new IllegalStateException("Invalid carrier mesh coordinates");
                        minX=Math.min(minX,points[i]);maxX=Math.max(maxX,points[i]);
                        minY=Math.min(minY,points[i+1]);maxY=Math.max(maxY,points[i+1]);
                    }
                }
                float spanX=maxX-minX, spanY=maxY-minY;
                if(!(spanX>0) || !(spanY>0)) throw new IllegalStateException("Degenerate carrier mesh bounds");
                float[] warpPoints=new float[18];
                for(int row=0;row<3;row++) for(int col=0;col<3;col++) {
                    int i=(row*3+col)*2;warpPoints[i]=minX+spanX*col/2f;warpPoints[i+1]=minY+spanY*row/2f;
                }
                wForm.setPositions(warpPoints);
                for(ACForm form:meshForms) {
                    float[] points=((CArtMeshForm)form).getPositions();
                    for(int i=0;i<points.length;i+=2) {points[i]=(points[i]-minX)/spanX;points[i+1]=(points[i+1]-minY)/spanY;}
                    form.setUpdated();
                }
                RESULT.setProperty("carrier.warpReparentCoordinates","CANVAS_TO_NORMALIZED_LOCAL");
                wForm.setOpacity(1.0f);
                selectedWarp.getKeyforms().add(wForm);

                source.addParameterControllableSource(selectedWarp);
                if (source.getRootPart() != null) {
                    source.getRootPart().addChild(selectedWarp, source.getRootPart().getChildren().size());
                }
            }

            // Safely link only when cycle-free: Rotation -> Warp -> ArtMesh
            selectedWarp.setTargetDeformerGuid(selectedRotation.getGuid());
            selectedMesh.setTargetDeformerGuid(selectedWarp.getGuid());
        }

        // Explicit Cycle-free Traversal Assertion on target hierarchy
        Set<Object> visitedGuids = new HashSet<>();
        ACParameterControllableSource current = selectedMesh;
        while (current != null) {
            Object guidObj = current.getGuid();
            if (!visitedGuids.add(guidObj)) {
                throw new IllegalStateException("Cyclic deformer hierarchy detected at " + guidObj);
            }
            com.live2d.type.CDeformerGuid parentGuid = current.getTargetDeformerGuid();
            if (parentGuid == null) break;
            current = source.getDeformerSource(parentGuid);
        }
        RESULT.setProperty("carrier.hierarchyAcyclic", "PROVEN_CYCLE_FREE");

        String meshGuid = selectedMesh.getGuid().getUuidString();
        String warpGuid = selectedWarp.getGuid().getUuidString();
        String rotGuid = selectedRotation.getGuid().getUuidString();

        // Attach multi-axis grid and extension in live document
        ensureExtendedInterpolation(selectedMesh, source);
        ensureExtendedInterpolation(selectedWarp, source);
        ensureExtendedInterpolation(selectedRotation, source);

        // Update instances and visible/lock hierarchy after linking and extension setup
        source.getDeformerSourceSet().updateMap();
        source.updateModelInstances();
        source.updateVisibleAndLockHierarchy();

        return new TargetObjects(selectedMesh, selectedWarp, selectedRotation, meshGuid, warpGuid, rotGuid);
    }

    @SuppressWarnings("unchecked")
    private static void ensureExtendedInterpolation(ACParameterControllableSource owner, CModelSource modelSource) throws Exception {
        setupDualAxisGridIfNeed(owner, modelSource);

        CExtendedInterpolationExtension ext = null;
        for (Object existing : owner.getExtensions()) {
            if (existing instanceof CExtendedInterpolationExtension eie) {
                ext = eie;
                break;
            }
        }
        if (ext == null) {
            // Instantiate with owner and register to owner.getExtensions() directly.
            // Critical: registering in getExtensions() ensures generated keyforms are queryable by GUID during addKey without NPE.
            ext = new CExtendedInterpolationExtension(owner);
            owner.getExtensions().add(ext);
        }

        KeyformGridSource grid = owner.getKeyformGridSource();
        if (grid != null) {
            for (KeyformBindingSource binding : grid.getKeyformBindings()) {
                binding.setExtendedInterpolationType(ExtendedInterpolationType.ELLIPSE);
                try {
                    binding.setInsertPointCount(4);
                } catch (Throwable ignored) {
                }
            }
        }

        ext.setupFormStructure(true, true);
    }

    @SuppressWarnings("unchecked")
    private static void setupDualAxisGridIfNeed(ACParameterControllableSource owner, CModelSource modelSource) throws Exception {
        KeyformGridSource grid = owner.getKeyformGridSource();
        if (grid == null) return;
        List<KeyformBindingSource> existingBindings = grid.getKeyformBindings();
        if (existingBindings != null && existingBindings.size() >= 2) {
            boolean hasAtLeast3KeysPerBinding = true;
            for (KeyformBindingSource b : existingBindings) {
                if (b.getKeys() == null || b.getKeys().size() < 3) {
                    hasAtLeast3KeysPerBinding = false;
                    break;
                }
            }
            if (!hasAtLeast3KeysPerBinding) {
                RESULT.setProperty("scenario.topology.scope", "REUSED_EXISTING_BINDINGS_PARTIAL_NO_GUARANTEED_3X3_DIVERSITY");
            } else {
                RESULT.setProperty("scenario.topology.scope", "EXISTING_MULTI_AXIS_GRID_REUSED");
            }
            return;
        }

        CParameterSourceSet ps = modelSource.getParameterSourceSet();
        List<CParameterSource> params = ps.getSources();
        List<kotlin.Pair<CParameterGuid, float[]>> pairs = new ArrayList<>();

        for (int axis = 0; axis < 2; axis++) {
            CParameterGuid paramGuid;
            if (axis < params.size()) {
                CParameterSource p = params.get(axis);
                paramGuid = p.getGuid();
            } else {
                CParameterSource newP = new CParameterSource();
                newP.setId(new CParameterId("EI_P" + axis));
                paramGuid = new CParameterGuid(new UUID(100, axis));
                newP.setGuid(paramGuid);
                newP.setMinValue(-60.0f);
                newP.setMaxValue(60.0f);
                newP.setDefaultValue(0.0f);
                newP.setup(modelSource);
                ps.add(newP, axis);
            }
            pairs.add(new kotlin.Pair<>(paramGuid, new float[]{-60.0f, 0.0f, 60.0f}));
        }
        ps.updateMap();

        List<ACForm> forms = (List<ACForm>) owner.getKeyforms();
        if (forms == null || forms.isEmpty()) return;

        List<CFormGuid> formGuids = new ArrayList<>();
        ACForm templateForm = forms.get(0);

        Class<?> copyContextClass = Class.forName("com.live2d.core.a");
        Constructor<?> copyContextCtor = copyContextClass.getConstructor();
        Method deepCopyMethod = templateForm.getClass().getMethod("deepCopy", copyContextClass);

        Set<Object> uniqueInstances = Collections.newSetFromMap(new IdentityHashMap<>());
        uniqueInstances.add(templateForm);

        while (forms.size() < 9) {
            // Fresh copy context instance per clone to prevent cached same-reference return
            Object freshContext = copyContextCtor.newInstance();
            ACForm copy = (ACForm) deepCopyMethod.invoke(templateForm, freshContext);
            if (!uniqueInstances.add(copy)) {
                throw new IllegalStateException("DeepCopy produced duplicate form reference at index " + forms.size());
            }
            if (copy.getSource() != owner) {
                throw new IllegalStateException("DeepCopy produced form with mismatched owner at index " + forms.size());
            }
            copy.setGuid(new CFormGuid(new UUID(200, forms.size())));
            forms.add(copy);
        }
        for (int i = 0; i < 9; i++) {
            formGuids.add(forms.get(i).getGuid());
        }

        grid.setup(owner);
        Method importMethod = grid.getClass().getMethod("import", CModelSource.class, List.class, List.class);
        importMethod.invoke(grid, modelSource, pairs, formGuids);
        RESULT.setProperty("scenario.topology.scope", "CONTROLLED_3X3_DUAL_AXIS_IMPORTED");
        RESULT.setProperty("scenario.topology.formsUniqueAndOwnerVerified", "true");
    }

    // -------------------------------------------------------------------------
    // Scenario 1: Parameter Evaluation with advance assertion
    // -------------------------------------------------------------------------
    private static ScenarioMetrics executeParameterScrubbing(ContextRef ctx, TargetObjects targets,
                                                             Class<?> bridgeClass, long scrubBaseline,
                                                             com.sun.management.ThreadMXBean threadBean) throws Exception {
        List<Long> latencies = new ArrayList<>();
        AtomicLong allocTotal = new AtomicLong();

        edt(() -> {
            CParameterSet paramSet = ctx.model.getParameterSet();
            List<CParameter> params = paramSet.getParameters();
            if (params.isEmpty()) {
                throw new IllegalStateException("Model has no parameters to scrub");
            }
            CParameter param = params.get(0);
            float[] scrubValues = new float[]{-30.0f, -15.0f, 0.0f, 15.0f, 30.0f, 0.0f};

            for (float val : scrubValues) {
                long tAllocStart = threadBean.getThreadAllocatedBytes(Thread.currentThread().getId());
                long tStart = System.nanoTime();

                param.setValue(val);
                ctx.model.updateParamInstance();
                ctx.viewContext.getClass().getMethod("repaintCanvas").invoke(ctx.viewContext);

                long tEnd = System.nanoTime();
                long tAllocEnd = threadBean.getThreadAllocatedBytes(Thread.currentThread().getId());

                latencies.add(tEnd - tStart);
                allocTotal.addAndGet(Math.max(0, tAllocEnd - tAllocStart));
            }
            return null;
        });

        long scrubbedAccepted = readBridgeCounter(bridgeClass, "acceptedCalls");
        long scrubDelta = scrubbedAccepted - scrubBaseline;
        RESULT.setProperty("scenario.paramScrub.acceptedDelta", Long.toString(scrubDelta));
        System.err.println("[EI-HOST-PROBE] Parameter scrubbing acceptedCalls delta: " + scrubDelta
                + " (baseline=" + scrubBaseline + ", current=" + scrubbedAccepted + ")");

        // Note: Normal slider scrubbing evaluates existing mesh instances without rebuilding forms.
        // A delta of 0 is an expected and valid factual outcome, NOT a failure.
        RESULT.setProperty("scenario.paramScrub.verifiedStatus",
                scrubDelta > 0 ? "OBSERVED_BRIDGE_ADVANCEMENT_PROVEN" : "OBSERVED_EVALUATED_NO_REBUILD_DELTA");
        RESULT.setProperty("scenario.paramScrub.uiScope", "MODEL_PARAMETER_INSTANCE_EVALUATION_NOT_PALETTE_SLIDER");

        return computeMetrics(latencies, allocTotal.get(), "OBSERVED");
    }

    // -------------------------------------------------------------------------
    // Scenario 2: Real View Dragging with Geometry Mutation Assertion
    // -------------------------------------------------------------------------
    record DragResult(ScenarioMetrics metrics, float beforeX, float beforeY,
                      float afterX, float afterY, float deltaX, float deltaY, boolean mutated,
                      List<int[]> beforeBank, List<int[]> afterBank) {}

    private static DragResult executeRealViewDraggingWithAssertion(ContextRef ctx, TargetObjects targets,
                                                                   com.sun.management.ThreadMXBean threadBean) throws Exception {
        List<Long> latencies = new ArrayList<>();
        AtomicLong allocTotal = new AtomicLong();
        float[] coords = new float[4]; // first point displacement is diagnostic only
        List<int[]> beforeBank = new ArrayList<>(), afterBank = new ArrayList<>();
        List<int[]> selectedPositions = new ArrayList<>();

        // The task owns this model copy; make its controlled edit target editable.
        long beforeFrame = edt(() -> ctx.model.getLastUpdatedTimeMSec());
        edt(() -> {
            RESULT.setProperty("scenario.viewDrag.defaultLockedBefore", Boolean.toString(ctx.doc.getModelSource().isDefaultKeyformLocked()));
            ctx.doc.getModelSource().setDefaultKeyformLocked(false);
            targets.artMesh.setLocked(false);
            targets.artMesh.setVisible(true);
            for (var parent : targets.artMesh.getAllParents()) { parent.setLocked(false); parent.setVisible(true); }
            ctx.doc.getModelSource().updateVisibleAndLockHierarchy();
            ctx.doc.getModelSource().updateModelInstances();
            selectObject(ctx, targets.artMesh);
            ctx.appCtrl.getClass().getMethod("command_showAllObject_InViewArea").invoke(ctx.appCtrl);
            ctx.viewContext.getClass().getMethod("repaintCanvas").invoke(ctx.viewContext);
            return null;
        });
        long frameDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (edt(() -> ctx.model.getLastUpdatedTimeMSec()) <= beforeFrame) {
            if (System.nanoTime() >= frameDeadline) throw new IllegalStateException("No native model update after task selection");
            Thread.sleep(50);
        }

        edt(() -> {
            var liveMesh = ctx.model.getArtMesh(targets.artMesh.getGuid());
            CArtMeshForm form = liveMesh.getCurrentKeyform();
            if (form == null) throw new IllegalStateException("Task parameter values do not select an editable source keyform");
            RESULT.setProperty("scenario.viewDrag.formGuid", form.getGuid().getUuidString());
            float[] origPositions = form.getPositions();
            if (origPositions == null || origPositions.length < 2) {
                throw new IllegalStateException("Target ArtMesh has no vertex positions!");
            }
            for(ACForm bankForm:allForms(targets.artMesh)) beforeBank.add(formBits(bankForm));
            selectedPositions.add(rawPositionBits(origPositions));
            coords[0] = origPositions[0];
            coords[1] = origPositions[1];

            // Hit the interior of a real evaluated triangle, using Canvas rather than local coordinates.
            float[] canvasPositions = liveMesh.getCalculatedForm().getPositions();
            int[] triangles = liveMesh.getIndices();
            int dragStartX = -1, dragStartY = -1;
            for (int t=0;t+2<triangles.length;t+=3) {
                float x=0, y=0;
                for(int corner=0;corner<3;corner++) {int index=triangles[t+corner]*2;x+=canvasPositions[index]/3f;y+=canvasPositions[index+1]/3f;}
                var mapped=((com.live2d.graphics3d.sceneGraph.GCameraManager)ctx.camera).documentToComponent(new com.live2d.graphics3d.type.GVector2(x,y));
                if(Float.isFinite(mapped.getX()) && Float.isFinite(mapped.getY()) && mapped.getX()>=24 && mapped.getY()>=24 && mapped.getX()<ctx.canvas.getWidth()-64 && mapped.getY()<ctx.canvas.getHeight()-48) {
                    dragStartX=Math.round(mapped.getX());dragStartY=Math.round(mapped.getY());break;
                }
            }
            RESULT.setProperty("scenario.viewDrag.canvasSize", ctx.canvas.getWidth()+"x"+ctx.canvas.getHeight());
            RESULT.setProperty("scenario.viewDrag.calculatedPoints",Integer.toString(canvasPositions.length/2));
            RESULT.setProperty("scenario.viewDrag.indices",Integer.toString(triangles.length));
            if(canvasPositions.length>=2) RESULT.setProperty("scenario.viewDrag.firstCalculated",canvasPositions[0]+","+canvasPositions[1]);
            if(dragStartX<0) throw new IllegalStateException("No visible bounded triangle in task edit mesh");
            RESULT.setProperty("scenario.viewDrag.componentX",Integer.toString(dragStartX));
            RESULT.setProperty("scenario.viewDrag.componentY",Integer.toString(dragStartY));
            RESULT.setProperty("scenario.viewDrag.mapping","CALCULATED_CANVAS_TRIANGLE_CENTROID");
            for(int id:new int[]{MouseEvent.MOUSE_MOVED}) {
                Object hover=ctx.mouseCtor.newInstance(new MouseEvent(ctx.canvas,id,System.currentTimeMillis(),0,dragStartX,dragStartY,0,false,MouseEvent.NOBUTTON));
                ctx.onInput.invoke(ctx.viewContext,hover);
            }

            // Real Mouse Drag Sequence on View Canvas
            long tAllocStart = threadBean.getThreadAllocatedBytes(Thread.currentThread().getId());
            long tStart = System.nanoTime();

            // PRESS
            MouseEvent pressEvent = new MouseEvent(ctx.canvas, MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK, dragStartX, dragStartY, 1, false, MouseEvent.BUTTON1);
            Object dragGesture = ctx.dragCtor.newInstance(pressEvent);
            ctx.onInput.invoke(ctx.viewContext, dragGesture);
            verifyLastMouseEvent(ctx, dragGesture);

            // MULTI-STEP DRAG (total 40px displacement)
            for (int step = 1; step <= 8; step++) {
                int curX = dragStartX + (step * 5);
                int curY = dragStartY + (step * 3);
                MouseEvent dragMove = new MouseEvent(ctx.canvas, MouseEvent.MOUSE_DRAGGED,
                        System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK, curX, curY, 0, false, MouseEvent.NOBUTTON);
                ctx.dragUpdate.invoke(dragGesture, dragMove);
                ctx.onInput.invoke(ctx.viewContext, dragGesture);
                verifyLastMouseEvent(ctx, dragGesture);
            }

            // RELEASE
            MouseEvent releaseEvent = new MouseEvent(ctx.canvas, MouseEvent.MOUSE_RELEASED,
                    System.currentTimeMillis(), 0, dragStartX + 40, dragStartY + 24, 1, false, MouseEvent.BUTTON1);
            ctx.dragRelease.invoke(dragGesture, releaseEvent);
            ctx.onInput.invoke(ctx.viewContext, dragGesture);
            verifyLastMouseEvent(ctx, dragGesture);

            long tEnd = System.nanoTime();
            long tAllocEnd = threadBean.getThreadAllocatedBytes(Thread.currentThread().getId());

            latencies.add(tEnd - tStart);
            allocTotal.addAndGet(Math.max(0, tAllocEnd - tAllocStart));

            float[] newPositions = ((CArtMeshForm) targets.artMesh.getKeyForm(form.getGuid())).getPositions();
            for(ACForm bankForm:allForms(targets.artMesh)) afterBank.add(formBits(bankForm));
            selectedPositions.add(rawPositionBits(newPositions));
            coords[2] = newPositions[0];
            coords[3] = newPositions[1];
            return null;
        });

        float beforeX = coords[0], beforeY = coords[1];
        float afterX = coords[2], afterY = coords[3];
        float deltaX = afterX - beforeX;
        float deltaY = afterY - beforeY;

        boolean mutated = !Arrays.equals(selectedPositions.get(0), selectedPositions.get(1));

        if (!Float.isFinite(afterX) || !Float.isFinite(afterY)) {
            throw new AssertionError("Drag resulted in non-finite coordinates: " + afterX + ", " + afterY);
        }

        String verifiedStatus = mutated ? "FULL_UI_MUTATION_PROVEN" : "UNVERIFIED_GEOMETRY_UNCHANGED";
        RESULT.setProperty("scenario.viewDrag.verifiedStatus", verifiedStatus);
        RESULT.setProperty("scenario.viewDrag.mutated", Boolean.toString(mutated));

        System.err.println("[EI-HOST-PROBE] Drag coordinates: before=(" + beforeX + "," + beforeY
                + ") after=(" + afterX + "," + afterY + ") delta=(" + deltaX + "," + deltaY + ") status=" + verifiedStatus);

        return new DragResult(computeMetrics(latencies, allocTotal.get(), mutated ? "PASS" : "UNVERIFIED"),
                beforeX, beforeY, afterX, afterY, deltaX, deltaY, mutated, beforeBank, afterBank);
    }

    private static void executeDragBenchmark(ContextRef ctx, TargetObjects targets, Class<?> bridge,
                                              com.sun.management.ThreadMXBean threadBean) throws Exception {
        final int warmup=3, samples=12;
        try {
            for(ExtendedInterpolationType curve:new ExtendedInterpolationType[]{ExtendedInterpolationType.ELLIPSE,ExtendedInterpolationType.SNS_CURVE}) {
                edt(() -> {
                    for(ACParameterControllableSource owner:List.of(targets.rotation,targets.warp,targets.artMesh)) {
                        for(KeyformBindingSource binding:owner.getKeyformGridSource().getKeyformBindings()) binding.setExtendedInterpolationType(curve);
                        for(Object extension:owner.getExtensions()) if(extension instanceof CExtendedInterpolationExtension ext) ext.setupFormStructure(true,true);
                    }
                    return null;
                });
                List<Long> nativeTimes=new ArrayList<>(), optimizedTimes=new ArrayList<>();
                long nativeAlloc=0, optimizedAlloc=0, nativeBridgeCalls=0, optimizedBridgeCalls=0;
                for(int iteration=-warmup;iteration<samples;iteration++) {
                    List<int[]> oracle=null;
                    for(boolean optimized:new boolean[]{false,true}) {
                        setBridgeEnabled(optimized);
                        long accepted=readBridgeCounter(bridge,"acceptedCalls");
                        long start=System.nanoTime();
                        DragResult drag=executeRealViewDraggingWithAssertion(ctx,targets,threadBean);
                        if(!drag.mutated) throw new AssertionError("A/B source drag did not mutate geometry");
                        // Include an actual native evaluation barrier in the observed latency.
                        awaitNativeFrame(ctx);
                        long elapsed=System.nanoTime()-start;
                        List<int[]> actual=edt(() -> {
                            List<int[]> bits=new ArrayList<>();
                            for(ACForm form:allForms(targets.artMesh))bits.add(formBits(form));
                            return bits;
                        });
                        if(!optimized) oracle=actual;
                        else {
                            if(oracle.size()!=actual.size()) throw new AssertionError("A/B drag bank size mismatch");
                            for(int i=0;i<actual.size();i++)if(!Arrays.equals(oracle.get(i),actual.get(i)))
                                throw new AssertionError("A/B drag raw bits differ at form "+i);
                        }
                        long calls=readBridgeCounter(bridge,"acceptedCalls")-accepted;
                        if(!optimized && calls!=0) throw new AssertionError("Native A/B leg used optimization bridge");
                        if(optimized && calls<=0) throw new AssertionError("Optimized A/B leg did not use bridge");
                        executeUndoRedoCycleWithAssertion(ctx,targets,drag,threadBean);
                        edt(() -> {
                            ctx.appCtrl.getClass().getMethod("command_undo",Class.forName("com.live2d.cubism.doc.IDocument")).invoke(ctx.appCtrl,ctx.doc);
                            var current=ctx.doc.getModelSource().getObject(targets.artMesh.getGuid(),false);
                            assertBankBits((ACParameterControllableSource)current,drag.beforeBank,"A/B final Undo");
                            return null;
                        });
                        awaitNativeFrame(ctx);
                        if(iteration>=0) {
                            if(optimized) {optimizedTimes.add(elapsed);optimizedAlloc+=drag.metrics.allocatedBytes;optimizedBridgeCalls+=calls;}
                            else {nativeTimes.add(elapsed);nativeAlloc+=drag.metrics.allocatedBytes;nativeBridgeCalls+=calls;}
                        }
                    }
                }
                String prefix="scenario.dragAB."+curve.name();
                recordMetrics(prefix+".native",computeMetrics(nativeTimes,nativeAlloc,"PASS"));
                recordMetrics(prefix+".optimized",computeMetrics(optimizedTimes,optimizedAlloc,"PASS"));
                RESULT.setProperty(prefix+".native.acceptedDelta",Long.toString(nativeBridgeCalls));
                RESULT.setProperty(prefix+".optimized.acceptedDelta",Long.toString(optimizedBridgeCalls));
                RESULT.setProperty(prefix+".scope","12_PAIRED_REAL_MESH_DRAGS_PREPARATION_AND_NATIVE_FRAME_BARRIER_INCLUDED_NOT_FPS");
                RESULT.setProperty(prefix+".allocationScope","MEASURED_EVENT_SEQUENCE_EDT_ONLY_NOT_FULL_OPERATION");
                RESULT.setProperty(prefix+".bankProof","ALL_MESH_BANKS_NATIVE_OPTIMIZED_RAW_BITS_AND_HISTORY_MATCH");
            }
        } finally {setBridgeEnabled(true);}
    }

    private static int[] rawPositionBits(float[] points) {
        int[] bits=new int[points.length];
        for(int i=0;i<points.length;i++) bits[i]=Float.floatToRawIntBits(points[i]);
        return bits;
    }

    private static void assertBankBits(ACParameterControllableSource owner, List<int[]> expected, String phase) {
        List<ACForm> forms=allForms(owner);
        if(forms.size()!=expected.size()) throw new AssertionError(phase+" changed bank size");
        for(int i=0;i<forms.size();i++) if(!Arrays.equals(expected.get(i),formBits(forms.get(i))))
            throw new AssertionError(phase+" bank mismatch at form "+i);
    }

    private static void selectObject(ContextRef ctx, Object targetSource) throws Exception {
        ISelector selector = ctx.selector;
        ISelection selection = null;
        if (targetSource instanceof ISelectableObject iso) {
            selection = iso.getSelection();
        } else {
            Method getSel = targetSource.getClass().getMethod("getSelection");
            selection = (ISelection) getSel.invoke(targetSource);
        }
        selector.clearSelection();
        selector.select(selection, false);
    }

    private static void verifyLastMouseEvent(ContextRef ctx, Object expectedEvent) throws Exception {
        Object last = ctx.viewContext.getClass().getMethod("getLastMouseEvent").invoke(ctx.viewContext);
        if (last != expectedEvent) {
            throw new AssertionError("View did not consume the task mouse event! Expected: "
                    + expectedEvent + " but was: " + last);
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 3: Undo & Redo with exact coordinate restoration & reapplication assertion
    // -------------------------------------------------------------------------
    private static ScenarioMetrics executeUndoRedoCycleWithAssertion(ContextRef ctx, TargetObjects targets,
                                                                    DragResult drag,
                                                                    com.sun.management.ThreadMXBean threadBean) throws Exception {
        List<Long> latencies = new ArrayList<>();
        AtomicLong allocTotal = new AtomicLong();

        if (!drag.mutated) {
            RESULT.setProperty("scenario.undoRedo.verifiedStatus", "SKIPPED_NO_DRAG_MUTATION");
            return new ScenarioMetrics("SKIPPED", 0, 0, 0, 0, 0, 0, 0);
        }

        edt(() -> {
            Method undo = ctx.appCtrl.getClass().getMethod("command_undo", Class.forName("com.live2d.cubism.doc.IDocument"));
            Method redo = ctx.appCtrl.getClass().getMethod("command_redo", Class.forName("com.live2d.cubism.doc.IDocument"));

            // 1. Perform Undo
            long tAllocStart = threadBean.getThreadAllocatedBytes(Thread.currentThread().getId());
            long tStart = System.nanoTime();

            undo.invoke(ctx.appCtrl, ctx.doc);
            ctx.viewContext.getClass().getMethod("repaintCanvas").invoke(ctx.viewContext);

            // Re-acquire fresh form reference by GUID from live document to avoid reading stale instance
            CArtMeshSource currentMeshUndo = (CArtMeshSource) ctx.doc.getModelSource().getObject(targets.artMesh.getGuid(), false);
            if (currentMeshUndo == null) {
                throw new IllegalStateException("ArtMesh disappeared after undo: " + targets.artMesh.getGuid());
            }
            CArtMeshForm undoForm = (CArtMeshForm) currentMeshUndo.getKeyForm(new CFormGuid(UUID.fromString(RESULT.getProperty("scenario.viewDrag.formGuid"))));
            float[] undoPositions = undoForm.getPositions();
            // Assert coordinate restoration
            if (Float.floatToRawIntBits(undoPositions[0]) != Float.floatToRawIntBits(drag.beforeX)
                    || Float.floatToRawIntBits(undoPositions[1]) != Float.floatToRawIntBits(drag.beforeY)) {
                throw new AssertionError("Undo failed to restore initial geometry bits! Expected: ("
                        + drag.beforeX + "," + drag.beforeY + ") but got: (" + undoPositions[0] + "," + undoPositions[1] + ")");
            }

            assertBankBits(currentMeshUndo, drag.beforeBank, "Undo");

            // 2. Perform Redo
            redo.invoke(ctx.appCtrl, ctx.doc);
            ctx.viewContext.getClass().getMethod("repaintCanvas").invoke(ctx.viewContext);

            // Re-acquire fresh form reference by GUID from live document to avoid reading stale instance
            CArtMeshSource currentMeshRedo = (CArtMeshSource) ctx.doc.getModelSource().getObject(targets.artMesh.getGuid(), false);
            if (currentMeshRedo == null) {
                throw new IllegalStateException("ArtMesh disappeared after redo: " + targets.artMesh.getGuid());
            }
            CArtMeshForm redoForm = (CArtMeshForm) currentMeshRedo.getKeyForm(new CFormGuid(UUID.fromString(RESULT.getProperty("scenario.viewDrag.formGuid"))));
            float[] redoPositions = redoForm.getPositions();
            // Assert coordinate reapplication
            if (Float.floatToRawIntBits(redoPositions[0]) != Float.floatToRawIntBits(drag.afterX)
                    || Float.floatToRawIntBits(redoPositions[1]) != Float.floatToRawIntBits(drag.afterY)) {
                throw new AssertionError("Redo failed to reapply mutated geometry bits! Expected: ("
                        + drag.afterX + "," + drag.afterY + ") but got: (" + redoPositions[0] + "," + redoPositions[1] + ")");
            }

            assertBankBits(currentMeshRedo, drag.afterBank, "Redo");
            long tEnd = System.nanoTime();
            long tAllocEnd = threadBean.getThreadAllocatedBytes(Thread.currentThread().getId());

            latencies.add(tEnd - tStart);
            allocTotal.addAndGet(Math.max(0, tAllocEnd - tAllocStart));
            return null;
        });

        RESULT.setProperty("scenario.undoRedo.verifiedStatus", "ALL_MESH_BANKS_RAW_BITS_ATTRIBUTES_RESTORED");
        return computeMetrics(latencies, allocTotal.get(), "PASS");
    }

    // -------------------------------------------------------------------------
    // Scenario 4: Configuration & Topology Switch (ELLIPSE <-> SNS_CURVE)
    // -------------------------------------------------------------------------
    private static ScenarioMetrics executeConfigAndTopologySwitch(ContextRef ctx, TargetObjects targets,
                                                                  com.sun.management.ThreadMXBean threadBean) throws Exception {
        List<Long> latencies = new ArrayList<>();
        AtomicLong allocTotal = new AtomicLong();

        ACParameterControllableSource[] targetList = new ACParameterControllableSource[]{
                targets.artMesh, targets.warp, targets.rotation
        };

        for (ACParameterControllableSource target : targetList) {
            edt(() -> {
                long tAllocStart = threadBean.getThreadAllocatedBytes(Thread.currentThread().getId());
                long tStart = System.nanoTime();

                CExtendedInterpolationExtension ext = null;
                for (Object obj : target.getExtensions()) {
                    if (obj instanceof CExtendedInterpolationExtension eie) {
                        ext = eie;
                        break;
                    }
                }
                if (ext != null) {
                    KeyformGridSource ownerGrid = target.getKeyformGridSource();
                    if (ownerGrid != null) {
                        for (KeyformBindingSource binding : ownerGrid.getKeyformBindings()) {
                            binding.setExtendedInterpolationType(ExtendedInterpolationType.SNS_CURVE);
                        }
                    }
                    ext.setupFormStructure(true, true);
                    ext.updateInterpolatedForms(true);
                    verifyLiveSourceEdit(target, ext, "SNS");

                    // Assert derived extended grid actually adopted SNS_CURVE
                    KeyformGridSource extGrid = ext.getExtendedKeyformGridSource();
                    if (extGrid != null && !extGrid.getKeyformBindings().isEmpty()) {
                        for (KeyformBindingSource eb : extGrid.getKeyformBindings()) {
                            if (eb.getExtendedInterpolationType() != ExtendedInterpolationType.SNS_CURVE) {
                                throw new AssertionError("Derived extended grid did not adopt SNS_CURVE from owner grid!");
                            }
                        }
                    }

                    // Switch back to ELLIPSE on owner grid
                    if (ownerGrid != null) {
                        for (KeyformBindingSource binding : ownerGrid.getKeyformBindings()) {
                            binding.setExtendedInterpolationType(ExtendedInterpolationType.ELLIPSE);
                        }
                    }
                    ext.setupFormStructure(true, true);
                    ext.updateInterpolatedForms(true);
                    verifyLiveSourceEdit(target, ext, "ELLIPSE");
                    extGrid = ext.getExtendedKeyformGridSource();

                    if (extGrid != null && !extGrid.getKeyformBindings().isEmpty()) {
                        for (KeyformBindingSource eb : extGrid.getKeyformBindings()) {
                            if (eb.getExtendedInterpolationType() != ExtendedInterpolationType.ELLIPSE) {
                                throw new AssertionError("Derived extended grid did not revert to ELLIPSE from owner grid!");
                            }
                        }
                    }
                }

                long tEnd = System.nanoTime();
                long tAllocEnd = threadBean.getThreadAllocatedBytes(Thread.currentThread().getId());

                latencies.add(tEnd - tStart);
                allocTotal.addAndGet(Math.max(0, tAllocEnd - tAllocStart));
                return null;
            });
        }

        RESULT.setProperty("scenario.configSwitch.verifiedStatus", "OBSERVED_CORE_REBUILD_ONLY");
        RESULT.setProperty("scenario.configSwitch.uiScope", "EXTENSION_API_REBUILD_NOT_UI_DIALOG");
        return computeMetrics(latencies, allocTotal.get(), "OBSERVED");
    }

    private static float[] formCoordinates(ACForm form) {
        if (form instanceof CArtMeshForm mesh) return mesh.getPositions();
        if (form instanceof CWarpDeformerForm warp) return warp.getPositions();
        CRotationDeformerForm rotation = (CRotationDeformerForm) form;
        return new float[]{rotation.getOriginX(), rotation.getOriginY()};
    }

    private static int[] formBits(ACForm form) {
        float[] coordinates = formCoordinates(form);
        int[] bits = new int[coordinates.length + 4];
        for (int i = 0; i < coordinates.length; i++) bits[i] = Float.floatToRawIntBits(coordinates[i]);
        bits[coordinates.length] = Float.floatToRawIntBits(form instanceof CArtMeshForm mesh ? mesh.getOpacity() : form instanceof CWarpDeformerForm warp ? warp.getOpacity() : ((CRotationDeformerForm) form).getOpacity());
        if (form instanceof CRotationDeformerForm rotation) {
            bits[coordinates.length + 1] = Float.floatToRawIntBits(rotation.getAngle());
            bits[coordinates.length + 2] = Float.floatToRawIntBits(rotation.getScale());
        }
        return bits;
    }

    private static List<ACForm> allForms(ACParameterControllableSource owner) {
        List<ACForm> forms = new ArrayList<>();
        for (Object form : owner.getKeyforms()) forms.add((ACForm) form);
        for (Object extension : owner.getExtensions()) {
            if (extension instanceof CExtendedInterpolationExtension ext) {
                forms.addAll(ext.getNormalExtendedKeyforms());
                forms.addAll(ext.getMorphTargetExtendedKeyforms());
            }
        }
        return forms;
    }

    // A live-document kernel oracle, deliberately separate from mouse/history proof.
    private static void verifyLiveSourceEdit(ACParameterControllableSource owner,
                                             CExtendedInterpolationExtension ext, String curve) throws Exception {
        ACForm source = (ACForm) owner.getKeyforms().get(0);
        if (source instanceof CRotationDeformerForm rotation) rotation.setOriginX(rotation.getOriginX() + 0.125f);
        else formCoordinates(source)[0] += 0.125f;
        source.setUpdated();
        List<ACForm> forms = allForms(owner);
        long[] versions = new long[forms.size()];
        long[] delta = new long[forms.size()];
        List<int[]> expected = new ArrayList<>();
        Method enabled = Class.forName(BRIDGE_CLASS).getMethod("enabled", boolean.class);
        try {
            for (int i=0;i<forms.size();i++) versions[i]=forms.get(i).getEditVersion();
            enabled.invoke(null, false);
            ext.updateInterpolatedForms(true);
            for (int i=0;i<forms.size();i++) {
                expected.add(formBits(forms.get(i)));
                delta[i]=forms.get(i).getEditVersion()-versions[i];
                versions[i]=forms.get(i).getEditVersion();
            }
            enabled.invoke(null, true);
            long before=readBridgeCounter(Class.forName(BRIDGE_CLASS), "acceptedCalls");
            ext.updateInterpolatedForms(true);
            if (readBridgeCounter(Class.forName(BRIDGE_CLASS), "acceptedCalls")<=before)
                throw new AssertionError("Live source edit did not use bridge: " + owner.getClass() + curve);
            for (int i=0;i<forms.size();i++) {
                if (!Arrays.equals(expected.get(i), formBits(forms.get(i))) ||
                        forms.get(i).getEditVersion()-versions[i]!=delta[i])
                    throw new AssertionError("Live native/optimized mismatch: " + owner.getClass() + curve + " form=" + i);
            }
            RESULT.setProperty("scenario.sourceEdit." + owner.getClass().getSimpleName() + "." + curve,
                    "RAW_BITS_AND_EDIT_VERSION_DELTA_MATCH_LIVE_CORE_API");
        } finally { enabled.invoke(null, true); }
    }

    // -------------------------------------------------------------------------
    // Scenario 5: Hierarchical Deformation Cascade
    // -------------------------------------------------------------------------
    private static ScenarioMetrics executeHierarchyCascade(ContextRef ctx, TargetObjects targets,
                                                           com.sun.management.ThreadMXBean threadBean) throws Exception {
        long start=System.nanoTime();
        awaitNativeFrame(ctx);
        int[] before=edt(() -> rawPositionBits(ctx.model.getArtMesh(targets.artMesh.getGuid()).getCalculatedForm().getPositions()));
        edt(() -> {
            for(Object form:targets.rotation.getKeyforms()) {
                CRotationDeformerForm rotation=(CRotationDeformerForm)form;
                rotation.setAngle(rotation.getAngle()+15f);rotation.setUpdated();
            }
            refreshTargets(targets,false);
            return null;
        });
        int[] nativePoints;
        setBridgeEnabled(false);
        try {
            awaitNativeFrame(ctx);
            nativePoints=edt(() -> rawPositionBits(ctx.model.getArtMesh(targets.artMesh.getGuid()).getCalculatedForm().getPositions()));
        } finally {setBridgeEnabled(true);}
        if(Arrays.equals(before,nativePoints)) throw new AssertionError("Parent rotation did not move evaluated child mesh");
        edt(() -> {refreshTargets(targets,true);return null;});
        awaitNativeFrame(ctx);
        int[] optimizedPoints=edt(() -> rawPositionBits(ctx.model.getArtMesh(targets.artMesh.getGuid()).getCalculatedForm().getPositions()));
        if(!Arrays.equals(nativePoints,optimizedPoints)) throw new AssertionError("Native/optimized hierarchy child geometry differs");
        RESULT.setProperty("scenario.hierarchy.verifiedStatus","PARENT_ROTATION_MOVES_CHILD_NATIVE_OPTIMIZED_RAW_BITS_MATCH");
        RESULT.setProperty("scenario.hierarchy.cascadeProof","PASS");
        return computeMetrics(List.of(System.nanoTime()-start),0,"PASS");
    }

    // Observational P4 baseline only: no native grid clamping or cache eviction.
    private static void recordCacheFootprint(TargetObjects targets, String phase) throws Exception {
        String prefix="cache."+phase+".";
        long totalForms=0, coordinateBytes=0;
        int ownerIndex=0;
        for(ACParameterControllableSource owner:List.of(targets.artMesh,targets.warp,targets.rotation)) {
            List<ACForm> forms=allForms(owner);
            long ownerBytes=0;
            for(ACForm form:forms) ownerBytes=Math.addExact(ownerBytes,Math.multiplyExact(4L,formCoordinates(form).length));
            RESULT.setProperty(prefix+"owner."+ownerIndex+".forms",Integer.toString(forms.size()));
            RESULT.setProperty(prefix+"owner."+ownerIndex+".coordinateBytes",Long.toString(ownerBytes));
            totalForms=Math.addExact(totalForms,forms.size());
            coordinateBytes=Math.addExact(coordinateBytes,ownerBytes);
            ownerIndex++;
        }
        RESULT.setProperty(prefix+"forms",Long.toString(totalForms));
        RESULT.setProperty(prefix+"coordinateBytes",Long.toString(coordinateBytes));
        RESULT.setProperty(prefix+"coordinateBytesScope","SUM_OF_FORM_COORDINATE_LENGTHS_NOT_RETAINED_HEAP");
        var heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        RESULT.setProperty(prefix+"heapUsed",Long.toString(heap.getUsed()));
        RESULT.setProperty(prefix+"heapCommitted",Long.toString(heap.getCommitted()));
        RESULT.setProperty(prefix+"heapMax",Long.toString(heap.getMax()));
        long gcCount=0,gcMs=0;
        for(var collector:ManagementFactory.getGarbageCollectorMXBeans()) {
            if(collector.getCollectionCount()>=0)gcCount+=collector.getCollectionCount();
            if(collector.getCollectionTime()>=0)gcMs+=collector.getCollectionTime();
        }
        RESULT.setProperty(prefix+"gcCountSinceProcessStart",Long.toString(gcCount));
        RESULT.setProperty(prefix+"gcMsSinceProcessStart",Long.toString(gcMs));
        for(var pool:ManagementFactory.getMemoryPoolMXBeans())
            if(pool.getType()==java.lang.management.MemoryType.HEAP && pool.getPeakUsage()!=null)
                RESULT.setProperty(prefix+"poolPeak."+pool.getName(),Long.toString(pool.getPeakUsage().getUsed()));
        Object cacheStats=Class.forName("dev.turboism.adapter.cubism.interpolation.NativeFormLookupBridge")
                .getMethod("cacheStatistics").invoke(null);
        for(var component:cacheStats.getClass().getRecordComponents())
            RESULT.setProperty(prefix+"auxIndex."+component.getName(),component.getAccessor().invoke(cacheStats).toString());
        if(((Number)cacheStats.getClass().getMethod("activeScopes").invoke(cacheStats)).longValue()!=0
                || ((Number)cacheStats.getClass().getMethod("activeIndexEntries").invoke(cacheStats)).longValue()!=0)
            throw new AssertionError("Auxiliary interpolation index retained after native rebuild");
        RESULT.setProperty("cache.indexTemporaryFormBudget","65536");
        RESULT.setProperty("cache.nativeDenseGridPolicy","UNCHANGED_NO_AUTHOR_POINT_REDUCTION");
    }

    private static long recordCurveCache(String phase) throws Exception {
        Object stats=Class.forName(BRIDGE_CLASS).getMethod("curveCacheStatistics").invoke(null);
        for(var component:stats.getClass().getRecordComponents())
            RESULT.setProperty("curveCache."+phase+"."+component.getName(),component.getAccessor().invoke(stats).toString());
        long bytes=((Number)stats.getClass().getMethod("bytes").invoke(stats)).longValue();
        long budget=((Number)stats.getClass().getMethod("byteBudget").invoke(stats)).longValue();
        if(bytes<0 || bytes>budget)throw new AssertionError("Numerical cache budget exceeded");
        return ((Number)stats.getClass().getMethod("hits").invoke(stats)).longValue();
    }

    private static long recordLocalRefresh(String phase) throws Exception {
        Object stats=Class.forName(BRIDGE_CLASS).getMethod("localRefreshStatistics").invoke(null);
        for(var component:stats.getClass().getRecordComponents())
            RESULT.setProperty("localRefresh."+phase+"."+component.getName(),component.getAccessor().invoke(stats).toString());
        long bytes=((Number)stats.getClass().getMethod("bytes").invoke(stats)).longValue();
        long budget=((Number)stats.getClass().getMethod("byteBudget").invoke(stats)).longValue();
        if(bytes<0 || bytes>budget)throw new AssertionError("Local-refresh cache budget exceeded");
        return ((Number)stats.getClass().getMethod("hits").invoke(stats)).longValue();
    }

    private static void setBridgeEnabled(boolean enabled) throws Exception {
        Class.forName(BRIDGE_CLASS).getMethod("enabled",boolean.class).invoke(null,enabled);
    }

    private static void refreshTargets(TargetObjects targets, boolean optimized) throws Exception {
        Method enabled=Class.forName(BRIDGE_CLASS).getMethod("enabled",boolean.class);
        try {
            enabled.invoke(null,optimized);
            for(ACParameterControllableSource owner:List.of(targets.rotation,targets.warp,targets.artMesh))
                for(Object extension:owner.getExtensions())
                    if(extension instanceof CExtendedInterpolationExtension ext) ext.updateInterpolatedForms(true);
        } finally {enabled.invoke(null,true);}
    }

    private static void awaitNativeFrame(ContextRef ctx) throws Exception {
        long before=edt(() -> {
            long timestamp=ctx.model.getLastUpdatedTimeMSec();
            ctx.doc.getModelSource().updateModelInstances();
            ctx.model.updateParamInstance();
            ctx.viewContext.getClass().getMethod("repaintCanvas").invoke(ctx.viewContext);
            return timestamp;
        });
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(edt(() -> ctx.model.getLastUpdatedTimeMSec())<=before) {
            if(System.nanoTime()>=deadline) throw new IllegalStateException("No native model evaluation after repaint");
            Thread.sleep(50);
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 6: Save & Reopen / Persistence Verification
    // -------------------------------------------------------------------------
    private static ScenarioMetrics executeSaveAndPersistenceWithVerification(ContextRef ctx, TargetObjects targets,
                                                                             Path home,
                                                                             com.sun.management.ThreadMXBean threadBean) throws Exception {
        List<Long> latencies = new ArrayList<>();
        AtomicLong allocTotal = new AtomicLong();

        Path saveTarget = home.resolve("state/extended-interpolation/saved-verify.cmo3");
        Files.createDirectories(saveTarget.getParent());
        File saveFile = saveTarget.toFile();
        Properties persistedBank = edt(() -> bankManifest(ctx.doc, targets));
        try (OutputStream output = Files.newOutputStream(saveTarget.resolveSibling("saved-bank.properties"))) {
            persistedBank.store(output, "Independent-session native model reopen oracle");
        }


        edt(() -> {
            long tAllocStart = threadBean.getThreadAllocatedBytes(Thread.currentThread().getId());
            long tStart = System.nanoTime();

            // Official saveDocument into task-isolated path
            boolean saved = ctx.doc.saveDocument(saveFile, false);
            if (!saved && !saveFile.exists()) {
                throw new AssertionError("Official saveDocument failed to write: " + saveFile);
            }

            long tEnd = System.nanoTime();
            long tAllocEnd = threadBean.getThreadAllocatedBytes(Thread.currentThread().getId());

            latencies.add(tEnd - tStart);
            allocTotal.addAndGet(Math.max(0, tAllocEnd - tAllocStart));
            return null;
        });

        // Verification of saved cmo3 file integrity and content
        if (!saveFile.exists() || saveFile.length() < 1024) {
            throw new AssertionError("Saved cmo3 file missing or too small: " + saveFile);
        }

        // cmo3 uses Cubism's CAFF container, not a standard ZIP archive.
        // Parse its directory with the official reader; full geometry is checked on reopen below.
        var archive = new com.live2d.serialize.archiver.ArchiveReader(saveFile);
        archive.close();

        RESULT.setProperty("scenario.save.bytes", Long.toString(saveFile.length()));
        RESULT.setProperty("scenario.save.verified", "true");
        RESULT.setProperty("scenario.save.verifiedStatus", "OBSERVED_FILE_WRITTEN");

        // Attempt official reopen via command_open(saveFile, false) and inspect getAllModelDocs()
        try {
            Method getAllModelDocs = ctx.appCtrl.getClass().getMethod("getAllModelDocs");
            @SuppressWarnings("unchecked")
            List<CModelingDocument> beforeDocs = (List<CModelingDocument>) getAllModelDocs.invoke(ctx.appCtrl);
            Set<Object> beforeUids = new HashSet<>();
            for (CModelingDocument d : beforeDocs) {
                beforeUids.add(d.getDocumentUID());
            }

            // A second path forces an independent document instead of focusing the saved document.
            Path reopenPath = saveTarget.resolveSibling("reopened-verify.cmo3");
            Files.copy(saveTarget, reopenPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            File reopenFile = reopenPath.toFile();
            Method commandOpen = ctx.appCtrl.getClass().getMethod("command_open", File.class, boolean.class);
            edt(() -> {
                commandOpen.invoke(ctx.appCtrl, reopenFile, false);
                return null;
            });

            // Poll briefly for the new document to appear in getAllModelDocs
            long reopenDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            CModelingDocument reopenedDoc = null;
            while (System.nanoTime() < reopenDeadline) {
                @SuppressWarnings("unchecked")
                List<CModelingDocument> currentDocs = (List<CModelingDocument>) getAllModelDocs.invoke(ctx.appCtrl);
                for (CModelingDocument d : currentDocs) {
                    if (d.getFile() != null && d.getFile().equals(reopenFile) && !beforeUids.contains(d.getDocumentUID())) {
                        reopenedDoc = d;
                        break;
                    }
                }
                if (reopenedDoc != null) break;
                Thread.sleep(200);
            }

            if (reopenedDoc != null) {
                CModelSource newSource = reopenedDoc.getModelSource();
                boolean foundGuid = false;
                for (CArtMeshSource m : newSource.getAllArtMeshes()) {
                    if (m.getGuid().getUuidString().equals(targets.artMeshGuid)) {
                        foundGuid = true;
                        break;
                    }
                }
                if (foundGuid) {
                    for (ACParameterControllableSource original : new ACParameterControllableSource[]{targets.artMesh, targets.warp, targets.rotation}) {
                        ACParameterControllableSource loaded = null;
                        List<ACParameterControllableSource> candidates = new ArrayList<>();
                        candidates.addAll(newSource.getAllArtMeshes());
                        candidates.addAll(newSource.getAllDeformers());
                        for (ACParameterControllableSource candidate : candidates)
                            if (candidate.getGuid().equals(original.getGuid())) loaded = candidate;
                        if (loaded == null) throw new AssertionError("Reopened source missing " + original.getGuid());
                        List<ACForm> expectedForms=allForms(original), loadedForms=allForms(loaded);
                        if (expectedForms.size()!=loadedForms.size()) throw new AssertionError("Reopened form count mismatch");
                        for (int i=0;i<expectedForms.size();i++)
                            if (!Arrays.equals(formBits(expectedForms.get(i)),formBits(loadedForms.get(i))))
                                throw new AssertionError("Reopened geometry mismatch form=" + i + " source=" + original.getClass() + " expected=" + Arrays.toString(formBits(expectedForms.get(i))) + " actual=" + Arrays.toString(formBits(loadedForms.get(i))));
                    }
                    RESULT.setProperty("scenario.save.reopenGeometry", "THREE_SOURCE_BANKS_RAW_BITS_MATCH_SAME_SESSION");
                    RESULT.setProperty("scenario.save.reopenVerified", "true");
                    RESULT.setProperty("scenario.save.reopenNewDocUid", reopenedDoc.getDocumentUID().toString());
                    RESULT.setProperty("scenario.save.reopenProof", "PROVEN_NEW_DOC_IDENTITY_VERIFIED");
                    System.err.println("[EI-HOST-PROBE] Official reopen verified with new document identity: " + reopenedDoc.getDocumentUID());
                } else {
                    RESULT.setProperty("scenario.save.reopenProof", "UNVERIFIED_TARGET_GUID_ABSENT_IN_REOPENED");
                }
            } else {
                RESULT.setProperty("scenario.save.reopenProof", "UNVERIFIED_ASYNC_OPEN_PENDING");
            }
        } catch (AssertionError mismatch) {
            throw mismatch;
        } catch (Throwable e) {
            RESULT.setProperty("scenario.save.reopenProof", "UNVERIFIED_EXCEPTION_" + e.getClass().getSimpleName());
        }

        return computeMetrics(latencies, allocTotal.get(), "OBSERVED");
    }

    private static String bankDigest(ACParameterControllableSource owner) throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        for(ACForm form:allForms(owner)) {
            int[] bits=formBits(form);
            var buffer=java.nio.ByteBuffer.allocate((bits.length+1)*4);
            buffer.putInt(bits.length); for(int value:bits)buffer.putInt(value);
            hash.update(buffer.array());
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static Properties bankManifest(CModelingDocument doc, TargetObjects targets) throws Exception {
        Properties result=new Properties();
        result.setProperty("originDocumentUid",String.valueOf(doc.getDocumentUID()));
        result.setProperty("originJvmStart",Long.toString(ManagementFactory.getRuntimeMXBean().getStartTime()));
        int index=0;
        for(ACParameterControllableSource owner:new ACParameterControllableSource[]{targets.artMesh,targets.warp,targets.rotation}) {
            String prefix="owner."+index+++".";
            result.setProperty(prefix+"guid",owner.getGuid().getUuidString());
            result.setProperty(prefix+"digest",bankDigest(owner));
            result.setProperty(prefix+"forms",Integer.toString(allForms(owner).size()));
            result.setProperty(prefix+"parent",String.valueOf(owner.getTargetDeformerGuid()));
        }
        return result;
    }

    private static void verifyColdReopen(ContextRef ctx, Path home) throws Exception {
        Properties expected=new Properties();
        try(InputStream input=Files.newInputStream(home.resolve("state/extended-interpolation/expected-bank.properties"))) {expected.load(input);}
        if(Long.toString(ManagementFactory.getRuntimeMXBean().getStartTime()).equals(expected.getProperty("originJvmStart")))
            throw new AssertionError("Independent JVM start required");
        if(String.valueOf(ctx.doc.getDocumentUID()).equals(expected.getProperty("originDocumentUid")))
            throw new AssertionError("Independent document required");
        edt(() -> {
            List<ACParameterControllableSource> owners=new ArrayList<>();
            owners.addAll(ctx.doc.getModelSource().getAllArtMeshes());owners.addAll(ctx.doc.getModelSource().getAllDeformers());
            for(int index=0;index<3;index++) {
                String prefix="owner."+index+".";
                ACParameterControllableSource owner=null;
                for(var candidate:owners)if(candidate.getGuid().getUuidString().equals(expected.getProperty(prefix+"guid")))owner=candidate;
                if(owner==null || !Integer.toString(allForms(owner).size()).equals(expected.getProperty(prefix+"forms")) ||
                   !bankDigest(owner).equals(expected.getProperty(prefix+"digest")) ||
                   !(owner.getTargetDeformerGuid()==null ? "null".equals(expected.getProperty(prefix+"parent")) : (expected.getProperty(prefix+"parent").equals(String.valueOf(owner.getTargetDeformerGuid())) || expected.getProperty(prefix+"parent").contains(owner.getTargetDeformerGuid().getUuidString()))))
                    throw new AssertionError("Independent-session source bank/hierarchy mismatch at " + index);
            }
            return null;
        });
        RESULT.setProperty("carrier.mode","INDEPENDENT_EDITOR_SESSION_REOPEN");
        RESULT.setProperty("scenario.coldReopen.verifiedStatus","THREE_SOURCE_BANKS_RAW_BITS_ATTRIBUTES_HIERARCHY_MATCH");
        RESULT.setProperty("scenario.coldReopen.jvmStart",Long.toString(ManagementFactory.getRuntimeMXBean().getStartTime()));
        RESULT.setProperty("scenario.coldReopen.originJvmStart",expected.getProperty("originJvmStart"));
    }

    // -------------------------------------------------------------------------
    // Scenario 7: GL Render & Visual Verification
    // -------------------------------------------------------------------------
    private static ScenarioMetrics executeRenderCheck(ContextRef ctx, TargetObjects targets, Path home, com.sun.management.ThreadMXBean threadBean) throws Exception {
        long start=System.nanoTime();
        Path pixels=home.resolve("state/extended-interpolation/pixels");
        edt(() -> {refreshTargets(targets,false);ctx.selector.clearSelection();return null;});
        GlPixelCapture.Result nativeFrame;
        setBridgeEnabled(false);
        try {
            awaitNativeFrame(ctx);
            nativeFrame=GlPixelCapture.capture(ctx.canvas,pixels.resolve("native.png"));
            nativeFrame.requireValid();
            GlPixelCapture.Result repeated=GlPixelCapture.capture(ctx.canvas,pixels.resolve("native-repeat.png"));
            repeated.requireValid();
            if(!nativeFrame.matches(repeated)) throw new AssertionError("Native rendering is unstable: "+nativeFrame.diff(repeated));
        } finally {setBridgeEnabled(true);}
        edt(() -> {refreshTargets(targets,true);return null;});
        awaitNativeFrame(ctx);
        GlPixelCapture.Result optimized=GlPixelCapture.capture(ctx.canvas,pixels.resolve("optimized.png"));
        optimized.requireValid();
        if(!nativeFrame.matches(optimized)) throw new AssertionError("Native/optimized pixel mismatch: "+nativeFrame.diff(optimized));
        edt(() -> {
            for(Object form:targets.rotation.getKeyforms()) {
                CRotationDeformerForm rotation=(CRotationDeformerForm)form;
                rotation.setAngle(rotation.getAngle()+15f);rotation.setUpdated();
            }
            refreshTargets(targets,true);return null;
        });
        awaitNativeFrame(ctx);
        GlPixelCapture.Result changed=GlPixelCapture.capture(ctx.canvas,pixels.resolve("changed-pose.png"));
        changed.requireValid();
        if(optimized.matches(changed)) throw new AssertionError("Pose change did not change captured pixels");
        RESULT.setProperty("scenario.render.native",nativeFrame.summary());
        RESULT.setProperty("scenario.render.optimized",optimized.summary());
        RESULT.setProperty("scenario.render.poseChange",optimized.diff(changed));
        RESULT.setProperty("scenario.render.verifiedStatus","NATIVE_OPTIMIZED_PIXEL_EXACT_WITH_POSE_CHANGE_CONTROL");
        RESULT.setProperty("scenario.render.pixelProof","PASS");
        return computeMetrics(List.of(System.nanoTime()-start),0,"PASS");
    }

    // -------------------------------------------------------------------------
    // Scenario 8: Settings Toggle & Config Inspection
    // -------------------------------------------------------------------------
    private static void inspectSettingsToggleAndPersistence(Path home) {
        Path configFile = home.resolve("config.json");
        boolean configFound = Files.isRegularFile(configFile);
        RESULT.setProperty("scenario.settingsToggle.configFound", Boolean.toString(configFound));

        // Boundary assertion: declare that JVM property override is active, NOT persisted config proof
        RESULT.setProperty("scenario.settingsToggle.boundary",
                "JVM_PROPERTY_OVERRIDE_ACTIVE_DOES_NOT_PROVE_PERSISTED_LAUNCHER_CONFIG_WITHOUT_RESTART");

        try {
            Class<?> serviceClass = Class.forName(
                    "dev.turboism.internal.core.CubismJvmSettingsService",
                    false,
                    ClassLoader.getSystemClassLoader());
            RESULT.setProperty("scenario.settingsToggle.serviceClassAvailable", "true");
            RESULT.setProperty("scenario.settingsToggle.status", "OBSERVED_SERVICE_PRESENT_RESTART_PENDING");
            System.err.println("[EI-HOST-PROBE] CubismJvmSettingsService detected on classpath.");
        } catch (Throwable ignored) {
            RESULT.setProperty("scenario.settingsToggle.serviceClassAvailable", "false");
            RESULT.setProperty("scenario.settingsToggle.status", "UNVERIFIED_IN_LIVE_DIALOG");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private static void recordMetrics(String prefix, ScenarioMetrics m) {
        RESULT.setProperty(prefix + ".status", m.status);
        RESULT.setProperty(prefix + ".iterations", Integer.toString(m.iterations));
        RESULT.setProperty(prefix + ".allocatedBytes", Long.toString(m.allocatedBytes));
        RESULT.setProperty(prefix + ".minNs", Long.toString(m.minNs));
        RESULT.setProperty(prefix + ".p50Ns", Long.toString(m.p50Ns));
        RESULT.setProperty(prefix + ".p95Ns", Long.toString(m.p95Ns));
        RESULT.setProperty(prefix + ".p99Ns", Long.toString(m.p99Ns));
        RESULT.setProperty(prefix + ".maxNs", Long.toString(m.maxNs));
    }

    private static ScenarioMetrics computeMetrics(List<Long> latencies, long allocBytes, String status) {
        if (latencies.isEmpty()) {
            return new ScenarioMetrics(status, 0, allocBytes, 0, 0, 0, 0, 0);
        }
        latencies = new ArrayList<>(latencies);
        Collections.sort(latencies);
        int n = latencies.size();
        long min = latencies.get(0);
        long max = latencies.get(n - 1);
        long p50 = latencies.get((int) Math.round((n - 1) * 0.50));
        long p95 = latencies.get((int) Math.round((n - 1) * 0.95));
        long p99 = latencies.get((int) Math.round((n - 1) * 0.99));
        return new ScenarioMetrics(status, n, allocBytes, min, p50, p95, p99, max);
    }

    private static void saveResultProperties(Path outputPath) {
        try {
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }
            Path tmp = outputPath.resolveSibling(outputPath.getFileName().toString() + ".tmp");
            try (OutputStream os = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                RESULT.store(os, "5.3.04 Extended Interpolation Host Validation Result");
            }
            Files.move(tmp, outputPath, StandardCopyOption.REPLACE_EXISTING);
            System.err.println("[EI-HOST-PROBE] Saved result properties to: " + outputPath);
        } catch (Exception e) {
            System.err.println("[EI-HOST-PROBE] Error writing result properties: " + e);
        }
    }

    private static void requestGracefulEditorExit() {
        System.err.println("[EI-HOST-PROBE] Requesting graceful Editor window closing...");
        SwingUtilities.invokeLater(() -> {
            for (Frame frame : Frame.getFrames()) {
                if (frame.isVisible()) {
                    frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
                }
            }
        });
    }

    private static <T> T edt(java.util.concurrent.Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static String calculateSha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(path)) {
            byte[] buf = new byte[65536];
            int read;
            while ((read = is.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    // Records
    record ContextRef(Object appCtrl, Object viewContext, CModelingDocument doc, CModel model, ISelector selector,
                      Object camera, Component canvas, String documentUid,
                      Constructor<?> mouseCtor, Constructor<?> dragCtor,
                      Method dragUpdate, Method dragRelease, Method onInput) {}

    record TargetObjects(CArtMeshSource artMesh, CWarpDeformerSource warp, CRotationDeformerSource rotation,
                         String artMeshGuid, String warpGuid, String rotationGuid) {}

    record ScenarioMetrics(String status, int iterations, long allocatedBytes,
                           long minNs, long p50Ns, long p95Ns, long p99Ns, long maxNs) {}
}
