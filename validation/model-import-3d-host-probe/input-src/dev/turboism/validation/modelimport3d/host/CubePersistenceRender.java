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
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Samples already-bound faces; never rebinds materials or changes authored geometry/culling. */
final class CubePersistenceRender {
    private CubePersistenceRender() { }
    static List<Map<String,Object>> sample(String phase, SurfaceImport.Prepared prepared,
            Supplier<CubismModel> current, Supplier<HistorySnapshot> history, java.util.function.IntConsumer restore, Path state, Map<String,Object> report) throws Exception {
        NativeAssertions.require(!SwingUtilities.isEventDispatchThread(), "render wait must run off EDT");
        NativeAssertions.require(Set.of("before-save","after-save","reopen","atlas-baseline","atlas-packed","atlas-repacked","atlas-redone",
            "atlas-before-save","atlas-after-save","atlas-reopen").contains(phase), "render phase");
        CubePersistenceInput.identify(prepared.inputSha256);
        Path dir=state.resolve("cube-persistence-render");
        for(Path p=dir.toAbsolutePath();p!=null;p=p.getParent())
            NativeAssertions.require(!Files.isSymbolicLink(p), "linked render path");
        if(!Files.exists(dir,LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(dir);
        NativeAssertions.require(Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS), "render directory");
        var ids=edt(()->current.get().drawables().all().stream().map(Drawable::id).toList());
        NativeAssertions.require(ids.size()==6 && prepared.geometry.patches().size()==6, "six render faces");
        var entry=edt(()->WholeImportProbe.persistenceState(current.get()));
        var entryHistory=edt(history::get);
        var entryPose=edt(()->new SurfaceProjection.Pose(current.get().parameters().find(CubeGeometryProbe.YAW).getValue(),
            current.get().parameters().find(CubeGeometryProbe.PITCH).getValue()));
        var orders=edt(()->ids.stream().map(id->current.get().drawables().find(id).authoringSnapshot().forms().get(0).drawOrder()).toList());
        var frames=new ArrayList<Map<String,Object>>(); Throwable primary=null;
        var progress=new LinkedHashMap<String,Object>();report.put("renderProgress-"+phase,progress);
        progress.put("entryState",entry);progress.put("entryHistory",WholeImportProbe.historyData(entryHistory));
        progress.put("completedFrames",frames);progress.put("entryPose",List.of(entryPose.yaw(),entryPose.pitch()));
        try {
            // The first requested pose is neutral, often already consumed after import/SAVE/reopen.
            // Force and consume a different live pose first; keep the strict fresh-pass contract.
            NativeAssertions.require(entryPose.yaw()==0 && entryPose.pitch()==0, "persistence sweep starts neutral");
            progress.put("stage","prime");
            var primed=edt(()->{
                progress.put("beforePrime",poseAndHistory(current.get(),history.get()));
                current.get().parameters().find(CubeGeometryProbe.YAW).setValue(-2);
                progress.put("afterPrimeYaw",poseAndHistory(current.get(),history.get()));
                current.get().parameters().find(CubeGeometryProbe.PITCH).setValue(-2);
                progress.put("afterPrimePitch",poseAndHistory(current.get(),history.get()));
                return ids.stream().map(id->current.get().drawables().find(id).evaluationSnapshot()).toList();
            });
            var priming=new ArrayList<Map<String,Object>>();progress.put("priming",priming);
            for(int i=0;i<6;i++) {
                var id=ids.get(i);var row=new LinkedHashMap<String,Object>();priming.add(row);
                row.put("requested",EvaluationProbe.evaluation(primed.get(i)));
                var actual=EvaluationProbe.awaitConsumed(primed.get(i),
                    ()->edt(()->current.get().drawables().find(id).evaluationSnapshot()),row::putAll,System::nanoTime,Thread::sleep);
                row.put("actual",EvaluationProbe.evaluation(actual));
            }
            progress.put("stage","frames");
            for(int index=0;index<CubeRenderProbe.POSES.size();index++) {
                var pose=CubeRenderProbe.POSES.get(index);
                var requested=edt(()->{
                    current.get().parameters().find(CubeGeometryProbe.YAW).setValue((float)pose.yaw());
                    current.get().parameters().find(CubeGeometryProbe.PITCH).setValue((float)pose.pitch());
                    return ids.stream().map(id->current.get().drawables().find(id).evaluationSnapshot()).toList();
                });
                var consumption=new ArrayList<Map<String,Object>>();
                progress.put("activePose",List.of(pose.yaw(),pose.pitch()));progress.put("activeConsumption",consumption);
                for(int i=0;i<6;i++) {
                    var id=ids.get(i);var row=new LinkedHashMap<String,Object>();consumption.add(row);
                    row.put("requested",EvaluationProbe.evaluation(requested.get(i)));
                    var actual=EvaluationProbe.awaitConsumed(requested.get(i),
                        ()->edt(()->current.get().drawables().find(id).evaluationSnapshot()),row::putAll,System::nanoTime,Thread::sleep);
                    row.put("actual",EvaluationProbe.evaluation(actual));
                    var expected=SurfaceProjection.project(prepared.geometry.patches().get(i).source().positions(),
                        CubeGeometryProbe.CAMERA,pose).positions();
                    NativeAssertions.require(EvaluationProbe.validGeometry(actual,expected,orders.get(i)), "persisted render geometry outside 0.1px");
                }
                final int number=index;
                frames.add(edt(()->{
                    var before=WholeImportProbe.persistenceState(current.get());var h0=history.get();
                    var evalBefore=ids.stream().map(id->EvaluationProbe.evaluation(current.get().drawables().find(id).evaluationSnapshot())).toList();
                    var pixels=current.get().renderPixels(1024,1024);
                    var after=WholeImportProbe.persistenceState(current.get());var h1=history.get();
                    var evalAfter=ids.stream().map(id->EvaluationProbe.evaluation(current.get().drawables().find(id).evaluationSnapshot())).toList();
                    NativeAssertions.require(before.equals(after) && sameHistory(h0,h1), "render mutated authored state/history");
                    NativeAssertions.require(pixels.width()==1024 && pixels.height()==1024, "render dimensions");
                    var image=new BufferedImage(1024,1024,BufferedImage.TYPE_INT_ARGB);
                    image.setRGB(0,0,1024,1024,pixels.argb(),0,1024);
                    var output=new ByteArrayOutputStream();NativeAssertions.require(ImageIO.write(image,"PNG",output), "PNG encoder");
                    var bytes=output.toByteArray();NativeAssertions.require(bytes.length<=8*1024*1024,"PNG budget");
                    String name=phase+"-"+number+".png";
                    Files.write(dir.resolve(name),bytes,StandardOpenOption.CREATE_NEW);
                    var row=new LinkedHashMap<String,Object>();row.put("phase",phase);row.put("index",number);
                    row.put("pose",List.of(pose.yaw(),pose.pitch()));row.put("stateBefore",before);row.put("stateAfter",after);
                    row.put("historyBefore",WholeImportProbe.historyData(h0));row.put("historyAfter",WholeImportProbe.historyData(h1));
                    row.put("evaluationBefore",evalBefore);row.put("evaluationAfter",evalAfter);
                    row.put("consumption",consumption);row.put("artifact","cube-persistence-render/"+name);
                    row.put("artifactBytes",bytes.length);row.put("artifactSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                    return row;
                }));
            }
            progress.put("stage","complete");
            return List.copyOf(frames);
        } catch(Exception | Error failure) { primary=failure;throw failure; }
        finally {
            try {
                var requested=edt(()->{
                    progress.put("beforeRestore",poseAndHistory(current.get(),history.get()));
                    restore.accept(entryHistory.position());
                    progress.put("afterHistoryRestore",poseAndHistory(current.get(),history.get()));
                    if(current.get().parameters().find(CubeGeometryProbe.YAW).getValue()!=(float)entryPose.yaw())
                        current.get().parameters().find(CubeGeometryProbe.YAW).setValue((float)entryPose.yaw());
                    progress.put("afterRestoreYaw",poseAndHistory(current.get(),history.get()));
                    if(current.get().parameters().find(CubeGeometryProbe.PITCH).getValue()!=(float)entryPose.pitch())
                        current.get().parameters().find(CubeGeometryProbe.PITCH).setValue((float)entryPose.pitch());
                    progress.put("afterRestorePitch",poseAndHistory(current.get(),history.get()));
                    return ids.stream().map(id->current.get().drawables().find(id).evaluationSnapshot()).toList();
                });
                for(int i=0;i<6;i++) {
                    var id=ids.get(i);
                    if(!CubeRenderProbe.currentParametersConsumed(requested.get(i)))
                        EvaluationProbe.awaitConsumed(requested.get(i),()->edt(()->current.get().drawables().find(id).evaluationSnapshot()),
                            ignored->{},System::nanoTime,Thread::sleep);
                }
                var restored=edt(()->WholeImportProbe.persistenceState(current.get()));var restoredHistory=edt(history::get);
                progress.put("restoredState",restored);progress.put("restoredHistory",WholeImportProbe.historyData(restoredHistory));
                progress.put("afterRestoreConsumption",edt(()->poseAndHistory(current.get(),history.get())));
                NativeAssertions.require(entry.equals(restored) && restoredHistory(entryHistory,restoredHistory),
                    "render sweep failed to restore entry state/history");
            } catch(Exception | Error failure) { if(primary!=null)primary.addSuppressed(failure);else throw failure; }
        }
    }
    private static Map<String,Object> poseAndHistory(CubismModel model,HistorySnapshot history) {
        return Map.of("pose",List.of(model.parameters().find(CubeGeometryProbe.YAW).getValue(),
            model.parameters().find(CubeGeometryProbe.PITCH).getValue()),"history",WholeImportProbe.historyData(history));
    }
    static boolean restoredHistory(HistorySnapshot before,HistorySnapshot after) {
        var a=new LinkedHashMap<>(WholeImportProbe.historyData(before));var b=new LinkedHashMap<>(WholeImportProbe.historyData(after));
        a.remove("revision");b.remove("revision");a.remove("entryIds");b.remove("entryIds");
        // Native Undo retains a redo tail. Protect the active prefix rather than deleting native history.
        return before.availability()==HistorySnapshot.Availability.AVAILABLE && !before.documentBindingId().isEmpty()
            && a.equals(b) && after.revision()>=before.revision()
            && before.entries().subList(0,before.position()).stream().allMatch(e->e.entryId().isPresent())
            && before.entries().size()>=before.position() && after.entries().size()>=before.position()
            && before.entries().subList(0,before.position()).stream().map(e->e.entryId()).toList()
                .equals(after.entries().subList(0,before.position()).stream().map(e->e.entryId()).toList());
    }
    static boolean sameHistory(HistorySnapshot before,HistorySnapshot after) {
        var a=new LinkedHashMap<>(WholeImportProbe.historyData(before));var b=new LinkedHashMap<>(WholeImportProbe.historyData(after));
        a.remove("revision");b.remove("revision");return a.equals(b) && after.revision()>=before.revision();
    }
    private static <T>T edt(java.util.concurrent.Callable<T> action)throws Exception {
        var task=new FutureTask<T>(action);SwingUtilities.invokeAndWait(task);return task.get();
    }
}
