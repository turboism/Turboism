package dev.turboism.validation.modelimport3d.host;

import java.awt.Component;
import java.util.*;
import javax.swing.*;

public final class UserEntryProbeSelfTest {
    public static void main(String[] args)throws Exception {
        var faces=List.of(0,1,2,2,3,0);
        if(!UserEntryProbe.sameOrientedFaces(faces,List.of(3,0,2,1,2,0)))throw new AssertionError("native face permutation/cyclic rotation rejected");
        if(UserEntryProbe.sameOrientedFaces(faces,List.of(0,2,1,2,3,0)))throw new AssertionError("reversed face accepted");
        if(UserEntryProbe.sameOrientedFaces(faces,List.of(0,1,2)))throw new AssertionError("missing face accepted");
        if(UserEntryProbe.sameOrientedFaces(faces,List.of(0,1,2,0,1,2)))throw new AssertionError("duplicate face substitution accepted");
        System.out.println("PASS 4 oriented source topology permutation/reversal/loss/multiplicity checks");
        for(String mode:List.of("cat-vertex-reopen","cat-vertex-drag","cat-optimized-reopen","cat-optimized-drag","cat-native-serialization"))
            if(UserEntryProbe.documentWaitSeconds(false,mode)!=300)throw new AssertionError("Cat loading wait missing: "+mode);
        if(UserEntryProbe.documentWaitSeconds(false,null)!=90 || UserEntryProbe.documentWaitSeconds(true,null)!=300)
            throw new AssertionError("default loading wait changed");
        if(!UserEntryProbe.catInputResource("cat-optimized-save").equals("cat.zip")
                || !UserEntryProbe.catInputResource("cat-vertex-save").equals("cat.glb"))throw new AssertionError("Cat profile resource mismatch");
        if(UserEntryProbe.class.getResource("cat.zip")!=null && UserEntryProbe.class.getResource(UserEntryProbe.catInputResource("cat-optimized-save"))==null)
            throw new AssertionError("optimized Cat profile rejected its packed input");
        SwingUtilities.invokeAndWait(()->{
            var bar=new JMenuBar();var plugins=new JMenu("插件");var model=new JMenu("3D 模型");
            var item=new JMenuItem("导入 3D 模型…");bar.add(plugins);plugins.add(model);model.add(item);
            var hits=new ArrayList<JMenuItem>();
            UserEntryProbe.walk(bar,JMenuItem.class,c->"导入 3D 模型…".equals(c.getText()),false,hits,
                Collections.newSetFromMap(new IdentityHashMap<Component,Boolean>()));
            if(!hits.equals(List.of(item)))throw new AssertionError("closed submenu not traversed exactly once");
            hits.clear();UserEntryProbe.walk(bar,JMenuItem.class,c->true,true,hits,
                Collections.newSetFromMap(new IdentityHashMap<Component,Boolean>()));
            if(!hits.isEmpty())throw new AssertionError("hidden controls admitted as visible");
        });
        var doc=new dev.turboism.sdk.cubism.DocumentSnapshot("doc","test","test.cmo3",Optional.empty(),Optional.of(
            new dev.turboism.sdk.cubism.ModelSnapshot("session", "test",List.of(),List.of(),List.of(),List.of())));
        java.util.function.Supplier<dev.turboism.sdk.cubism.model.CubismModel> model=()->
            (dev.turboism.sdk.cubism.model.CubismModel)java.lang.reflect.Proxy.newProxyInstance(UserEntryProbeSelfTest.class.getClassLoader(),
                new Class<?>[]{dev.turboism.sdk.cubism.model.CubismModel.class},(o,m,a)->{
                    if(m.getName().equals("id"))return new dev.turboism.sdk.cubism.id.ModelId("native-guid");
                    throw new AssertionError("unexpected model call");
                });
        if(UserEntryProbe.observe(()->null,()->{throw new AssertionError("model read without document");},null)!=null)
            throw new AssertionError("missing document admitted");
        if(UserEntryProbe.observe(()->doc,()->{throw new AssertionError("old seed model read");},"doc")!=null)
            throw new AssertionError("seed admitted as new document");
        var target=UserEntryProbe.observe(()->doc,model,null);
        if(target==null || !target.snapshotModelId().equals("session") || !target.editorModelId().equals("native-guid"))
            throw new AssertionError("document/model namespaces conflated");
        var count=new int[]{0};
        if(UserEntryProbe.observe(()->++count[0]==1?doc:null,model,null)!=null)
            throw new AssertionError("disappearing document admitted");
        var live=new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterState(5,List.of(
            new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterValue("p",new dev.turboism.sdk.cubism.id.ParameterId("yaw"),0)));
        var old=new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterState(2,live.parameters());
        var fresh=new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterState(3,live.parameters());
        var changed=new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterState(6,List.of(
            new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterValue("p",new dev.turboism.sdk.cubism.id.ParameterId("yaw"),21.9f)));
        var requested=new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot(new dev.turboism.sdk.cubism.id.ArtMeshId("mesh"),"guid",Optional.empty(),live,old,1);
        var consumed=new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot(requested.artMeshId(),"guid",Optional.empty(),live,fresh,2);
        var drift=new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot(requested.artMeshId(),"guid",Optional.empty(),changed,fresh,2);
        if(!SourceTextureCapture.readiness(Collections.nCopies(6,requested),Collections.nCopies(6,requested)).equals("WAITING"))throw new AssertionError("stale pass admitted");
        if(!SourceTextureCapture.readiness(Collections.nCopies(6,requested),Collections.nCopies(6,consumed)).equals("CONSUMED"))throw new AssertionError("fresh pass rejected");
        if(!SourceTextureCapture.readiness(Collections.nCopies(6,requested),Collections.nCopies(6,drift)).equals("LIVE_CHANGED"))throw new AssertionError("live drift admitted");
        if(!SourceTextureCapture.readiness(Collections.nCopies(6,requested),List.of(consumed)).equals("IDENTITY_CHANGED"))throw new AssertionError("missing mesh admitted");
        for(int meshCount:new int[]{1,2}) {
            if(!SourceTextureCapture.readiness(Collections.nCopies(meshCount,requested),Collections.nCopies(meshCount,consumed),meshCount).equals("CONSUMED"))throw new AssertionError("surface consumption rejected");
            if(!SourceTextureCapture.readiness(Collections.nCopies(meshCount,requested),Collections.nCopies(meshCount,requested),meshCount).equals("WAITING"))throw new AssertionError("stale surface consumption admitted");
            if(!SourceTextureCapture.readiness(Collections.nCopies(meshCount,requested),Collections.nCopies(meshCount,drift),meshCount).equals("LIVE_CHANGED"))throw new AssertionError("surface live drift admitted");
            if(!SourceTextureCapture.readiness(Collections.nCopies(meshCount,requested),Collections.nCopies(meshCount+1,consumed),meshCount).equals("IDENTITY_CHANGED"))throw new AssertionError("wrong surface meshCount admitted");
        }
        for(long elapsed:new long[]{14_999_999_999L,15_000_000_000L,15_000_000_001L}) {
            var clock=new long[]{0};var progress=new LinkedHashMap<String,Object>();boolean rejected=false;
            try {
                SourceTextureCapture.awaitConsumed(Collections.nCopies(6,requested),()->{
                    clock[0]=elapsed;return Collections.nCopies(6,consumed);
                },progress,()->clock[0],millis->{throw new AssertionError("unexpected sleep");});
            } catch(IllegalStateException failure) {rejected=true;}
            if(rejected!=(elapsed>=15_000_000_000L))throw new AssertionError("late consumed sample admitted or early sample rejected");
            if(!progress.get("waitReason").equals(rejected?"TIMEOUT":"CONSUMED") || !progress.get("attempts").equals(1))throw new AssertionError("deadline evidence missing");
        }
        var entry=new dev.turboism.sdk.cubism.history.HistoryEntry(0,"Import",true,Optional.empty(),
            Optional.of(new dev.turboism.sdk.cubism.history.HistoryEntryId("entry-1")),Optional.empty());
        var history=new dev.turboism.sdk.cubism.history.HistorySnapshot(
            dev.turboism.sdk.cubism.history.HistorySnapshot.Availability.AVAILABLE,1,2,1,List.of(entry),true,false,"doc","manager");
        var encoded=SourceTextureCapture.history(history);
        String json=NativeReport.json(Map.of("entryHistory",encoded,"restoredHistory",encoded));
        if(!json.contains("\"entries\":[\"entry-1\"]"))throw new AssertionError("stable history ID not serialized as string");
        var missing=new dev.turboism.sdk.cubism.history.HistorySnapshot(
            dev.turboism.sdk.cubism.history.HistorySnapshot.Availability.AVAILABLE,1,2,1,
            List.of(new dev.turboism.sdk.cubism.history.HistoryEntry(0,"Import",true)),true,false,"doc","manager");
        boolean missingRejected=false;
        try {SourceTextureCapture.history(missing);}catch(IllegalStateException expected){missingRejected=true;}
        if(!missingRejected)throw new AssertionError("missing stable history ID admitted");
        var geometry=NativeAssertions.GEOMETRY;
        var mesh=new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot(new dev.turboism.sdk.cubism.id.ArtMeshId("compact"),"source",geometry,List.of(),
            List.of(new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Cell("f",List.of())),
            List.of(new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form("f",geometry.positions(),500)),List.of());
        var first=UserEntryProbe.compactMesh(mesh);
        var changedOrder=new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot(mesh.artMeshId(),mesh.sourceGuid(),geometry,mesh.axes(),mesh.cells(),
            List.of(new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form("f",geometry.positions(),501)),List.of());
        if(first.equals(UserEntryProbe.compactMesh(changedOrder)))throw new AssertionError("compact state loses draw order");
        var moved=geometry.positions().stream().map(p->new dev.turboism.sdk.cubism.model.Point2(p.x()+1,p.y())).toList();
        var changedLocal=new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot(mesh.artMeshId(),mesh.sourceGuid(),geometry,mesh.axes(),mesh.cells(),
            List.of(new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form("f",moved,500)),List.of());
        if(first.equals(UserEntryProbe.compactMesh(changedLocal)))throw new AssertionError("compact state loses local positions");
        if(!AppleTextureCapture.readiness(Collections.nCopies(98,requested),Collections.nCopies(98,consumed)).equals("CONSUMED"))throw new AssertionError("98 mesh consumption rejected");
        if(!AppleTextureCapture.readiness(Collections.nCopies(98,requested),Collections.nCopies(98,requested)).equals("WAITING"))throw new AssertionError("stale 98 mesh pass admitted");
        if(!AppleTextureCapture.readiness(Collections.nCopies(98,requested),Collections.nCopies(6,consumed)).equals("IDENTITY_CHANGED"))throw new AssertionError("partial apple mesh pass admitted");
        var bankA=Map.<String,Object>of("id","A","geometry","same","drawOrder",10);
        var bankB=Map.<String,Object>of("id","B","geometry","same","drawOrder",20);
        var seed=Map.<String,Object>of("meshes",List.of(bankA,bankB),"parameters",List.of(0));
        if(!UserEntryProbe.sameBoundSourceContent(seed,Map.of("meshes",List.of(bankB,bankA),"parameters",List.of(0))))throw new AssertionError("native enumeration order mistaken for source change");
        for(var bad:List.of(List.of(bankA,bankA),List.of(bankA),List.of(bankA,Map.of("id","B","geometry","changed","drawOrder",20)),List.of(bankA,Map.of("id","B","geometry","same","drawOrder",21))))
            if(UserEntryProbe.sameBoundSourceContent(seed,Map.of("meshes",bad,"parameters",List.of(0))))throw new AssertionError("lost/duplicate/modified source accepted");
        if(UserEntryProbe.sameBoundSourceContent(seed,Map.of("meshes",List.of(bankB,bankA),"parameters",List.of(1))))throw new AssertionError("parameter change accepted");
        System.out.println("PASS 2 menu traversal + 4 document readiness + 12 render consumption + 3 deadline + 2 history serialization checks; NOT_HOST_VERIFIED");
    }
}
