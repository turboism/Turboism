package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.command.*;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutService;
import dev.turboism.sdk.ui.UserFileAccessService;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.SwingUtilities;

/** Save an already-repacked cube, then observe the saved artifact in a fresh process. */
final class CubeAtlasPersistenceProbe {
    private CubeAtlasPersistenceProbe() { }

    /** Called only inside CubeAtlasProbe.afterAtlas, before that probe restores its seed. */
    static void savePacked(byte[] glb,Supplier<CubismModel> current,Supplier<HistorySnapshot> history,
            IntConsumer restore,TextureAtlasLayoutService layouts,EditorCommandService commands,
            UserFileAccessService files,Path state,Map<String,Object> data)throws Exception {
        var prepared=CubePersistenceInput.prepare(glb);
        var profile=CubePersistenceInput.identify(prepared.inputSha256);
        start(data,"atlas-save",profile);
        try {
            var before=edt(()->capture(current.get(),layouts,profile));data.put("beforeSave",before);
            var h0=edt(history::get);data.put("historyBefore",WholeImportProbe.historyData(h0));
            data.put("beforeSaveFrames",CubePersistenceRender.sample("atlas-before-save",prepared,current,history,restore,state,data));
            NativeAssertions.require(before.equals(edt(()->capture(current.get(),layouts,profile))),"sampling changed packed source");
            var artifact=CubePersistenceFile.save(files,commands,state,()->{
                NativeAssertions.require(history.get().documentBindingId().equals(h0.documentBindingId()),"save document changed");
                NativeAssertions.require(before.equals(capture(current.get(),layouts,profile)),"packed state changed before SAVE_AS");
            });
            data.put("artifact",artifact.relativePath());data.put("artifactBytes",artifact.bytes());data.put("artifactSha256",artifact.sha256());
            var after=edt(()->capture(current.get(),layouts,profile));data.put("afterSave",after);
            NativeAssertions.require(before.equals(after),"SAVE_AS changed packed authored state/layout");
            data.put("afterSaveFrames",CubePersistenceRender.sample("atlas-after-save",prepared,current,history,restore,state,data));
            var h1=edt(history::get);data.put("historyAfter",WholeImportProbe.historyData(h1));
            NativeAssertions.require(CubePersistenceRender.restoredHistory(h0,h1),"packed save observation changed history");
            data.put("renderStatus","CAPTURED");data.put("status","PASS");
        } catch(Exception|Error failure) {data.put("status","FAIL");data.put("failure",failure.toString());throw failure;}
    }

    /** No import, atlas creation or layout write is permitted in the reopen leg. */
    static void reopen(byte[] glb,Supplier<CubismModel> current,Supplier<HistorySnapshot> history,
            IntConsumer restore,TextureAtlasLayoutService layouts,EditorCommandService commands,
            Path state,Map<String,Object> data)throws Exception {
        var prepared=CubePersistenceInput.prepare(glb);
        var profile=CubePersistenceInput.identify(prepared.inputSha256);
        start(data,"atlas-reopen",profile);
        try {
            var before=edt(()->capture(current.get(),layouts,profile));data.put("reopened",before);
            var h0=edt(history::get);data.put("historyBefore",WholeImportProbe.historyData(h0));
            edt(()->{
                var command=commands.execute(EditorCommand.SHOW_MODEL_BY_TEXTURE_ATLAS);
                NativeAssertions.require(command.status()==EditorCommandResult.Status.EXECUTED,"atlas display unavailable");return null;
            });
            var displayed=edt(()->capture(current.get(),layouts,profile));data.put("afterDisplay",displayed);
            NativeAssertions.require(before.equals(displayed),"atlas display rewrote reopened mapping");
            data.put("reopenFrames",CubePersistenceRender.sample("atlas-reopen",prepared,current,history,restore,state,data));
            var after=edt(()->capture(current.get(),layouts,profile));data.put("afterObservation",after);
            var h1=edt(history::get);data.put("historyAfter",WholeImportProbe.historyData(h1));
            NativeAssertions.require(before.equals(after) && CubePersistenceRender.restoredHistory(h0,h1),"reopen observation mutated packed state/history");
            data.put("renderStatus","CAPTURED");data.put("status","PASS");
        } catch(Exception|Error failure) {data.put("status","FAIL");data.put("failure",failure.toString());throw failure;}
    }

    static Map<String,Object> capture(CubismModel model,TextureAtlasLayoutService layouts) {
        return capture(model,layouts,CubePersistenceInput.ORIGINAL);
    }
    static Map<String,Object> capture(CubismModel model,TextureAtlasLayoutService layouts,CubePersistenceInput profile) {
        NativeAssertions.require(SwingUtilities.isEventDispatchThread(),"capture requires EDT");
        var atlases=model.textures().textureAtlases();
        NativeAssertions.require(atlases.size()==1 && atlases.get(0).width()==profile.atlasSize && atlases.get(0).height()==profile.atlasSize,"one atlas with explicit input-profile size required");
        var atlas=atlases.get(0);
        for(var mesh:model.drawables().all()) {
            var input=model.textures().textureInput(mesh.id()).orElseThrow();
            NativeAssertions.require(input.kind()==ArtMeshTextureInput.Kind.TEXTURE_ATLAS_REGION
                && input.textureAtlasId().orElseThrow().equals(atlas.id()),"mesh lacks packed atlas binding");
        }
        var layout=CubeAtlasProbe.layout(layouts.current().orElseThrow());
        // Process-local document/model identities intentionally stay out of the
        // cross-process layout payload; full source/atlas identities remain.
        var durable=new LinkedHashMap<String,Object>(layout);durable.remove("documentId");durable.remove("modelId");
        return Map.of("cube",CubePersistenceProbe.capture(model),"layout",Collections.unmodifiableMap(durable),
            "atlas",List.of(atlas.id().value(),atlas.name(),atlas.width(),atlas.height(),atlas.modelImageCount()));
    }
    private static void start(Map<String,Object> data,String mode,CubePersistenceInput profile) {
        NativeAssertions.require(!SwingUtilities.isEventDispatchThread(),"probe must not block EDT");
        data.put("schemaVersion","mi3d-cube-atlas-persistence-v1");data.put("mode",mode);data.put("status","RUNNING");
        data.put("failure",null);data.put("inputSha256",profile.sha256);data.put("textureLayout",profile.layout);
        data.put("registeredImageSize",List.of(profile.imageWidth,profile.imageHeight));data.put("renderStatus","NOT_EVALUATED");
        var p=ProcessHandle.current();data.put("process",Map.of("pid",p.pid(),"startEpochMillis",p.info().startInstant().orElseThrow().toEpochMilli()));
    }
    private static <T>T edt(Callable<T> action)throws Exception {var task=new FutureTask<T>(action);SwingUtilities.invokeAndWait(task);return task.get();}
}
