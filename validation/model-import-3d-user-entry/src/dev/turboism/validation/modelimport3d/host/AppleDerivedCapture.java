package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.transaction.*;
import java.io.*;
import java.util.*;
import java.util.function.Supplier;

/** Task-only full original-face replacement using independently frozen derived images. */
final class AppleDerivedCapture {
    static final String SHA="f93fc84f9a71351977ecd1d85e82d39793278b3683ffb3c8b1b32d5049ef3520";
    static final String CROPPED_SHA="00a706b57d43b33e9042c0f1fb8b66692c0be44e7525a5b4ef4739be393656cc";
    static final String ALIGNED_SHA="cd891e99c35c3d0e6302c591a98fa020f2ae32dc6bba0a856a282f361afc54f2";
    static boolean knownFixture(String hash) {return SHA.equals(hash)||CROPPED_SHA.equals(hash)||ALIGNED_SHA.equals(hash);}
    record Chart(int index,ArtMeshId originalId,ArtMeshGeometry geometry,List<Integer> oldToNew,
                 List<Integer> representatives,byte[] png,int imageSize) {
        Chart { oldToNew=List.copyOf(oldToNew);representatives=List.copyOf(representatives);png=png.clone(); }
    }
    record Replacement(List<ArtMeshId> ids,List<Chart> charts,
                       Map<ArtMeshId,ArtMeshAuthoringSnapshot> originals,WarpAuthoringSnapshot warp,
                       List<RotationAuthoringSnapshot> rotations) { }
    static void check(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    static List<Chart> parse(byte[] raw,Map<ArtMeshId,ArtMeshAuthoringSnapshot> originals)throws Exception {
        var charts=new ArrayList<Chart>();int totalFaces=0,totalSlots=0;
        check(raw.length<32*1024*1024,"bounded derived fixture");
        try(var input=new DataInputStream(new ByteArrayInputStream(raw))) {
            check(input.readInt()==0x4d493345 && input.readInt()==2 && input.readInt()==98,"derived fixture header");
            for(int chart=0;chart<98;chart++) {
                check(input.readInt()==chart,"ordered original chart inventory");
                int originalCount=input.readInt(),n=input.readInt(),k=input.readInt();
                check(originalCount>=3&&originalCount<=65535&&n>=3&&n<=originalCount&&k>=3&&k<=600000&&k%3==0,"bounded original/derived domains");
                var id=new ArtMeshId("ArtMesh3D"+chart);var original=originals.get(id);check(original!=null,"missing original chart");
                var source=original.sourceGeometry();check(source.positions().size()==originalCount&&source.triangleIndices().size()==k,"original chart domain differs");
                for(int i=0;i<originalCount;i++)check(source.positions().get(i).equals(new Point2(input.readFloat(),input.readFloat())),"original source-plane provenance differs");
                byte[] expected=input.readNBytes(32);check(expected.length==32,"complete original corner provenance hash");
                var encoded=new ByteArrayOutputStream();try(var out=new DataOutputStream(encoded)) {
                    out.writeInt(originalCount);out.writeInt(k);for(var p:source.positions()){out.writeFloat(p.x());out.writeFloat(p.y());}
                    for(int index:source.triangleIndices())out.writeInt(index);
                }
                check(java.security.MessageDigest.isEqual(expected,java.security.MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray())),"original source face/corner provenance differs");
                for(int i=0;i<originalCount;i++) {var p=source.positions().get(i);check(source.uvs().get(i).equals(new Point2(p.x()/1024,p.y()/256)),"original shader UV provenance differs");}
                var remap=new ArrayList<Integer>();var representatives=new ArrayList<Integer>(Collections.nCopies(n,-1));
                for(int i=0;i<originalCount;i++) {
                    int target=input.readInt();check(target>=0&&target<n,"derived slot outside domain");remap.add(target);
                    int first=representatives.get(target);
                    if(first==-1)representatives.set(target,i);
                    else for(var form:original.forms())check(form.positions().get(first).equals(form.positions().get(i)),"welded original form positions differ");
                }
                check(!representatives.contains(-1),"unused derived slot");
                var positions=new ArrayList<Point2>();var uv=new ArrayList<Point2>();
                for(int i=0;i<n;i++){var p=new Point2(input.readFloat(),input.readFloat());check(p.x()>=4&&p.y()>=4&&p.x()<=516&&p.y()<=516,"derived image bounds");positions.add(p);}
                var indices=new ArrayList<Integer>();for(int i=0;i<k;i++) {
                    int value=input.readInt();check(value==remap.get(source.triangleIndices().get(i)),"original face/corner order changed");indices.add(value);
                }
                int bytes=input.readInt();check(bytes>0&&bytes<=AuthorizedImageContent.MAX_PNG_BYTES,"bounded derived texture");
                byte[] png=input.readNBytes(bytes);check(png.length==bytes,"truncated derived texture");
                var image=javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
                check(image!=null&&image.getWidth()==image.getHeight()&&(image.getWidth()==1024||image.getWidth()==520||image.getWidth()==576),"exact full or losslessly cropped derived texture dimensions");
                int imageSize=image.getWidth();image.flush();
                for(var p:positions)uv.add(imageSize==576
                    ?new Point2(p.x()*(1f/imageSize),p.y()*(1f/imageSize))
                    :new Point2(p.x()/imageSize,p.y()/imageSize));
                check(original.axes().size()==2&&original.forms().size()==49&&original.cells().size()==49&&original.morphKeys().isEmpty(),"original ordinary layer bank required");
                var base=original.forms().get(0);for(var form:original.forms())check(form.positions().equals(base.positions()),"original layer forms have distinct geometry");
                charts.add(new Chart(chart,id,new ArtMeshGeometry(positions,uv,indices),remap,representatives,png,imageSize));
                totalFaces+=k/3;totalSlots+=n;
            }
            check(input.read()==-1&&totalFaces==150784&&totalSlots==80514,"complete derived original-face inventory");
        }
        return List.copyOf(charts);
    }
    static Replacement create(PluginContext context,Supplier<CubismModel> current,byte[] raw,
                              Map<String,Object> report)throws Exception {
        var model=current.get();check(model.drawables().all().size()==98,"original full Apple seed required");
        var originals=new LinkedHashMap<ArtMeshId,ArtMeshAuthoringSnapshot>();
        for(var mesh:model.drawables().all())check(originals.put(mesh.id(),mesh.authoringSnapshot())==null,"duplicate original mesh ID");
        var charts=parse(raw,originals);
        report.put("derivedShaderUvMethod",charts.stream().allMatch(c->c.imageSize()==576)
            ?"OFFICIAL_5302_ALIGNED_RECIPROCAL_FLOAT":"FLOAT_DIVISION");
        var first=model.drawables().find(charts.get(0).originalId());
        var warp=model.warpDeformers().find(first.parentDeformerId().orElseThrow());var warpBefore=warp.authoringSnapshot();
        var rotations=model.rotationDeformers().all().stream().map(r->r.authoringSnapshot()).toList();
        var roll=model.rotationDeformers().find(warp.parentDeformerId().orElseThrow());var rollBefore=roll.form();
        check(rollBefore.angle()==0&&rollBefore.scale()==1&&!rollBefore.reflectedX()&&!rollBefore.reflectedY(),"neutral Roll required");
        for(var axis:warpBefore.axes())check(model.parameters().find(axis.parameterId()).getValue()==axis.defaultValue(),"neutral XY required");
        var oldGrid=warp.grid();var identity=new ArrayList<Point2>();for(int y=0;y<=oldGrid.rows();y++)for(int x=0;x<=oldGrid.columns();x++)identity.add(new Point2((float)x/oldGrid.columns(),(float)y/oldGrid.rows()));
        var ids=new ArrayList<ArtMeshId>();var rows=new ArrayList<Map<String,Object>>();report.put("derivedCharts",rows);
        boolean reuseImages=!SHA.equals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw)));
        var imageBindings=new HashMap<String,ModelImageId>();
        var transaction=context.cubism().authoringTransactions().execute(AuthoringTransactionOptions.of("Create editable original-face Apple charts with derived source textures"),()->{
            try {
                roll.replaceForm(new RotationDeformerForm(0,0,0,1,false,false));warp.replaceGrid(new WarpGrid(oldGrid.rows(),oldGrid.columns(),oldGrid.quadTransform(),identity));
                for(var chart:charts) {
                    var original=model.drawables().find(chart.originalId());var before=originals.get(chart.originalId());
                    check(original.parentDeformerId().equals(Optional.of(warp.id())),"all original charts must share original Warp");
                    var defaults=new TreeMap<String,Float>();for(var axis:before.axes())defaults.put(axis.parameterGuid(),axis.defaultValue());
                    var sourceForms=new HashMap<String,ArtMeshAuthoringSnapshot.Form>();for(var f:before.forms())sourceForms.put(f.formGuid(),f);
                    var sourceCells=new HashMap<Map<String,Float>,ArtMeshAuthoringSnapshot.Form>();for(var c:before.cells())sourceCells.put(ApplePartitionBoundCapture.tuple(c.coordinates()),sourceForms.get(c.formGuid()));
                    var base=sourceCells.get(defaults);check(base!=null,"complete default tuple required");
                    var local=chart.representatives().stream().map(base.positions()::get).toList();
                    var preview=model.drawables().previewSourceTriangulation(chart.geometry());
                    check(preview.positions().equals(chart.geometry().positions())&&sameFaces(preview.triangleIndices(),chart.geometry().triangleIndices()),"real SDK preview rejects derived original-face topology");
                    var part=model.parts().find(original.parentPartId().orElseThrow());
                    var mesh=model.drawables().createWithSourceGeometry("Apple editable chart "+chart.index(),part,-1,chart.geometry(),local);
                    ids.add(mesh.id());mesh.setCulling(original.culling());
                    String imageHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(chart.png()));
                    var existing=reuseImages?imageBindings.get(imageHash):null;
                    if(existing==null) {
                        var binding=model.textures().registerImageAndBind(AuthorizedImageContent.ofDimensions(chart.png(),"Derived original Apple UV/material mapping chart "+chart.index(),chart.imageSize(),chart.imageSize()),mesh.id());
                        imageBindings.put(imageHash,binding.modelImageId());
                    } else model.textures().bindModelImage(existing,mesh.id());
                    check(mesh.authoringSnapshot().sourceGeometry().equals(chart.geometry()),"derived image bind changed source plane");
                    mesh.setTargetDeformer(Optional.of(warp.id()));var attached=mesh.authoringSnapshot();double drift=0;
                    for(int i=0;i<local.size();i++)drift=Math.max(drift,Math.hypot((double)local.get(i).x()-attached.forms().get(0).positions().get(i).x(),(double)local.get(i).y()-attached.forms().get(0).positions().get(i).y()));
                    check(drift<=1e-6,"derived identity attachment drift exceeds existing Warp tolerance");
                    mesh.setDrawOrder(base.drawOrder());
                    for(var axis:before.axes()) {
                        var points=new ArrayList<ParameterBindingPoint>();for(int key=0;key<axis.keys().size();key++)points.add(new ParameterBindingPoint(new ParameterBindingPointId("DERIVED_"+chart.index()+"_"+axis.parameterId().value()+"_"+key),axis.keys().get(key)));
                        model.parameterBindings(axis.parameterId()).bind(ParameterBindingTarget.artMesh(mesh.id()),points);
                    }
                    var bound=mesh.authoringSnapshot();check(bound.axes().equals(before.axes())&&bound.forms().size()==49&&bound.cells().size()==49,"derived layer domain differs");
                    var edits=new ArrayList<ArtMeshKeyformDrawOrder>();for(var c:bound.cells()) {
                        var tuple=ApplePartitionBoundCapture.tuple(c.coordinates());var source=sourceCells.get(tuple);check(source!=null,"derived layer tuple missing");
                        if(!tuple.equals(defaults))edits.add(new ArtMeshKeyformDrawOrder(c.coordinates(),c.formGuid(),source.drawOrder()));
                    }
                    mesh.setOrdinaryKeyformDrawOrders(bound,edits);var bank=mesh.authoringSnapshot();
                    for(var c:bank.cells()) {
                        var f=bank.forms().stream().filter(v->v.formGuid().equals(c.formGuid())).findFirst().orElseThrow();
                        check(f.drawOrder()==sourceCells.get(ApplePartitionBoundCapture.tuple(c.coordinates())).drawOrder(),"derived layer order differs");
                        check(f.positions().equals(attached.forms().get(0).positions()),"derived layer local geometry differs");
                    }
                    rows.add(Map.of("chart",chart.index(),"originalId",chart.originalId().value(),"id",mesh.id().value(),"oldToNew",chart.oldToNew(),"representatives",chart.representatives(),"localDrift",drift));
                }
            } finally {warp.replaceGrid(oldGrid);roll.replaceForm(rollBefore);}
            check(warp.authoringSnapshot().equals(warpBefore)&&model.rotationDeformers().all().stream().map(r->r.authoringSnapshot()).toList().equals(rotations),"derived creation changed original XYZ banks");
            for(var entry:originals.entrySet())check(model.drawables().find(entry.getKey()).authoringSnapshot().equals(entry.getValue()),"derived creation changed original chart");
            return List.copyOf(ids);
        });
        check(transaction.changed()&&ids.size()==98,"full derived creation failed: "+transaction);
        return new Replacement(List.copyOf(ids),charts,originals,warpBefore,rotations);
    }
    static boolean sameFaces(List<Integer> a,List<Integer> b){return faces(a).equals(faces(b));}
    private static Map<List<Integer>,Integer> faces(List<Integer> indices) {
        var result=new HashMap<List<Integer>,Integer>();check(indices.size()%3==0,"complete faces required");
        for(int i=0;i<indices.size();i+=3) {
            int a=indices.get(i),b=indices.get(i+1),c=indices.get(i+2);
            var rotations=List.of(List.of(a,b,c),List.of(b,c,a),List.of(c,a,b));
            var sorted=new ArrayList<>(rotations);sorted.sort((x,y)->{for(int k=0;k<3;k++){int v=Integer.compare(x.get(k),y.get(k));if(v!=0)return v;}return 0;});
            result.merge(sorted.get(0),1,Integer::sum);
        }
        return result;
    }
}
