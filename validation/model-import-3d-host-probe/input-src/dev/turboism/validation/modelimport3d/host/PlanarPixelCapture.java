package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.model.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.function.IntConsumer;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Captures source-material pixels on imported textured planes without changing authored properties. */
final class PlanarPixelCapture {
    static final List<SurfaceProjection.Pose> POSES=List.of(
        new SurfaceProjection.Pose(-30,-30),new SurfaceProjection.Pose(-30,0),new SurfaceProjection.Pose(-30,30),
        new SurfaceProjection.Pose(0,-30),new SurfaceProjection.Pose(0,0),new SurfaceProjection.Pose(0,30),
        new SurfaceProjection.Pose(30,-30),new SurfaceProjection.Pose(30,0),new SurfaceProjection.Pose(30,30));
    static void run(SurfaceImport.Prepared prepared,SurfaceImport.Imported imported,Supplier<CubismModel> current,
            Supplier<HistorySnapshot> history,IntConsumer restore,PoseDriveHistory.Session driver,Path state,Map<String,Object> report)throws Exception {
        NativeAssertions.require(!SwingUtilities.isEventDispatchThread(),"planar pixel wait must run off EDT");
        int expectedMeshes=expectedMeshCount(prepared.inputSha256);
        NativeAssertions.require(prepared.inputSha256.equals(imported.inputSha256()) && prepared.sheetOrders==null,
            "planar pixel source identity");
        var patches=imported.geometry().patches();
        NativeAssertions.require(patches.size()==expectedMeshes && prepared.geometry.patches().size()==expectedMeshes,
            "planar pixel patch count");
        for(int i=0;i<patches.size();i++)NativeAssertions.require(
            patches.get(i).source().equals(prepared.geometry.patches().get(i).source()),"pixel source mapping");
        var entry=edt(()->WholeImportProbe.state(current.get()));var incoming=edt(history::get);
        driver.ready(incoming);var root=driver.rootSnapshot();
        var saved=edt(()->List.of(current.get().parameters().find(imported.yaw()).getValue(),current.get().parameters().find(imported.pitch()).getValue()));
        NativeAssertions.require(saved.equals(List.of(0f,0f)),"planar pixel sweep starts neutral");
        NativeAssertions.require(edt(()->current.get().drawables().all().size()==expectedMeshes && current.get().canvas().widthPixels()==1024
            && current.get().canvas().heightPixels()==1024),"isolated planar pixel canvas");
        for(var patch:patches)NativeAssertions.require(edt(()->current.get().drawables().find(patch.meshId()).culling()),"planar culling state");
        Path dir=state.resolve("planar-render").toAbsolutePath();
        for(Path p=dir;p!=null;p=p.getParent())NativeAssertions.require(!Files.isSymbolicLink(p),"linked planar render path");
        Files.createDirectory(dir); // Fresh namespace; never replace an earlier capture.
        var frames=new ArrayList<Map<String,Object>>();report.put("frames",frames);report.put("status","RUNNING");
        report.put("inputSha256",prepared.inputSha256);report.put("entryState",entry);report.put("entryHistory",WholeImportProbe.historyData(incoming));
        report.put("historyProtocol","POSE_DRIVER_UNDO_RETURN_V2");
        report.put("driverRoot",SurfaceGeometryCapture.driveHistory(root));report.put("liveBefore",saved);
        report.put("poses",POSES.stream().map(p->List.of(p.yaw(),p.pitch())).toList());
        Throwable primary=null;
        try {
            for(int index=0;index<POSES.size();index++) {
                var pose=POSES.get(index);var row=new LinkedHashMap<String,Object>();frames.add(row);
                row.put("index",index);row.put("pose",List.of(pose.yaw(),pose.pitch()));row.put("status","RUNNING");
                var historyBeforeDrive=edt(history::get);row.put("historyBeforeDrive",SurfaceGeometryCapture.driveHistory(historyBeforeDrive));
                boolean roundTrip=(float)pose.yaw()==saved.get(0) && (float)pose.pitch()==saved.get(1);
                float firstYaw=roundTrip ? SurfaceKeyformBake.bindingKeys(prepared.geometry.grid().yawKeys()).stream()
                    .filter(k->k.floatValue()!=saved.get(0)).findFirst().orElseThrow() : (float)pose.yaw();
                var writes=List.of(new PoseDriveHistory.Write(imported.yaw().value(),saved.get(0),firstYaw),
                    new PoseDriveHistory.Write(imported.pitch().value(),saved.get(1),(float)pose.pitch()));
                boolean admitted=false;Throwable poseFailure=null;
                try {
                var initialRequested=edt(()->{
                    driver.ready(history.get());
                    current.get().parameters().find(imported.yaw()).setValue(firstYaw);
                    current.get().parameters().find(imported.pitch()).setValue((float)pose.pitch());
                    return patches.stream().map(p->current.get().drawables().find(p.meshId()).evaluationSnapshot()).toList();
                });
                var driven=edt(history::get);
                driver.driven(historyBeforeDrive,driven,writes);admitted=true;
                final var primeSources=initialRequested;
                if(roundTrip) {
                    var priming=new LinkedHashMap<String,Object>();row.put("priming",priming);
                    priming.put("pose",List.of(firstYaw,pose.pitch()));
                    priming.put("historyDriven",SurfaceGeometryCapture.driveHistory(driven));
                    var primeMeshes=new ArrayList<Map<String,Object>>();priming.put("meshes",primeMeshes);
                    for(int i=0;i<patches.size();i++) {
                        var patch=patches.get(i);var detail=new LinkedHashMap<String,Object>();primeMeshes.add(detail);
                        detail.put("requested",EvaluationProbe.evaluation(initialRequested.get(i)));
                        var actual=EvaluationProbe.awaitConsumed(initialRequested.get(i),
                            ()->edt(()->current.get().drawables().find(patch.meshId()).evaluationSnapshot()),
                            detail::putAll,System::nanoTime,Thread::sleep);
                        detail.put("actual",EvaluationProbe.evaluation(actual));
                        NativeAssertions.require(actual.artMeshId().equals(patch.meshId())
                            && actual.sourceGuid().equals(primeSources.get(i).sourceGuid()),"priming mesh identity changed");
                        for(var axis:List.of(imported.yaw(),imported.pitch())) {
                            var values=actual.liveParameters().parameters().stream().filter(p->p.parameterId().equals(axis)).toList();
                            NativeAssertions.require(values.size()==1 && values.get(0).value()==(axis.equals(imported.yaw())?firstYaw:(float)pose.pitch()),
                                "priming parameter clamped/changed");
                        }
                    }
                    var primeObserved=edt(history::get);driver.observed(primeObserved);
                    priming.put("historyObserved",SurfaceGeometryCapture.driveHistory(primeObserved));
                    initialRequested=edt(()->{
                        driver.observed(history.get());
                        current.get().parameters().find(imported.yaw()).setValue((float)pose.yaw());
                        return patches.stream().map(p->current.get().drawables().find(p.meshId()).evaluationSnapshot()).toList();
                    });
                    driven=edt(history::get);driver.returned(primeObserved,driven);
                }
                row.put("historyDriven",SurfaceGeometryCapture.driveHistory(driven));
                final var requested=initialRequested;
                var consumption=new ArrayList<Map<String,Object>>();row.put("consumption",consumption);
                for(int i=0;i<patches.size();i++) {
                    var patch=patches.get(i);var observed=new LinkedHashMap<String,Object>();consumption.add(observed);
                    NativeAssertions.require(requested.get(i).artMeshId().equals(patch.meshId()),"planar requested mesh identity");
                    observed.put("requested",EvaluationProbe.evaluation(requested.get(i)));
                    var actual=EvaluationProbe.awaitConsumed(requested.get(i),()->edt(()->current.get().drawables().find(patch.meshId()).evaluationSnapshot()),
                        observed::putAll,System::nanoTime,Thread::sleep);
                    observed.put("actual",EvaluationProbe.evaluation(actual));
                    validate(actual,prepared,i,pose);
                }
                var pixels=edt(()->{
                    var before=WholeImportProbe.state(current.get());var h0=history.get();
                    driver.observed(h0);
                    row.put("stateBefore",before);row.put("historyBefore",WholeImportProbe.historyData(h0));
                    var raster=current.get().renderPixels(1024,1024);
                    var after=WholeImportProbe.state(current.get());var h1=history.get();
                    row.put("stateAfter",after);row.put("historyAfter",WholeImportProbe.historyData(h1));
                    var evaluations=patches.stream().map(p->current.get().drawables().find(p.meshId()).evaluationSnapshot()).toList();
                    row.put("evaluationAfter",evaluations.stream().map(EvaluationProbe::evaluation).toList());
                    for(int i=0;i<patches.size();i++) {
                        NativeAssertions.require(evaluations.get(i).artMeshId().equals(requested.get(i).artMeshId())
                            && evaluations.get(i).sourceGuid().equals(requested.get(i).sourceGuid()),"planar rendered mesh identity");
                        validate(evaluations.get(i),prepared,i,pose);
                    }
                    NativeAssertions.require(before.equals(after) && h0.equals(h1),"pixel render mutated state/history");
                    driver.observed(h1);row.put("historyObserved",SurfaceGeometryCapture.driveHistory(h1));
                    return raster;
                });
                byte[] png=encode(pixels);String name=index+".png";
                Files.write(dir.resolve(name),png,StandardOpenOption.CREATE_NEW);
                row.put("artifact","planar-render/"+name);row.put("artifactBytes",png.length);
                row.put("artifactSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)));row.put("status","CAPTURED");
                } catch(Exception | Error failure) {poseFailure=failure;throw failure;}
                finally {
                    try {
                        edt(()->{restore.accept(root.position());return null;});
                        var restored=edt(history::get);row.put("historyRestored",SurfaceGeometryCapture.driveHistory(restored));
                        NativeAssertions.require(admitted,"pixel driver not admitted; outer cleanup required");
                        driver.restored(restored);
                        var live=edt(()->List.of(current.get().parameters().find(imported.yaw()).getValue(),current.get().parameters().find(imported.pitch()).getValue()));
                        row.put("liveRestored",live);NativeAssertions.require(saved.equals(live),"pixel pose undo live restoration");
                        row.put("undoVerified",true);
                    } catch(Exception | Error failure) {
                        row.put("undoVerified",false);row.put("recoveryFailure",failure.toString());
                        if(poseFailure!=null)poseFailure.addSuppressed(failure);else throw failure;
                    }
                }
            }
        } catch(Exception | Error failure) {
            primary=failure;report.put("status","FAIL");report.put("failure",failure.toString());throw failure;
        } finally {
            try {
                edt(()->{restore.accept(root.position());return null;});
                var restored=edt(()->WholeImportProbe.state(current.get()));var restoredHistory=edt(history::get);
                report.put("restoredState",restored);report.put("restoredHistory",WholeImportProbe.historyData(restoredHistory));
                report.put("historyFinal",SurfaceGeometryCapture.historyData(restoredHistory));
                NativeAssertions.require(entry.equals(restored) && restoredHistory.position()==root.position(),"planar pixel restoration");driver.ready(restoredHistory);
                var live=edt(()->List.of(current.get().parameters().find(imported.yaw()).getValue(),current.get().parameters().find(imported.pitch()).getValue()));
                report.put("liveAfter",live);NativeAssertions.require(saved.equals(live),"pixel final live restoration");
                report.put("finallyRestored",true);
            } catch(Exception | Error failure) {
                report.put("finallyRestored",false);report.put("status","FAIL");report.put("recoveryFailure",failure.toString());
                if(primary!=null)primary.addSuppressed(failure);else throw failure;
            }
        }
        report.put("status","CAPTURED");report.put("pixelAcceptance","REQUIRES_INDEPENDENT_ORACLE");
    }
    static int expectedMeshCount(String sha) {
        if(PlanarGeometryProbe.Profile.PLANE.sha256.equals(sha))return 1;
        if(PlanarGeometryProbe.Profile.SEAM.sha256.equals(sha))return 2;
        throw new IllegalStateException("unsupported planar pixel source");
    }
    private static void validate(ArtMeshEvaluationSnapshot actual,SurfaceImport.Prepared prepared,int surface,SurfaceProjection.Pose pose) {
        NativeAssertions.require(CubeRenderProbe.currentParametersConsumed(actual),"planar render consumed parameters");
        var expected=SurfaceProjection.project(prepared.geometry.patches().get(surface).source().positions(),prepared.request.camera(),pose).positions();
        NativeAssertions.require(EvaluationProbe.validGeometry(actual,expected,500),"planar render geometry/order");
        for(var axis:List.of(prepared.request.yaw(),prepared.request.pitch())) {
            var values=actual.liveParameters().parameters().stream().filter(p->p.parameterId().equals(axis)).toList();
            NativeAssertions.require(values.size()==1 && values.get(0).value()==(float)(axis.equals(prepared.request.yaw())?pose.yaw():pose.pitch()),"planar render pose changed");
        }
    }
    static byte[] encode(CubismRenderedPixels pixels)throws Exception {
        NativeAssertions.require(pixels.width()==1024 && pixels.height()==1024,"planar pixel dimensions");
        var image=new BufferedImage(1024,1024,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,1024,1024,pixels.argb(),0,1024);
        var output=new ByteArrayOutputStream();NativeAssertions.require(ImageIO.write(image,"PNG",output),"planar PNG encoder");
        byte[] png=output.toByteArray();NativeAssertions.require(png.length<=8*1024*1024,"planar PNG budget");return png;
    }
    private static <T>T edt(Callable<T> action)throws Exception {
        var task=new FutureTask<T>(action);SwingUtilities.invokeAndWait(task);
        try{return task.get();}catch(ExecutionException e){if(e.getCause() instanceof Exception x)throw x;if(e.getCause() instanceof Error x)throw x;throw e;}
    }
}
