package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.command.*;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.textureatlas.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.SwingUtilities;

/** Repack the saved cube through the public SDK; sample the native atlas display path. */
final class CubeAtlasProbe {
    private CubeAtlasProbe() { }
    static void run(byte[] glb, Supplier<CubismModel> current, Supplier<HistorySnapshot> history,
            IntConsumer restore, TextureAtlasLayoutService layouts, EditorCommandService commands,
            Path state, Map<String,Object> data) throws Exception {
        run(glb,current,history,restore,layouts,commands,state,data,()->{});
    }
    @FunctionalInterface interface AfterAtlas { void run() throws Exception; }
    static void run(byte[] glb, Supplier<CubismModel> current, Supplier<HistorySnapshot> history,
            IntConsumer restore, TextureAtlasLayoutService layouts, EditorCommandService commands,
            Path state, Map<String,Object> data, AfterAtlas afterAtlas) throws Exception {
        NativeAssertions.require(!SwingUtilities.isEventDispatchThread(), "atlas probe requires worker");
        var prepared=CubePersistenceInput.prepare(glb);
        var profile=CubePersistenceInput.identify(prepared.inputSha256);
        data.put("schemaVersion","mi3d-cube-atlas-v1");data.put("status","RUNNING");data.put("failure",null);
        data.put("inputSha256",prepared.inputSha256);data.put("renderStatus","NOT_EVALUATED");
        data.put("textureLayout",profile.layout);
        data.put("registeredImageSize",List.of(profile.imageWidth,profile.imageHeight));
        var root=edt(history::get);data.put("initialHistory",WholeImportProbe.historyData(root));
        var initial=edt(()->CubePersistenceProbe.capture(current.get()));data.put("initial",initial);
        var initialAtlases=edt(()->atlases(current.get()));data.put("initialAtlases",initialAtlases);
        NativeAssertions.require(initialAtlases.isEmpty(),"atlas probe requires unatlased saved cube");
        Throwable primary=null;
        try {
            display(commands,EditorCommand.SHOW_MODEL_BY_RAW_IMAGE,data,"baselineDisplay");
            var raw=edt(()->CubePersistenceProbe.capture(current.get()));
            data.put("afterRawDisplay",raw);
            NativeAssertions.require(initial.equals(raw),
                "raw-image display changed authored source mapping before atlas creation");
            data.put("baselineFrames",CubePersistenceRender.sample("atlas-baseline",prepared,current,history,restore,state,data));
            edt(()->{ current.get().textures().addTextureAtlas("MI3D Cube Atlas",profile.atlasSize,profile.atlasSize);return null; });
            data.put("createdAtlases",edt(()->atlases(current.get())));
            data.put("afterCreate",edt(()->CubePersistenceProbe.capture(current.get())));
            data.put("createdHistory",WholeImportProbe.historyData(edt(history::get)));
            var first=apply(layouts,false,history,data,"first");
            display(commands,EditorCommand.SHOW_MODEL_BY_TEXTURE_ATLAS,data,"atlasDisplay");
            var packed=edt(()->CubePersistenceProbe.capture(current.get()));data.put("packed",packed);
            data.put("packedAtlases",edt(()->atlases(current.get())));
            data.put("packedFrames",CubePersistenceRender.sample("atlas-packed",prepared,current,history,restore,state,data));
            var second=apply(layouts,true,history,data,"second");
            NativeAssertions.require(!first.currentPlan().equals(second.currentPlan()),"repack was a no-op");
            var repacked=edt(()->CubePersistenceProbe.capture(current.get()));data.put("repacked",repacked);
            data.put("repackedAtlases",edt(()->atlases(current.get())));
            data.put("repackedFrames",CubePersistenceRender.sample("atlas-repacked",prepared,current,history,restore,state,data));
            int secondPosition=((Number)((Map<?,?>)data.get("secondHistoryAfter")).get("position")).intValue();
            // Do not write parameter poses between Undo and Redo: native writes discard the redo tail.
            edt(()->{restore.accept(secondPosition-1);return null;});
            data.put("undoHistory",WholeImportProbe.historyData(edt(history::get)));
            data.put("undoLayout",layout(edt(()->layouts.current().orElseThrow())));
            data.put("undoCapture",edt(()->CubePersistenceProbe.capture(current.get())));
            edt(()->{restore.accept(secondPosition);return null;});
            data.put("redoHistory",WholeImportProbe.historyData(edt(history::get)));
            data.put("redoLayout",layout(edt(()->layouts.current().orElseThrow())));
            var redone=edt(()->CubePersistenceProbe.capture(current.get()));data.put("redone",redone);
            data.put("redoneFrames",CubePersistenceRender.sample("atlas-redone",prepared,current,history,restore,state,data));
            data.put("renderStatus","CAPTURED");
            afterAtlas.run();
            data.put("status","PASS");
        } catch(Exception|Error failure) {primary=failure;data.put("status","FAIL");data.put("failure",failure.toString());throw failure;}
        finally {
            try {
                display(commands,EditorCommand.SHOW_MODEL_BY_RAW_IMAGE,data,"restoredDisplay");
                edt(()->{restore.accept(root.position());return null;});
                var restored=edt(()->CubePersistenceProbe.capture(current.get()));data.put("restored",restored);
                data.put("restoredAtlases",edt(()->atlases(current.get())));
                var restoredHistory=edt(history::get);data.put("restoredHistory",WholeImportProbe.historyData(restoredHistory));
                NativeAssertions.require(initial.equals(restored) && initialAtlases.equals(data.get("restoredAtlases"))
                    && CubePersistenceRender.restoredHistory(root,restoredHistory),"atlas cleanup changed seed");
                data.put("finallyRestored",true);
            } catch(Exception|Error failure) {
                data.put("finallyRestored",false);data.put("status","FAIL");
                if(primary!=null)primary.addSuppressed(failure);else throw failure;
            }
        }
    }
    private static TextureAtlasLayoutSnapshot apply(TextureAtlasLayoutService service, boolean reflected,
            Supplier<HistorySnapshot> history,Map<String,Object> data,String name)throws Exception {
        return edt(()->{
            var available=service.current();data.put(name+"TargetAvailable",available.isPresent());
            var before=available.orElseThrow(()->new IllegalStateException("atlas layout target unavailable after creation"));
            data.put(name+"LayoutBefore",layout(before));
            var h=history.get();data.put(name+"HistoryBefore",WholeImportProbe.historyData(h));
            var plan=SurfaceAtlasLayout.plan(before,reflected);data.put(name+"Requested",plan(plan));
            var result=service.apply(before.target(),plan);data.put(name+"Apply",result.status().map(Enum::name).orElse("FAILED"));
            NativeAssertions.require(result.status().orElse(null)==TextureAtlasLayoutApplyStatus.APPLIED,"atlas apply: "+result);
            var after=service.current().orElseThrow();data.put(name+"LayoutAfter",layout(after));
            var h1=history.get();data.put(name+"HistoryAfter",WholeImportProbe.historyData(h1));
            NativeAssertions.require(after.currentPlan().equals(plan) && h1.position()==h.position()+1,"atlas layout/history differs");
            return after;
        });
    }
    private static void display(EditorCommandService commands,EditorCommand command,Map<String,Object> data,String key)throws Exception {
        var result=edt(()->commands.execute(command));data.put(key,Map.of("command",command.name(),"status",result.status().name()));
        NativeAssertions.require(result.status()==EditorCommandResult.Status.EXECUTED,"display mode failed: "+command);
    }
    static Map<String,Object> layout(TextureAtlasLayoutSnapshot value) {
        return Map.of("documentId",value.documentId(),"modelId",value.modelId(),"atlasId",value.atlasId(),
            "items",value.items().stream().map(i->List.of(i.textureId(),i.width(),i.height())).toList(),"plan",plan(value.currentPlan()));
    }
    private static Map<String,Object> plan(TextureAtlasLayoutPlan value) {
        return Map.of("width",value.pageWidth(),"height",value.pageHeight(),"pages",value.pageCount(),"names",value.pageNames(),
            "scale",value.scale(),"placements",value.placements().stream().map(p->List.of(p.textureId(),p.pageIndex(),p.x(),p.y(),p.width(),p.height(),p.rotated())).toList());
    }
    private static List<?> atlases(CubismModel model) {
        return model.textures().textureAtlases().stream().map(a->List.of(a.id().value(),a.name(),a.width(),a.height(),a.modelImageCount())).toList();
    }
    private static <T>T edt(Callable<T> action)throws Exception {
        var task=new FutureTask<T>(action);SwingUtilities.invokeAndWait(task);return task.get();
    }
}
