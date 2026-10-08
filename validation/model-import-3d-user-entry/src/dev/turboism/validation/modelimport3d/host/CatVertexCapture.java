package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.plugin.PluginContext;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** SDK-only detached observations; no source engine or oracle embedded. */
final class CatVertexCapture {
    private CatVertexCapture() {}
    static void inventory(CubismModel model) { inventory(model,70576,48497); }
    static void optimizedInventory(CubismModel model) { inventory(model,48864,35397); }
    static void adaptiveInventory(CubismModel model) { inventory(model,48864,35397,true); }
    static void costInventory(CubismModel model) { inventory(model,51790,37202,true,"cat-cost-sampling.properties","417800"); }
    static void editableCostInventory(CubismModel model) { inventory(model,51790,37833,true,"cat-editable-sampling.properties","517471",615); }
    static Map<String,Object> isolatedPatchParents(CubismModel model) {
        return isolatedPatchParents(model,512);
    }
    static Map<String,Object> editableIsolatedPatchParents(CubismModel model) {
        return isolatedPatchParents(model,615);
    }
    private static Map<String,Object> isolatedPatchParents(CubismModel model,int expectedMeshes) {
        var parents=new HashSet<PartId>();PartId root=null;
        for(var mesh:model.drawables().all()) {
            var id=mesh.parentPartId().orElseThrow();
            require(parents.add(id),"each patch needs its own local refresh Part");
            var leaf=model.parts().find(id);var owner=leaf.parentId().orElseThrow();
            if(root==null)root=owner;
            require(root.equals(owner) && leaf.getOpacity()==1f,"neutral patch Parts under one import root");
        }
        require(parents.size()==expectedMeshes,"complete isolated patch parent domain");
        return Map.of("leafParts",parents.size(),"rootPart",root.value(),"distinctParents",true);
    }
    private static void inventory(CubismModel model,int expectedTriangles,int expectedSlots) { inventory(model,expectedTriangles,expectedSlots,false); }
    private static void inventory(CubismModel model,int expectedTriangles,int expectedSlots,boolean adaptive) {
        inventory(model,expectedTriangles,expectedSlots,adaptive,"cat-adaptive-sampling.properties","481632");
    }
    private static void inventory(CubismModel model,int expectedTriangles,int expectedSlots,boolean adaptive,String fixture,String expectedForms) {
        inventory(model,expectedTriangles,expectedSlots,adaptive,fixture,expectedForms,512);
    }
    private static void inventory(CubismModel model,int expectedTriangles,int expectedSlots,boolean adaptive,String fixture,String expectedForms,int expectedMeshes) {
        Properties sampling=new Properties();
        if(adaptive)try(var input=CatVertexCapture.class.getResourceAsStream(fixture)) {
            require(input!=null,"exact Cat adaptive sampling fixture");sampling.load(input);
            require(expectedForms.equals(sampling.getProperty("forms")),"expected adaptive forms");
        } catch(IOException failure) { throw new IllegalStateException(failure); }
        int meshIndex=0;
        require(model.drawables().all().size()==expectedMeshes && model.warpDeformers().all().isEmpty()
            && model.rotationDeformers().all().size()==2,"direct native inventory");
        var roll=model.rotationDeformers().all().stream().filter(r->!r.authoringSnapshot().axes().isEmpty()).findFirst().orElseThrow();
        var placement=model.rotationDeformers().all().stream().filter(r->r.authoringSnapshot().axes().isEmpty()).findFirst().orElseThrow();
        require(roll.authoringSnapshot().forms().size()==5 && roll.parentDeformerId().equals(Optional.of(placement.id()))
            && placement.parentDeformerId().isEmpty(),"Roll/placement hierarchy");
        int triangles=0,slots=0;
        for(var mesh:model.drawables().all()) {
            var s=mesh.authoringSnapshot();triangles+=s.sourceGeometry().triangleIndices().size()/3;slots+=s.sourceGeometry().positions().size();
            require(s.forms().size()==81 && s.cells().size()==81 && s.axes().size()==2 && s.morphKeys().isEmpty(),"ordinary source bank");
            require(mesh.culling() && mesh.visible() && mesh.parentDeformerId().equals(Optional.of(roll.id())),"source mesh state");
            require(model.textures().textureInput(mesh.id()).isPresent(),"source texture binding");
            int[] insertCounts=adaptive?Arrays.stream(sampling.getProperty("mesh."+meshIndex).split(",")).mapToInt(Integer::parseInt).toArray():new int[]{3,3};
            for(int axisIndex=0;axisIndex<s.axes().size();axisIndex++) {
                var axis=s.axes().get(axisIndex);
                var required=new ParameterInterpolation(ParameterInterpolation.Method.ELLIPSE,insertCounts[axisIndex],1);
                require(axis.keys().equals(List.of(-180f,-135f,-90f,-45f,0f,45f,90f,135f,180f))
                    && model.parameterBindings(axis.parameterId()).interpolation(ParameterBindingTarget.artMesh(mesh.id())).equals(required),"native XY ellipse density mesh="+meshIndex);
            }
            meshIndex++;
        }
        require(triangles==expectedTriangles && slots==expectedSlots,"all source triangles/partition slots");
    }
    static Map<String,Object> bank(CubismModel model)throws Exception {
        return bank(model,false);
    }
    static Map<String,Object> persistentBank(CubismModel model)throws Exception {
        return bank(model,true);
    }
    private static Map<String,Object> bank(CubismModel model,boolean stable)throws Exception {
        // The SDK generation guards live observations; it is not serialized model
        // content. Preserve it for within-session history checks, normalize only
        // persistent banks. Resource and owning model identities remain exact.
        return bank(model,stable,stable?1:-1);
    }
    static Map<String,Object> generationDiagnostic(CubismModel model)throws Exception {
        var variants=new LinkedHashMap<String,Object>();
        for(long generation=1;generation<=4;generation++)variants.put(Long.toString(generation),bank(model,true,generation));
        var observed=model.drawables().all().stream().map(m->model.textures().textureInput(m.id()).orElseThrow().modelGeneration()).distinct().sorted().toList();
        return Map.of("rawBank",bank(model,true,-1),"observedTextureGenerations",observed,"generationOnlyVariants",variants,
            "onlyOverriddenField","ArtMeshTextureInput.modelGeneration; all remaining original binary bank fields exact");
    }
    private static Map<String,Object> bank(CubismModel model,boolean stable,long textureGeneration)throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");int triangles=0,slots=0;
        try(var out=new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest)))) {
            out.writeInt(model.drawables().all().size());
            var meshes=model.drawables().all();
            if(stable)meshes=meshes.stream().sorted(Comparator.comparing(m->m.id().value())).toList();
            for(var mesh:meshes) {
                var s=mesh.authoringSnapshot();out.writeUTF(s.artMeshId().value());out.writeUTF(s.sourceGuid());
                out.writeUTF(mesh.parentDeformerId().toString());out.writeUTF(mesh.parentPartId().toString());out.writeBoolean(mesh.culling());out.writeBoolean(mesh.visible());
                points(out,s.sourceGeometry().positions());points(out,s.sourceGeometry().uvs());
                out.writeInt(s.sourceGeometry().triangleIndices().size());for(int i:s.sourceGeometry().triangleIndices())out.writeInt(i);
                triangles+=s.sourceGeometry().triangleIndices().size()/3;slots+=s.sourceGeometry().positions().size();
                out.writeUTF(s.axes().toString());out.writeInt(s.cells().size());for(var cell:s.cells()){out.writeUTF(cell.formGuid());out.writeUTF(cell.coordinates().toString());}
                out.writeInt(s.forms().size());for(var f:s.forms()){out.writeUTF(f.formGuid());out.writeInt(f.drawOrder());points(out,f.positions());}
                out.writeUTF(s.morphKeys().toString());
                for(var a:s.axes())out.writeUTF(model.parameterBindings(a.parameterId()).interpolation(ParameterBindingTarget.artMesh(mesh.id())).toString());
                var input=model.textures().textureInput(mesh.id());
                if(textureGeneration>0)input=input.map(t->new ArtMeshTextureInput(t.artMeshId(),t.modelId(),textureGeneration,t.kind(),t.modelImageId(),t.textureAtlasId()));
                out.writeUTF(input.toString());
            }
            var rotations=model.rotationDeformers().all();
            if(stable)rotations=rotations.stream().sorted(Comparator.comparing(r->r.id().value())).toList();
            for(var r:rotations){out.writeUTF(r.authoringSnapshot().toString());out.writeUTF(r.parentDeformerId().toString());}
            var parts=model.parts().all();
            if(stable)parts=parts.stream().sorted(Comparator.comparing(p->p.id().value())).toList();
            for(var p:parts){out.writeUTF(p.id().value());out.writeUTF(p.parentId().toString());out.writeFloat(p.getOpacity());}
            if(stable)for(var p:model.parameters().all().stream().sorted(Comparator.comparing(p->p.id().value())).toList()) {
                out.writeUTF(p.id().value());out.writeFloat(p.getMinimumValue());out.writeFloat(p.getDefaultValue());
                out.writeFloat(p.getMaximumValue());out.writeFloat(p.getValue());
            }
        }
        return Map.of("sha256",HexFormat.of().formatHex(digest.digest()),"meshes",model.drawables().all().size(),"slots",slots,"triangles",triangles);
    }
    static void frames(PluginContext context,Supplier<CubismModel> current,Path state,Map<String,Object> report)throws Exception {
        var root=edt(()->context.cubism().history().snapshot());var frames=new ArrayList<Map<String,Object>>();report.put("frames",frames);
        Files.createDirectory(state.resolve("cat-frames"));
        try {
            int index=0;
            for(float[] pose:List.of(new float[]{0,0,0},new float[]{-180,-180,-180},new float[]{180,180,180},new float[]{90,90,90},new float[]{35,-47,61},new float[]{-127,103,-155},new float[]{37.5f,-112.5f,23},new float[]{-150,75,-72},new float[]{-179.5f,179.5f,179.5f})) {
                var row=new LinkedHashMap<String,Object>();frames.add(row);row.put("XYZ",List.of(pose[0],pose[1],pose[2]));
                var requested=edt(()->{for(var p:current.get().parameters().all()){
                    String id=p.id().value();if(id.startsWith("Param3DPitch"))p.setValue(pose[0]);if(id.startsWith("Param3DYaw"))p.setValue(pose[1]);if(id.startsWith("Param3DRoll"))p.setValue(pose[2]);
                }return observations(current.get());});
                long deadline=System.nanoTime()+30_000_000_000L;
                while(!edt(()->consumed(requested,observations(current.get())))){require(System.nanoTime()<deadline,"Cat native consumption timeout");Thread.sleep(100);}
                final int frame=index++;
                edt(()->{
                    var before=observations(current.get());require(consumed(requested,before),"Cat pose changed");
                    var history=context.cubism().history().snapshot();var pixels=current.get().renderPixels(1024,1024);
                    require(before.equals(observations(current.get())) && history.equals(context.cubism().history().snapshot()),"render changed evaluation/history");
                    require(pixels.width()==1024 && pixels.height()==1024,"render dimensions");
                    var image=new BufferedImage(1024,1024,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,1024,1024,pixels.argb(),0,1024);
                    var path=state.resolve("cat-frames/"+frame+".png");require(!Files.exists(path),"new frame required");require(ImageIO.write(image,"PNG",path.toFile()),"PNG writer");
                    row.put("artifact","cat-frames/"+frame+".png");row.put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
                    row.put("evaluated",before.stream().map(SourceTextureCapture::evaluation).toList());return null;
                });
            }
        } finally {
            var now=edt(()->context.cubism().history().snapshot());int count=now.position()-root.position();require(count>=0,"pose history moved before root");
            if(count>0)edt(()->{context.cubism().history().undo(count);return null;});
            require(edt(()->context.cubism().history().snapshot().position())==root.position(),"pose history restoration");
        }
        report.put("renderVerification","CAPTURED_REQUIRES_INDEPENDENT_SOURCE_ORACLE");
    }
    static boolean consumed(List<ArtMeshEvaluationSnapshot> requested,List<ArtMeshEvaluationSnapshot> actual) {
        if(requested.size()!=512 || actual.size()!=512)return false;
        for(int i=0;i<512;i++) {
            var r=requested.get(i);var a=actual.get(i);
            require(r.artMeshId().equals(a.artMeshId()) && r.sourceGuid().equals(a.sourceGuid()),"evaluation identity changed");
            if(a.calculatedForm().isEmpty() || !r.liveParameters().parameters().equals(a.liveParameters().parameters())
                || !r.liveParameters().parameters().equals(a.consumedParameters().parameters()))return false;
        }
        return true;
    }
    private static List<ArtMeshEvaluationSnapshot> observations(CubismModel m){return m.drawables().all().stream().map(Drawable::evaluationSnapshot).toList();}
    private static void points(DataOutputStream out,List<Point2> p)throws IOException{out.writeInt(p.size());for(var v:p){out.writeFloat(v.x());out.writeFloat(v.y());}}
    private static <T>T edt(Callable<T> action)throws Exception{var task=new FutureTask<T>(action);SwingUtilities.invokeAndWait(task);return task.get();}
    private static void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
}
