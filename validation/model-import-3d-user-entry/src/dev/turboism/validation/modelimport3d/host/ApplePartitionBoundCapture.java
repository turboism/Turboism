package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.transaction.*;
import java.io.*;
import java.util.*;
import java.util.function.Supplier;

/** Real chart26 replacement in the task-owned full Apple document. */
final class ApplePartitionBoundCapture {
    static final ArtMeshId ORIGINAL=new ArtMeshId("ArtMesh3D26");
    static final String FIXTURE_SHA="906624bccb601f507a1531a9518c4efe9ab58027ac5a7eb9755ac8a0ba6fb1e1";
    record Group(ArtMeshGeometry geometry,List<Integer> originalFaces,List<Integer> originalSlots) { }
    record Replacement(List<ArtMeshId> ids,List<Group> groups,ArtMeshAuthoringSnapshot original,
                       Map<ArtMeshId,ArtMeshAuthoringSnapshot> unrelated,WarpAuthoringSnapshot warp,List<RotationAuthoringSnapshot> rotations) { }
    static void check(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    static List<Group> parse(byte[] raw,ArtMeshAuthoringSnapshot original)throws Exception {
        var geometry=original.sourceGeometry();var slots=new HashMap<Point2,Integer>();
        for(int i=0;i<geometry.positions().size();i++) {
            var previous=slots.putIfAbsent(geometry.positions().get(i),i);
            if(previous!=null) {
                check(geometry.uvs().get(previous).equals(geometry.uvs().get(i)),"coincident UV disagreement");
                for(var form:original.forms())check(form.positions().get(previous).equals(form.positions().get(i)),"coincident local positions disagree");
            }
        }
        var result=new ArrayList<Group>();var seen=new HashSet<Integer>();
        try(var input=new DataInputStream(new ByteArrayInputStream(raw))) {
            check(input.readInt()==0x4d493344 && input.readInt()==5,"exact partition header");
            for(int group=0;group<5;group++) {
                int n=input.readInt(),k=input.readInt();check(n>=3 && n<=1108 && k>=3 && k<=6144 && k%3==0,"bounded group domain");
                var p=new ArrayList<Point2>();var uv=new ArrayList<Point2>();var map=new ArrayList<Integer>();var indices=new ArrayList<Integer>();
                for(int i=0;i<n;i++){var point=new Point2(input.readFloat(),input.readFloat());var slot=slots.get(point);check(slot!=null,"source position absent");p.add(point);uv.add(geometry.uvs().get(slot));map.add(slot);}
                for(int i=0;i<k;i++)indices.add(input.readInt());var faces=new ArrayList<Integer>();
                for(int t=0;t<k/3;t++) {
                    int face=input.readInt();check(face>=0 && face<2048 && seen.add(face),"complete unique source faces");faces.add(face);
                    for(int c=0;c<3;c++){int old=geometry.triangleIndices().get(face*3+c),now=map.get(indices.get(t*3+c));
                        check(geometry.positions().get(old).equals(geometry.positions().get(now)) && geometry.uvs().get(old).equals(geometry.uvs().get(now)),"original face corner changed");}
                }
                result.add(new Group(new ArtMeshGeometry(p,uv,indices),List.copyOf(faces),List.copyOf(map)));
            }
            check(input.read()==-1 && seen.size()==2048,"original chart inventory");
        }
        check(original.axes().size()==2 && original.forms().size()==49 && original.cells().size()==49,"real layer bank required");
        return List.copyOf(result);
    }
    static Replacement create(PluginContext context,Supplier<CubismModel> current,byte[] raw,Map<String,Object> report)throws Exception {
        var model=current.get();var original=model.drawables().find(ORIGINAL);var before=original.authoringSnapshot();var groups=parse(raw,before);
        check(model.drawables().all().size()==98,"full original Apple seed required");
        var unrelated=new LinkedHashMap<ArtMeshId,ArtMeshAuthoringSnapshot>();for(var mesh:model.drawables().all())if(!mesh.id().equals(ORIGINAL))unrelated.put(mesh.id(),mesh.authoringSnapshot());
        var warp=model.warpDeformers().find(original.parentDeformerId().orElseThrow());var warpBefore=warp.authoringSnapshot();
        var rotations=model.rotationDeformers().all().stream().map(r->r.authoringSnapshot()).toList();
        var roll=model.rotationDeformers().find(warp.parentDeformerId().orElseThrow());var rollBefore=roll.form();
        check(rollBefore.angle()==0 && rollBefore.scale()==1 && !rollBefore.reflectedX() && !rollBefore.reflectedY(),"neutral Roll required");
        var placement=model.rotationDeformers().find(roll.parentDeformerId().orElseThrow());
        check(placement.form().equals(new RotationDeformerForm(0,0,0,1,false,false)),"identity outer placement required");
        for(var axis:warpBefore.axes())check(model.parameters().find(axis.parameterId()).getValue()==axis.defaultValue(),"neutral XY required");
        var oldGrid=warp.grid();var identity=new ArrayList<Point2>();for(int y=0;y<=oldGrid.rows();y++)for(int x=0;x<=oldGrid.columns();x++)identity.add(new Point2((float)x/oldGrid.columns(),(float)y/oldGrid.rows()));
        var part=model.parts().find(original.parentPartId().orElseThrow());var image=model.textures().textureInput(ORIGINAL).orElseThrow().modelImageId().orElseThrow();
        var sourceCells=new HashMap<Map<String,Float>,ArtMeshAuthoringSnapshot.Form>();var forms=new HashMap<String,ArtMeshAuthoringSnapshot.Form>();for(var f:before.forms())forms.put(f.formGuid(),f);
        for(var c:before.cells())sourceCells.put(tuple(c.coordinates()),forms.get(c.formGuid()));
        var defaults=new HashMap<String,Float>();for(var a:before.axes())defaults.put(a.parameterGuid(),a.defaultValue());var base=sourceCells.get(defaults);check(base!=null,"default source layer form required");
        for(var form:before.forms())check(form.positions().equals(base.positions()),"all layer forms must share source local geometry");
        var ids=new ArrayList<ArtMeshId>();var observations=new ArrayList<Map<String,Object>>();report.put("replacementGroups",observations);
        var transaction=context.cubism().authoringTransactions().execute(AuthoringTransactionOptions.of("Replace Apple chart26 with exact editable source partitions"),()->{
            try {
                roll.replaceForm(new RotationDeformerForm(0,0,0,1,false,false));warp.replaceGrid(new WarpGrid(oldGrid.rows(),oldGrid.columns(),oldGrid.quadTransform(),identity));
                for(int g=0;g<groups.size();g++) {
                    var group=groups.get(g);var local=group.originalSlots().stream().map(base.positions()::get).toList();
                    var mesh=model.drawables().createWithSourceGeometry("Apple chart26 editable group "+g,part,-1,group.geometry(),local);
                    ids.add(mesh.id());mesh.setCulling(original.culling());model.textures().bindModelImage(image,mesh.id());mesh.setTargetDeformer(Optional.of(warp.id()));
                    var attached=mesh.authoringSnapshot();double drift=0;for(int i=0;i<local.size();i++)drift=Math.max(drift,Math.hypot((double)local.get(i).x()-attached.forms().get(0).positions().get(i).x(),(double)local.get(i).y()-attached.forms().get(0).positions().get(i).y()));
                    check(drift<=1e-6,"identity attachment local error exceeds existing Warp tolerance");check(attached.sourceGeometry().equals(group.geometry()),"partition source geometry changed");
                    mesh.setDrawOrder(base.drawOrder());
                    for(var axis:before.axes()) {
                        var points=new ArrayList<ParameterBindingPoint>();for(int k=0;k<axis.keys().size();k++)points.add(new ParameterBindingPoint(new ParameterBindingPointId("PARTITION26_"+g+"_"+axis.parameterId().value()+"_"+k),axis.keys().get(k)));
                        model.parameterBindings(axis.parameterId()).bind(ParameterBindingTarget.artMesh(mesh.id()),points);
                    }
                    var bound=mesh.authoringSnapshot();check(bound.axes().equals(before.axes()) && bound.forms().size()==49 && bound.cells().size()==49,"copied layer bindings differ");
                    var edits=new ArrayList<ArtMeshKeyformDrawOrder>();for(var cell:bound.cells()) {var coords=tuple(cell.coordinates());var source=sourceCells.get(coords);check(source!=null,"copied layer tuple missing");if(!coords.equals(defaults))edits.add(new ArtMeshKeyformDrawOrder(cell.coordinates(),cell.formGuid(),source.drawOrder()));}
                    mesh.setOrdinaryKeyformDrawOrders(bound,edits);var bank=mesh.authoringSnapshot();for(var cell:bank.cells()) {var target=bank.forms().stream().filter(f->f.formGuid().equals(cell.formGuid())).findFirst().orElseThrow();check(target.drawOrder()==sourceCells.get(tuple(cell.coordinates())).drawOrder(),"original layer order differs");}
                    observations.add(Map.of("group",g,"originalFaces",group.originalFaces(),"originalSlots",group.originalSlots(),"parent",warp.id().value(),"localDrift",drift,"bank",NativeAssertions.snapshot(bank)));
                }
            } finally {warp.replaceGrid(oldGrid);roll.replaceForm(rollBefore);}
            check(warp.authoringSnapshot().equals(warpBefore) && model.rotationDeformers().all().stream().map(r->r.authoringSnapshot()).toList().equals(rotations),"temporary identity authoring changed original XYZ banks");
            return List.copyOf(ids);
        });
        check(transaction.changed() && ids.size()==5,"real bound replacement transaction failed: "+transaction);
        for(var entry:unrelated.entrySet())check(model.drawables().find(entry.getKey()).authoringSnapshot().equals(entry.getValue()),"unrelated original mesh changed");
        report.put("originalChart",NativeAssertions.snapshot(before));report.put("originalXYZBanksPreserved",true);
        return new Replacement(List.copyOf(ids),groups,before,unrelated,warpBefore,rotations);
    }
    static Map<String,Float> tuple(List<ArtMeshAuthoringSnapshot.Coordinate> coordinates){var result=new TreeMap<String,Float>();for(var c:coordinates)check(result.put(c.parameterGuid(),c.value())==null,"duplicate coordinate");return result;}
}
