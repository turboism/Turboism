package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.id.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Exercises the real all98 fixture and parser, not Editor authoring acceptance. */
public class AppleDerivedFixtureTest {
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    interface Attempt {void run()throws Exception;}
    static void rejected(Attempt attempt)throws Exception {try{attempt.run();}catch(IllegalStateException|IOException|IllegalArgumentException expected){return;}throw new AssertionError("corrupt provenance accepted");}
    public static void main(String[] args)throws Exception {
        byte[] raw=Files.readAllBytes(Path.of(args[0]));var originals=new LinkedHashMap<ArtMeshId,ArtMeshAuthoringSnapshot>();
        try(var in=new DataInputStream(new ByteArrayInputStream(raw))) {
            check(in.readInt()==0x4d493345&&in.readInt()==2&&in.readInt()==98);
            for(int chart=0;chart<98;chart++) {
                check(in.readInt()==chart);int count=in.readInt(),derived=in.readInt(),k=in.readInt();
                in.skipNBytes(count*8L+32);var remap=new ArrayList<Integer>();for(int i=0;i<count;i++)remap.add(in.readInt());
                in.skipNBytes(derived*8L+k*4L);int bytes=in.readInt();in.skipNBytes(bytes);
                var p=new ArrayList<Point2>();var uv=new ArrayList<Point2>();var indices=new ArrayList<Integer>();
                try(var source=new DataInputStream(Files.newInputStream(Path.of(args[1]).resolve(String.format("original-%03d.bin",chart))))) {
                    check(source.readInt()==count&&source.readInt()==k);
                    for(int i=0;i<count;i++){var v=new Point2(source.readFloat(),source.readFloat());p.add(v);uv.add(new Point2(v.x()/1024,v.y()/256));}
                    for(int i=0;i<k;i++)indices.add(source.readInt());check(source.read()==-1);
                }
                var local=remap.stream().map(i->new Point2(i/2048f,i/4096f)).toList();
                var keys=List.of(-180f,-120f,-60f,0f,60f,120f,180f);
                var axes=List.of(new ArtMeshAuthoringSnapshot.Axis("axis-yaw",new ParameterId("ParamYaw"),-180,0,180,keys),new ArtMeshAuthoringSnapshot.Axis("axis-pitch",new ParameterId("ParamPitch"),-180,0,180,keys));
                var cells=new ArrayList<ArtMeshAuthoringSnapshot.Cell>();var forms=new ArrayList<ArtMeshAuthoringSnapshot.Form>();
                for(float x:keys)for(float y:keys){String guid="form-"+chart+"-"+x+"-"+y;cells.add(new ArtMeshAuthoringSnapshot.Cell(guid,List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis-yaw",x),new ArtMeshAuthoringSnapshot.Coordinate("axis-pitch",y))));forms.add(new ArtMeshAuthoringSnapshot.Form(guid,local,chart));}
                var id=new ArtMeshId("ArtMesh3D"+chart);originals.put(id,new ArtMeshAuthoringSnapshot(id,"source-"+chart,new ArtMeshGeometry(p,uv,indices),axes,cells,forms,List.of()));
            }check(in.read()==-1);
        }
        var charts=AppleDerivedCapture.parse(raw,originals);check(charts.size()==98);
        check(charts.stream().mapToInt(c->c.geometry().positions().size()).sum()==80514);
        check(charts.stream().mapToInt(c->c.geometry().triangleIndices().size()/3).sum()==150784);
        rejected(()->AppleDerivedCapture.parse(Arrays.copyOf(raw,raw.length-1),originals));
        var missing=new LinkedHashMap<>(originals);missing.remove(new ArtMeshId("ArtMesh3D97"));rejected(()->AppleDerivedCapture.parse(raw,missing));
        var first=originals.get(new ArtMeshId("ArtMesh3D0"));var indices=new ArrayList<>(first.sourceGeometry().triangleIndices());int a=indices.get(0);indices.set(0,indices.get(1));indices.set(1,a);
        var changed=new LinkedHashMap<>(originals);changed.put(first.artMeshId(),new ArtMeshAuthoringSnapshot(first.artMeshId(),first.sourceGuid(),new ArtMeshGeometry(first.sourceGeometry().positions(),first.sourceGeometry().uvs(),indices),first.axes(),first.cells(),first.forms(),first.morphKeys()));
        rejected(()->AppleDerivedCapture.parse(raw,changed));
        var uv=new ArrayList<>(first.sourceGeometry().uvs());uv.set(0,new Point2(.5f,.5f));changed.put(first.artMeshId(),new ArtMeshAuthoringSnapshot(first.artMeshId(),first.sourceGuid(),new ArtMeshGeometry(first.sourceGeometry().positions(),uv,first.sourceGeometry().triangleIndices()),first.axes(),first.cells(),first.forms(),first.morphKeys()));
        rejected(()->AppleDerivedCapture.parse(raw,changed));
        System.out.println("PASS all98 original corners/80514 slots/150784 faces + 4 corruption controls; NOT_HOST_VERIFIED");
    }
}
