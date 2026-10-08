package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.SwingUtilities;

/** Executes the real prepared importer. Independent verification decides acceptance. */
final class WholeImportProbe {
    private WholeImportProbe() { }
    static void run(byte[] glb, Supplier<CubismModel> current, Supplier<HistorySnapshot> history,
            IntConsumer restore, AuthoringTransactionService transactions, Map<String,Object> data) throws Exception {
        NativeAssertions.require(!SwingUtilities.isEventDispatchThread(),"whole import probe must not block EDT");
        var entry=edt(history::get);
        var original=edt(()->state(current.get()));
        Throwable primary=null;
        try {
            // Earlier gates deliberately undo their meshes. Establish a separate native
            // edit before the importer so its rollback must preserve authored geometry.
            var seeded=edt(()->transactions.execute(AuthoringTransactionOptions.of("MI3D protected mesh"),()->
                current.get().drawables().create("MI3D_PROTECTED",null,-1,new ArtMeshGeometry(
                    List.of(new Point2(10,20),new Point2(40,20),new Point2(10,50)),
                    List.of(new Point2(0,0),new Point2(1,0),new Point2(0,1)),List.of(0,1,2)))));
            NativeAssertions.require(seeded.outcome()==AuthoringTransactionOutcome.COMMITTED,
                "protected mesh setup did not commit");
            execute(glb,current,history,restore,transactions,data);
        } catch(Exception | Error failure) {
            primary=failure;data.put("status","FAIL");data.put("failure",failure.toString());throw failure;
        } finally {
            try {
                edt(()->{restore.accept(entry.position());return null;});
                NativeAssertions.require(edt(()->state(current.get())).equals(original),
                    "protected mesh setup cleanup changed entry state");
            } catch(Exception | Error failure) {
                data.put("status","FAIL");
                if(primary!=null)primary.addSuppressed(failure);else throw failure;
            }
        }
    }
    private static void execute(byte[] glb, Supplier<CubismModel> current, Supplier<HistorySnapshot> history,
            IntConsumer restore, AuthoringTransactionService transactions, Map<String,Object> data) throws Exception {
        NativeAssertions.require(!SwingUtilities.isEventDispatchThread(),"whole import probe must not block EDT");
        data.put("status","RUNNING");
        var prepared=SurfaceImport.prepare(glb,new SurfaceImport.Request("MI3D Imported Cube",
            CubeGeometryProbe.YAW,CubeGeometryProbe.PITCH,CubeGeometryProbe.CAMERA,30,30,2_000_000));
        NativeAssertions.require(prepared.inputSha256.equals(CubeGeometryProbe.INPUT_SHA256),"authorized cube bytes required");
        data.put("inputSha256",prepared.inputSha256);
        data.put("renderUvTransform",List.of(.5,.25,.5,.25));
        var root=edt(history::get);var initial=edt(()->state(current.get()));
        data.put("initialHistory",historyData(root));data.put("initialState",initial);
        NativeAssertions.require(!((List<?>)initial.get("meshes")).isEmpty()
            && !((List<?>)initial.get("parameters")).isEmpty(),"preexisting authored content required");
        var failures=new ArrayList<Map<String,Object>>();data.put("failures",failures);
        Throwable primary=null;
        try {
            for(var stage:SurfaceImport.Stage.values()) {
                var reached=new ArrayList<String>();var row=new LinkedHashMap<String,Object>();failures.add(row);
                row.put("stage",stage.name());row.put("beforeHistory",historyData(edt(history::get)));
                var result=edt(()->SurfaceImport.apply(prepared,current,transactions,at->{
                    reached.add(at.name());if(at==stage)throw new IllegalStateException("MI3D_WHOLE_INJECTED_"+stage);
                }));
                row.put("reached",List.copyOf(reached));row.put("outcome",result.outcome().name());
                row.put("diagnosticId",result.diagnosticId().orElse(null));
                var after=edt(()->state(current.get()));row.put("afterState",after);
                row.put("afterHistory",historyData(edt(history::get)));
                NativeAssertions.require(result.outcome()==AuthoringTransactionOutcome.ROLLED_BACK
                    && !reached.isEmpty() && reached.get(reached.size()-1).equals(stage.name()) && after.equals(initial),
                    "whole import failed restoration at "+stage+": "+result.outcome());
                NativeAssertions.require(edt(history::get).equals(root),"failed import changed native history at "+stage);
            }
            var before=edt(history::get);var reached=new ArrayList<String>();
            var result=edt(()->SurfaceImport.apply(prepared,current,transactions,at->reached.add(at.name())));
            data.put("outcome",result.outcome().name());data.put("reached",List.copyOf(reached));
            data.put("diagnosticId",result.diagnosticId().orElse(null));
            NativeAssertions.require(result.outcome()==AuthoringTransactionOutcome.COMMITTED,"whole import did not commit: "+result.outcome());
            var imported=result.value().orElseThrow();var committed=edt(history::get);
            data.put("beforeHistory",historyData(before));data.put("committedHistory",historyData(committed));
            NativeAssertions.require(committed.position()==before.position()+1,"whole import must have exactly one history entry");
            data.put("partId",imported.partId().value());
            data.put("meshIds",imported.geometry().patches().stream().map(p->p.meshId().value()).toList());
            data.put("written",edt(()->imported.geometry().patches().stream()
                .map(p->NativeAssertions.snapshot(current.get().drawables().find(p.meshId()).authoringSnapshot())).toList()));
            var written=edt(()->state(current.get()));data.put("committedState",written);
            edt(()->{restore.accept(before.position());return null;});
            var undone=edt(()->state(current.get()));data.put("undoneState",undone);
            data.put("undoneHistory",historyData(edt(history::get)));
            NativeAssertions.require(undone.equals(initial),"single native Undo failed to restore preexisting content/resources");
            edt(()->{restore.accept(committed.position());return null;});
            var redone=edt(()->state(current.get()));data.put("redoneState",redone);
            data.put("redoneHistory",historyData(edt(history::get)));
            NativeAssertions.require(redone.equals(written),"single native Redo changed import identities/content/resources");
            edt(()->{for(var p:imported.geometry().patches())p.bake().verify(current.get().drawables().find(p.meshId()));return null;});
            data.put("status","PASS");
        } catch(Exception | Error failure) {
            primary=failure;data.put("status","FAIL");data.put("failure",failure.toString());throw failure;
        } finally {
            try {
                edt(()->{restore.accept(root.position());return null;});
                var restored=edt(()->state(current.get()));data.put("restoredState",restored);
                data.put("restoredHistory",historyData(edt(history::get)));
                NativeAssertions.require(restored.equals(initial),"whole-import finally did not restore entry state");
                data.put("finallyRestored",true);
            } catch(Exception | Error failure) {
                data.put("status","FAIL");data.put("finallyRestored",false);
                if(primary!=null)primary.addSuppressed(failure);else throw failure;
            }
        }
    }
    /** Cross-process comparison excludes session identity, but retains authored IDs and ordering.
     * Pair verification must additionally bind the exact GLB/CMO3 and evaluate geometry/pixels.
     * Image metadata is not evidence of observed image bytes.
     */
    static Map<String,Object> persistenceState(CubismModel model) {
        var observed = new LinkedHashMap<String,Object>(state(model));
        var meshes = new ArrayList<Map<String,Object>>();
        for (Object item : (List<?>) observed.get("meshes")) {
            var row = new LinkedHashMap<String,Object>();
            ((Map<?,?>) item).forEach((k,v) -> row.put((String) k,v));
            if (row.get("input") instanceof Map<?,?> input) {
                var binding = new LinkedHashMap<String,Object>();
                input.forEach((k,v) -> binding.put((String) k,v));
                binding.put("atlasId", model.textures().textureInput(new dev.turboism.sdk.cubism.id.ArtMeshId((String)row.get("id")))
                    .orElseThrow().textureAtlasId().map(v->v.value()).orElse(""));
                row.put("input", binding);
            }
            meshes.add(row);
        }
        observed.put("meshes", meshes);
        return persistenceState(observed);
    }
    static Map<String,Object> persistenceState(Map<String,Object> observed) {
        var durable = new LinkedHashMap<String,Object>(observed);
        durable.remove("model");
        var meshes = new ArrayList<Map<String,Object>>();
        for (Object item : (List<?>) observed.get("meshes")) {
            var row = new LinkedHashMap<String,Object>();
            ((Map<?,?>) item).forEach((k,v) -> row.put((String) k,v));
            if (row.get("input") instanceof Map<?,?> input) {
                var binding = new LinkedHashMap<String,Object>();
                input.forEach((k,v) -> binding.put((String) k,v));
                binding.remove("modelId");
                binding.remove("generation");
                row.put("input", Collections.unmodifiableMap(binding));
            }
            meshes.add(Collections.unmodifiableMap(row));
        }
        durable.put("meshes", List.copyOf(meshes));
        durable.put("schema", "mi3d-cube-persistence-state-v1");
        return Collections.unmodifiableMap(durable);
    }
    static Map<String,Object> state(CubismModel model) {
        var meshes=new ArrayList<Map<String,Object>>();
        for(var mesh:model.drawables().all()) {
            var row=new LinkedHashMap<String,Object>();row.put("id",mesh.id().value());
            row.put("snapshotSha256",SurfaceSnapshotFingerprint.of(mesh.authoringSnapshot()));
            row.put("culling",mesh.culling());row.put("visible",mesh.visible());
            row.put("parent",mesh.parentPartId().map(PartId::value).orElse(null));
            row.put("input",model.textures().textureInput(mesh.id()).<Object>map(i->Map.of(
                "kind",i.kind().name(),"modelId",i.modelId().value(),"generation",i.modelGeneration(),
                "meshId",i.artMeshId().value(),"imageId",i.modelImageId().map(v->v.value()).orElse(""))).orElse(null));
            meshes.add(row);
        }
        var c=model.canvas();
        return Map.of("model",model.id().value(),"meshes",meshes,
            "canvas",List.of(c.widthPixels(),c.heightPixels(),c.originXPixels(),c.originYPixels(),c.pixelsPerUnit()),
            "parameters",model.parameters().all().stream().map(p->List.of(p.id().value(),p.name().orElse(""),
                p.type().name(),p.getValue(),p.getMinimumValue(),p.getDefaultValue(),p.getMaximumValue(),p.repeat().orElseThrow())).toList(),
            "parts",model.parts().all().stream().map(p->List.of(p.id().value(),p.name(),p.parentId().map(PartId::value).orElse(""))).toList(),
            "rawImages",model.textures().rawImages().stream().map(v->List.of(v.id().value(),v.name(),v.width(),v.height())).toList(),
            "imageGroups",model.textures().modelImageGroups().stream().map(g->Map.of("name",g.groupName(),"images",
                g.modelImages().stream().map(v->List.of(v.id().value(),v.name(),v.width(),v.height())).toList())).toList());
    }
    static Map<String,Object> historyData(HistorySnapshot h) {
        return Map.of("availability",h.availability().name(),"generation",h.generation(),"revision",h.revision(),
            "position",h.position(),"documentBindingId",h.documentBindingId(),"managerBindingId",h.managerBindingId(),
            "entryIds",h.entries().stream().map(e->e.entryId().map(id->id.value()).orElse("")).toList());
    }
    private static <T>T edt(Callable<T> action)throws Exception {
        if(SwingUtilities.isEventDispatchThread())return action.call();
        var task=new FutureTask<T>(action);SwingUtilities.invokeAndWait(task);return task.get();
    }
}
