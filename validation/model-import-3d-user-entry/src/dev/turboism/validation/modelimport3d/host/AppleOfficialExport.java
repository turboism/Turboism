package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.command.*;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.textureatlas.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Calls the official atlas writer and MOC exporter; contains no import or custom evaluator. */
final class AppleOfficialExport {
    private AppleOfficialExport() { }
    static void run(Supplier<CubismModel> current,Supplier<Map<String,Object>> state,
            Supplier<HistorySnapshot> history,IntConsumer undo,TextureAtlasLayoutService layouts,
            EditorCommandService commands,Path directory,Map<String,Object> report,
            IntConsumer redo,Callable<Map<String,Object>> packedSave)throws Exception {
        run(current,state,history,undo,layouts,commands,directory,report,redo,packedSave,98,2048,false);
    }
    static void runSurface(Supplier<CubismModel> current,Supplier<Map<String,Object>> state,
            Supplier<HistorySnapshot> history,IntConsumer undo,TextureAtlasLayoutService layouts,
            EditorCommandService commands,Path directory,Map<String,Object> report,
            IntConsumer redo,Callable<Map<String,Object>> packedSave,int meshCount)throws Exception {
        require(meshCount==1 || meshCount==2,"explicit complete surface count required");
        require(redo!=null && packedSave!=null,"surface repack/save required");
        run(current,state,history,undo,layouts,commands,directory,report,redo,packedSave,meshCount,1024,true);
    }
    @FunctionalInterface interface Capture { void run() throws Exception; }
    static void runXYZ(Supplier<CubismModel> current,Supplier<Map<String,Object>> state,
            Supplier<HistorySnapshot> history,IntConsumer undo,TextureAtlasLayoutService layouts,
            EditorCommandService commands,Path directory,Map<String,Object> report,Capture capture)throws Exception {
        run(current,state,history,undo,layouts,commands,directory,report,null,null,98,2048,false,capture);
    }
    static void runDerivedXYZ(Supplier<CubismModel> current,Supplier<Map<String,Object>> state,
            Supplier<HistorySnapshot> history,IntConsumer undo,TextureAtlasLayoutService layouts,
            EditorCommandService commands,Path directory,Map<String,Object> report,Capture capture)throws Exception {
        run(current,state,history,undo,layouts,commands,directory,report,null,null,98,4096,false,capture,2);
    }
    static void runDerivedAtlasUndo(Supplier<CubismModel> current,Supplier<Map<String,Object>> state,
            Supplier<HistorySnapshot> history,IntConsumer undo,TextureAtlasLayoutService layouts,
            EditorCommandService commands,Path directory,Map<String,Object> report)throws Exception {
        run(current,state,history,undo,layouts,commands,directory,report,null,null,98,4096,false,null,2,true);
    }
    private static void run(Supplier<CubismModel> current,Supplier<Map<String,Object>> state,
            Supplier<HistorySnapshot> history,IntConsumer undo,TextureAtlasLayoutService layouts,
            EditorCommandService commands,Path directory,Map<String,Object> report,
            IntConsumer redo,Callable<Map<String,Object>> packedSave,int meshCount,int atlasSize,boolean surface)throws Exception {
        run(current,state,history,undo,layouts,commands,directory,report,redo,packedSave,meshCount,atlasSize,surface,null);
    }
    private static void run(Supplier<CubismModel> current,Supplier<Map<String,Object>> state,
            Supplier<HistorySnapshot> history,IntConsumer undo,TextureAtlasLayoutService layouts,
            EditorCommandService commands,Path directory,Map<String,Object> report,
            IntConsumer redo,Callable<Map<String,Object>> packedSave,int meshCount,int atlasSize,boolean surface,Capture xyzCapture)throws Exception {
        run(current,state,history,undo,layouts,commands,directory,report,redo,packedSave,meshCount,atlasSize,surface,xyzCapture,1);
    }
    private static void run(Supplier<CubismModel> current,Supplier<Map<String,Object>> state,
            Supplier<HistorySnapshot> history,IntConsumer undo,TextureAtlasLayoutService layouts,
            EditorCommandService commands,Path directory,Map<String,Object> report,
            IntConsumer redo,Callable<Map<String,Object>> packedSave,int meshCount,int atlasSize,boolean surface,Capture xyzCapture,int maximumPages)throws Exception {
        run(current,state,history,undo,layouts,commands,directory,report,redo,packedSave,meshCount,atlasSize,surface,xyzCapture,maximumPages,false);
    }
    private static void run(Supplier<CubismModel> current,Supplier<Map<String,Object>> state,
            Supplier<HistorySnapshot> history,IntConsumer undo,TextureAtlasLayoutService layouts,
            EditorCommandService commands,Path directory,Map<String,Object> report,
            IntConsumer redo,Callable<Map<String,Object>> packedSave,int meshCount,int atlasSize,boolean surface,Capture xyzCapture,int maximumPages,boolean layoutOnly)throws Exception {
        if(SwingUtilities.isEventDispatchThread())throw new IllegalStateException("export worker required");
        var section=new LinkedHashMap<String,Object>();report.put("officialExport",section);
        section.put("schemaVersion",surface?"mi3d-surface-official-export-v1":"mi3d-apple-official-export-v1");section.put("status","RUNNING");
        var original=edt(state::get);var root=edt(history::get);
        require(edt(()->current.get().drawables().all().size()==meshCount),"complete export mesh inventory required");
        section.put("entryHistory",AppleTextureCapture.history(root));
        require(edt(()->current.get().textures().textureAtlases().isEmpty()),"unatlased seed required");
        String originalJson=NativeReport.json(original);section.put("beforeAtlasStateJson",originalJson);
        section.put("beforeAtlasSha256",hash(originalJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Throwable primary=null;
        try {
            edt(()->{current.get().textures().addTextureAtlas(surface?"Surface source atlas":"Apple source atlas",atlasSize,atlasSize);return null;});
            edt(()->{
                var before=layouts.current().orElseThrow();var plan=AppleAtlasLayout.plan(before,false);
                require(plan.pageCount()>=1&&plan.pageCount()<=maximumPages,"bounded complete atlas pages required");
                section.put("atlasPageCount",plan.pageCount());
                section.put("initialAtlasLayout",layoutPlan(plan));
                var result=layouts.apply(before.target(),plan);
                require(result.status().orElse(null)==TextureAtlasLayoutApplyStatus.APPLIED,"native atlas apply failed");
                require(commands.execute(EditorCommand.SHOW_MODEL_BY_TEXTURE_ATLAS).status()==EditorCommandResult.Status.EXECUTED,"atlas display unavailable");
                return null;
            });
            if(packedSave!=null) {
                require(redo!=null,"native redo required for repack");
                var initial=edt(state::get);var firstLayout=edt(()->layout(layouts));var initialHistory=edt(history::get);
                edt(()->{
                    require(AppleAtlasLayout.apply(layouts,true).status().orElse(null)==TextureAtlasLayoutApplyStatus.APPLIED,"reflected repack failed");return null;
                });
                var repacked=edt(state::get);var secondLayout=edt(()->layout(layouts));var repackedHistory=edt(history::get);
                require(!firstLayout.equals(secondLayout) && !initial.equals(repacked),"repack must change atlas placement and UV");
                require(repackedHistory.position()==initialHistory.position()+1,"repack must be one native history entry");
                for(String field:List.of("deformers","parameters","parts","rawImages","imageGroups"))
                    require(initial.get(field).equals(repacked.get(field)),"repack changed protected "+field);
                edt(()->{undo.accept(1);require(state.get().equals(initial) && layout(layouts).equals(firstLayout),"repack Undo changed source/layout");return null;});
                edt(()->{redo.accept(1);require(state.get().equals(repacked) && layout(layouts).equals(secondLayout),"repack Redo failed");return null;});
                section.put("repack",Map.of("before",initial,"after",repacked,"layoutBefore",firstLayout,"layoutAfter",secondLayout,
                    "historyBefore",AppleTextureCapture.history(initialHistory),"historyAfter",AppleTextureCapture.history(repackedHistory),
                    "undoExact",true,"redoExact",true));
            }
            var packed=edt(state::get);section.put("packedStateJson",NativeReport.json(packed));
            if(layoutOnly) {section.put("status","LAYOUT_APPLIED_UNDO_DIAGNOSTIC_ONLY");return;}
            Path geometry=directory.resolve("packed-source");Files.createDirectory(geometry);
            section.put("packedSource",edt(()->{
                var rows=new ArrayList<Map<String,Object>>();
                for(var mesh:current.get().drawables().all()) {
                    var g=mesh.authoringSnapshot().sourceGeometry();String name="packed-source/"+rows.size()+".bin";
                    try(var stream=new java.io.DataOutputStream(Files.newOutputStream(directory.resolve(name),StandardOpenOption.CREATE_NEW))) {
                        for(var point:g.positions()){stream.writeFloat(point.x());stream.writeFloat(point.y());}
                        for(var point:g.uvs()){stream.writeFloat(point.x());stream.writeFloat(point.y());}
                        for(int index:g.triangleIndices())stream.writeInt(index);
                    }
                    byte[] raw=Files.readAllBytes(directory.resolve(name));rows.add(Map.of("id",mesh.id().value(),"artifact",name,"bytes",raw.length,"sha256",hash(raw)));
                }
                return rows;
            }));
            section.put("atlases",edt(()->current.get().textures().textureAtlases().stream()
                .map(a->List.of(a.id().value(),a.name(),a.width(),a.height(),a.modelImageCount())).toList()));
            Path capture=directory.resolve("atlas-capture");Files.createDirectory(capture);
            var pixels=new LinkedHashMap<String,Object>();section.put("atlasCapture",pixels);
            if(xyzCapture!=null)xyzCapture.run();
            else if(surface)SourceTextureCapture.run(current,state,history,undo,capture,pixels,meshCount);
            else AppleTextureCapture.run(current,state,history,undo,capture,pixels);
            require(edt(state::get).equals(packed),"atlas capture changed packed state");
            if(packedSave!=null) {
                section.put("packedAtlasLayout",edt(()->layout(layouts)));
                section.put("packedPersistenceSave",packedSave.call());
                require(edt(state::get).equals(packed),"packed save changed protected state");
            }
            var h0=edt(history::get);String before=hash(NativeReport.json(packed).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var settings=new CubismMocExportSettings(512,512,100,false,false,false,false,false);
            var export=edt(()->current.get().exportMoc(settings));
            var after=edt(state::get);var h1=edt(history::get);
            section.put("exportStateBefore",packed);section.put("exportStateAfter",after);
            section.put("historyBeforeExport",AppleTextureCapture.history(h0));section.put("historyAfterExport",AppleTextureCapture.history(h1));
            section.put("exportResultSummary",export.toString());
            section.put("changedStateFields",packed.keySet().stream().filter(k->!Objects.equals(packed.get(k),after.get(k))).toList());
            section.put("stateUnchangedAtExport",after.equals(packed));section.put("historyUnchangedAtExport",h0.equals(h1));
            require(after.equals(packed) && h0.equals(h1),"official export changed state/history");
            require(export.settings().equals(settings) && export.textures().size()==((Number)section.get("atlasPageCount")).intValue(),"complete official export textures required");
            section.put("beforeExportSha256",before);section.put("afterExportSha256",hash(NativeReport.json(after).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            section.put("historyBeforeExport",AppleTextureCapture.history(h0));section.put("historyAfterExport",AppleTextureCapture.history(h1));
            section.put("settings",List.of(512,512,100,false,false,false,false,false));
            Path output=directory.resolve("official-export");Files.createDirectory(output);
            byte[] moc=export.moc3();Files.write(output.resolve("model.moc3"),moc,StandardOpenOption.CREATE_NEW);
            section.put("moc",Map.of("artifact","official-export/model.moc3","bytes",moc.length,"sha256",hash(moc)));
            var textures=new ArrayList<Map<String,Object>>();
            for(int i=0;i<export.textures().size();i++) {
                var t=export.textures().get(i);require(t.width()==atlasSize && t.height()==atlasSize,"official atlas dimensions changed");
                int[] argb=t.argb();var image=new BufferedImage(t.width(),t.height(),BufferedImage.TYPE_INT_ARGB);
                image.setRGB(0,0,t.width(),t.height(),argb,0,t.width());String name="texture-"+i+".png";
                try(var stream=Files.newOutputStream(output.resolve(name),StandardOpenOption.CREATE_NEW)) {
                    require(ImageIO.write(image,"png",stream),"PNG encoding unavailable");
                } finally {image.flush();}
                byte[] png=Files.readAllBytes(output.resolve(name));textures.add(Map.of("index",i,"artifact","official-export/"+name,
                    "bytes",png.length,"sha256",hash(png),"width",t.width(),"height",t.height()));
            }
            section.put("textures",textures);section.put("status","CAPTURED_REQUIRES_OFFICIAL_CORE_VERIFICATION");
        } catch(Exception|Error failure) {primary=failure;section.put("status","FAIL");section.put("failure",failure.toString());throw failure;}
        finally {
            try {
                edt(()->{
                    require(commands.execute(EditorCommand.SHOW_MODEL_BY_RAW_IMAGE).status()==EditorCommandResult.Status.EXECUTED,"raw display restore unavailable");
                    int count=history.get().position()-root.position();require(count>=0 && count<=64,"bounded atlas restoration");
                    if(count>0)undo.accept(count);
                    var restorationState=state.get();
                    section.put("restorationState",restorationState);
                    section.put("restorationChangedStateFields",original.keySet().stream()
                        .filter(k->!Objects.equals(original.get(k),restorationState.get(k))).toList());
                    section.put("restorationAtlasIds",current.get().textures().textureAtlases().stream()
                        .map(a->a.id().value()).toList());
                    section.put("restorationUndoCount",count);
                    if(layoutOnly) {
                        Path source=directory.resolve("restored-source");Files.createDirectory(source);
                        var rows=new ArrayList<Map<String,Object>>();
                        for(var mesh:current.get().drawables().all()) {
                            var geometry=mesh.authoringSnapshot().sourceGeometry();String name="restored-source/"+rows.size()+".bin";
                            try(var stream=new java.io.DataOutputStream(Files.newOutputStream(directory.resolve(name),StandardOpenOption.CREATE_NEW))) {
                                for(var p:geometry.positions()){stream.writeFloat(p.x());stream.writeFloat(p.y());}
                                for(var p:geometry.uvs()){stream.writeFloat(p.x());stream.writeFloat(p.y());}
                                for(int index:geometry.triangleIndices())stream.writeInt(index);
                            }
                            byte[] raw=Files.readAllBytes(directory.resolve(name));rows.add(Map.of("id",mesh.id().value(),"artifact",name,"sha256",hash(raw),"bytes",raw.length));
                        }
                        section.put("restorationSource",rows);
                    }
                    require(restorationState.equals(original) && current.get().textures().textureAtlases().isEmpty(),"atlas/export cleanup did not restore source");
                    var restored=history.get();require(restored.position()==root.position() && restored.generation()==root.generation()
                        && restored.documentBindingId().equals(root.documentBindingId()) && restored.managerBindingId().equals(root.managerBindingId()),"history target changed");
                    section.put("restoredHistory",AppleTextureCapture.history(restored));section.put("restoredState",state.get());
                    section.put("sourceRestored",true);return null;
                });
            } catch(Exception|Error failure) {section.put("sourceRestored",false);section.put("status","FAIL");if(primary!=null)primary.addSuppressed(failure);else throw failure;}
        }
    }
    static Map<String,Object> layout(TextureAtlasLayoutService layouts) {
        var snapshot=layouts.current().orElseThrow();var plan=snapshot.currentPlan();
        return Map.of("atlasId",snapshot.atlasId(),"pageWidth",plan.pageWidth(),"pageHeight",plan.pageHeight(),"pageCount",plan.pageCount(),
            "placements",plan.placements().stream().map(p->List.of(p.textureId(),p.pageIndex(),p.x(),p.y(),p.width(),p.height(),p.rotated())).toList());
    }
    private static Map<String,Object> layoutPlan(TextureAtlasLayoutPlan plan) {
        return Map.of("pageWidth",plan.pageWidth(),"pageHeight",plan.pageHeight(),"pageCount",plan.pageCount(),
            "placements",plan.placements().stream().map(p->List.of(p.textureId(),p.pageIndex(),p.x(),p.y(),p.width(),p.height(),p.rotated())).toList());
    }
    private static void require(boolean ok,String why){if(!ok)throw new IllegalStateException(why);}
    private static String hash(byte[] raw)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}
    private static <T>T edt(Callable<T> task)throws Exception {
        if(SwingUtilities.isEventDispatchThread())return task.call();
        var future=new FutureTask<T>(task);SwingUtilities.invokeAndWait(future);return future.get();
    }
}
