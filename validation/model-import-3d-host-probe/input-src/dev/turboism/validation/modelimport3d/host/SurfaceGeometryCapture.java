package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.function.IntConsumer;
import javax.swing.SwingUtilities;

/** Native evaluation observer for an already imported surface. Does not author forms.
 * Caller owns import/history cleanup. Independent report verification remains required.
 */
final class SurfaceGeometryCapture {
    private SurfaceGeometryCapture() {}
    static List<SurfaceProjection.Pose> poses(SurfaceImportPlan.Plan plan) {
        var yaw=subdivide(SurfaceKeyformBake.bindingKeys(plan.grid().yawKeys()));
        var pitch=subdivide(SurfaceKeyformBake.bindingKeys(plan.grid().pitchKeys()));
        var result=new LinkedHashSet<SurfaceProjection.Pose>();
        for(float y:yaw)for(float p:pitch)result.add(new SurfaceProjection.Pose(y,p));
        var random=new Random(0x3344494dL);
        float y0=yaw.get(0),y1=yaw.get(yaw.size()-1),p0=pitch.get(0),p1=pitch.get(pitch.size()-1);
        for(int i=0;i<512;i++)result.add(new SurfaceProjection.Pose(y0+(y1-y0)*random.nextFloat(),p0+(p1-p0)*random.nextFloat()));
        return List.copyOf(result);
    }
    private static List<Float> subdivide(List<Float> keys) {
        var result=new ArrayList<Float>();
        for(int i=0;i<keys.size();i++) {
            result.add(keys.get(i));
            if(i+1<keys.size())result.add((keys.get(i)+keys.get(i+1))/2);
        }
        return result;
    }
    static PoseDriveHistory.Session run(SurfaceImport.Prepared prepared,SurfaceImport.Imported imported,
            Supplier<CubismModel> current,Supplier<HistorySnapshot> history,IntConsumer restore,Map<String,Object> report)throws Exception {
        NativeAssertions.require(!SwingUtilities.isEventDispatchThread(),"surface evaluation cannot wait on EDT");
        NativeAssertions.require(prepared.inputSha256.equals(imported.inputSha256()),"capture source identity");
        NativeAssertions.require(edt(()->current.get().canvas().widthPixels()==1024
            && current.get().canvas().heightPixels()==1024),"capture requires 1024 canvas");
        var patches=imported.geometry().patches();
        NativeAssertions.require(patches.size()==prepared.geometry.patches().size(),"capture patch count");
        for(int i=0;i<patches.size();i++)NativeAssertions.require(
            patches.get(i).source().equals(prepared.geometry.patches().get(i).source()),"capture source correspondence");
        var yaw=prepared.request.yaw();var pitch=prepared.request.pitch();
        var saved=edt(()->List.of(current.get().parameters().find(yaw).getValue(),current.get().parameters().find(pitch).getValue()));
        var before=edt(()->patches.stream().map(p->current.get().drawables().find(p.meshId()).authoringSnapshot()).toList());
        var root=edt(history::get);
        NativeAssertions.require(root.availability()==HistorySnapshot.Availability.AVAILABLE,"capture history unavailable");
        var driver=new PoseDriveHistory.Session(root);
        var samples=new ArrayList<Map<String,Object>>();report.put("samples",samples);
        report.put("inputSha256",prepared.inputSha256);report.put("status","RUNNING");
        report.put("historyProtocol","POSE_DRIVER_UNDO_RETURN_V2");
        report.put("scope","NATIVE_SURFACE_GEOMETRY_ONLY");report.put("maxErrorPixels",.1);
        report.put("before",before.stream().map(NativeAssertions::snapshot).toList());
        report.put("historyBefore",historyData(root));report.put("liveBefore",saved);
        var inventory=poses(prepared.geometry);report.put("expectedSamples",inventory.size());
        Throwable primary=null;
        try {
            for(var pose:inventory) {
                var row=new LinkedHashMap<String,Object>();samples.add(row);
                row.put("pose",List.of(pose.yaw(),pose.pitch()));row.put("status","RUNNING");
                var historyBeforeDrive=edt(history::get);
                row.put("historyBeforeDrive",driveHistory(historyBeforeDrive));
                boolean roundTrip=(float)pose.yaw()==saved.get(0) && (float)pose.pitch()==saved.get(1);
                float firstYaw=roundTrip ? SurfaceKeyformBake.bindingKeys(prepared.geometry.grid().yawKeys()).stream()
                    .filter(k->k.floatValue()!=saved.get(0)).findFirst().orElseThrow() : (float)pose.yaw();
                var writes=List.of(new PoseDriveHistory.Write(yaw.value(),saved.get(0),firstYaw),
                    new PoseDriveHistory.Write(pitch.value(),saved.get(1),(float)pose.pitch()));
                boolean admitted=false;
                Throwable poseFailure=null;
                try {
                var requested=edt(()->{
                    driver.ready(history.get());
                    current.get().parameters().find(yaw).setValue(firstYaw);
                    current.get().parameters().find(pitch).setValue((float)pose.pitch());
                    return patches.stream().map(p->current.get().drawables().find(p.meshId()).evaluationSnapshot()).toList();
                });
                var historyDriven=edt(history::get);
                driver.driven(historyBeforeDrive,historyDriven,writes);admitted=true;
                if(roundTrip) {
                    var priming=new LinkedHashMap<String,Object>();row.put("priming",priming);
                    priming.put("pose",List.of(firstYaw,pose.pitch()));
                    priming.put("historyDriven",driveHistory(historyDriven));
                    var primeMeshes=new ArrayList<Map<String,Object>>();priming.put("meshes",primeMeshes);
                    for(int i=0;i<patches.size();i++) {
                        var patch=patches.get(i);var detail=new LinkedHashMap<String,Object>();primeMeshes.add(detail);
                        detail.put("requested",EvaluationProbe.evaluation(requested.get(i)));
                        var actual=EvaluationProbe.awaitConsumed(requested.get(i),
                            ()->edt(()->current.get().drawables().find(patch.meshId()).evaluationSnapshot()),
                            detail::putAll,System::nanoTime,Thread::sleep);
                        detail.put("actual",EvaluationProbe.evaluation(actual));
                        NativeAssertions.require(actual.artMeshId().equals(before.get(i).artMeshId())
                            && actual.sourceGuid().equals(before.get(i).sourceGuid()),"priming mesh identity changed");
                        for(var axis:List.of(yaw,pitch)) {
                            var values=actual.liveParameters().parameters().stream().filter(p->p.parameterId().equals(axis)).toList();
                            NativeAssertions.require(values.size()==1 && values.get(0).value()==(axis.equals(yaw)?firstYaw:(float)pose.pitch()),
                                "priming parameter clamped/changed");
                        }
                    }
                    var primeObserved=edt(history::get);driver.observed(primeObserved);
                    priming.put("historyObserved",driveHistory(primeObserved));
                    requested=edt(()->{
                        driver.observed(history.get());
                        current.get().parameters().find(yaw).setValue((float)pose.yaw());
                        return patches.stream().map(p->current.get().drawables().find(p.meshId()).evaluationSnapshot()).toList();
                    });
                    historyDriven=edt(history::get);driver.returned(primeObserved,historyDriven);
                }
                row.put("historyDriven",driveHistory(historyDriven));
                var observations=new ArrayList<Map<String,Object>>();row.put("meshes",observations);
                for(int i=0;i<patches.size();i++) {
                    var patch=patches.get(i);var detail=new LinkedHashMap<String,Object>();observations.add(detail);
                    NativeAssertions.require(requested.get(i).artMeshId().equals(before.get(i).artMeshId())
                        && requested.get(i).sourceGuid().equals(before.get(i).sourceGuid()),"capture mesh identity changed");
                    detail.put("requested",EvaluationProbe.evaluation(requested.get(i)));
                    var actual=EvaluationProbe.awaitConsumed(requested.get(i),
                        ()->edt(()->current.get().drawables().find(patch.meshId()).evaluationSnapshot()),
                        detail::putAll,System::nanoTime,Thread::sleep);
                    detail.put("actual",EvaluationProbe.evaluation(actual));
                    var expected=SurfaceProjection.project(patch.source().positions(),prepared.request.camera(),pose).positions();
                    double error=EvaluationProbe.maxError(actual,expected);detail.put("maxErrorPixels",error);
                    NativeAssertions.require(error>=0 && error<=.1 && actual.calculatedForm().filter(f->f.interpolated()
                        && List.of("Canvas","DeformerLocal").contains(f.coordinateType())).isPresent(),"native surface projection error");
                    for(var axis:List.of(yaw,pitch)) {
                        var values=actual.liveParameters().parameters().stream().filter(p->p.parameterId().equals(axis)).toList();
                        NativeAssertions.require(values.size()==1 && values.get(0).value()==(float)(axis.equals(yaw)?pose.yaw():pose.pitch()),
                            "surface parameter clamped/changed");
                    }
                    // Record orders for separate acceptance; geometry alone never proves occlusion.
                }
                var observedHistory=edt(history::get);row.put("historyAfter",driveHistory(observedHistory));
                driver.observed(observedHistory);
                row.put("status","PASS");
                } catch(Exception | Error failure) {poseFailure=failure;throw failure;}
                finally {
                    try {
                        edt(()->{restore.accept(root.position());return null;});
                        var restoredHistory=edt(history::get);row.put("historyRestored",driveHistory(restoredHistory));
                        NativeAssertions.require(admitted,"pose driver was not admitted; recovery requires outer cleanup");
                        driver.restored(restoredHistory);
                        var live=edt(()->List.of(current.get().parameters().find(yaw).getValue(),current.get().parameters().find(pitch).getValue()));
                        row.put("liveRestored",live);
                        NativeAssertions.require(live.equals(saved),"pose undo did not restore live axes");
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
                var after=edt(()->patches.stream().map(p->current.get().drawables().find(p.meshId()).authoringSnapshot()).toList());
                report.put("after",after.stream().map(NativeAssertions::snapshot).toList());
                report.put("historyAfter",historyData(edt(history::get)));
                NativeAssertions.require(before.equals(after) && edt(history::get).position()==root.position()
                    && edt(history::get).entries().subList(0,root.position()).equals(root.entries()),"surface capture changed authored state/history");
                var restored=edt(()->List.of(current.get().parameters().find(yaw).getValue(),current.get().parameters().find(pitch).getValue()));
                report.put("liveAfter",restored);
                NativeAssertions.require(restored.equals(saved),"surface live parameter restoration");
                report.put("restored",true);
            } catch(Exception | Error failure) {
                report.put("restored",false);report.put("status","FAIL");report.put("recoveryFailure",failure.toString());
                if(primary!=null)primary.addSuppressed(failure);else throw failure;
            }
        }
        report.put("status","PASS");
        driver.ready(edt(history::get));
        return driver;
    }
    static Map<String,Object> driveHistory(HistorySnapshot h) {
        var row=new LinkedHashMap<String,Object>();
        row.put("availability",h.availability().name());row.put("generation",h.generation());row.put("revision",h.revision());
        row.put("position",h.position());row.put("documentBindingId",h.documentBindingId());row.put("managerBindingId",h.managerBindingId());
        row.put("canUndo",h.canUndo());row.put("canRedo",h.canRedo());
        row.put("entries",h.entries().stream().map(e->{
            var entry=new LinkedHashMap<String,Object>();entry.put("index",e.index());entry.put("significant",e.significant());
            entry.put("entryId",e.entryId().map(Object::toString).orElse(""));entry.put("transactionId",e.transactionId().orElse(""));
            try {entry.put("sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(e.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));}
            catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
            entry.put("action",e.action().map(a->Map.of("kind",a.kind().name(),"targetType",a.targetType(),
                "targetId",a.targetId(),"property",a.property(),"before",a.before().orElse(""),
                "after",a.after().orElse(""),"detailLevel",a.detailLevel().name())).orElse(null));return entry;
        }).toList());return row;
    }
    static Map<String,Object> historyData(HistorySnapshot h) {
        var row=new LinkedHashMap<String,Object>();
        row.put("availability",h.availability().name());row.put("generation",h.generation());row.put("revision",h.revision());
        row.put("position",h.position());row.put("documentBindingId",h.documentBindingId());row.put("managerBindingId",h.managerBindingId());
        row.put("entries",h.entries().stream().map(Object::toString).toList());
        row.put("entryIds",h.entries().stream().map(e->e.entryId().map(Object::toString).orElse("")).toList());
        row.put("canUndo",h.canUndo());row.put("canRedo",h.canRedo());return row;
    }
    private static <T>T edt(Callable<T> action)throws Exception {
        if(SwingUtilities.isEventDispatchThread())return action.call();
        var task=new FutureTask<T>(action);SwingUtilities.invokeAndWait(task);
        try{return task.get();}catch(ExecutionException e){
            if(e.getCause() instanceof Exception x)throw x;
            if(e.getCause() instanceof Error x)throw x;
            throw e;
        }
    }
}
