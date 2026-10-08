package dev.turboism.validation.modelimport3d.host;
import java.nio.file.*;
import java.io.*;
import java.util.*;
final class CanonicalWarpPreparationTest {
 public static void main(String[] args)throws Exception {
  var request=new SurfaceImport.Request("Apple",new dev.turboism.sdk.cubism.id.ParameterId("Yaw"),new dev.turboism.sdk.cubism.id.ParameterId("Pitch"),new SurfaceProjection.Camera(512,512,200,new SurfaceProjection.Vertex(0,0,0)),30,30,10000000);
  var candidate=SurfaceWarpImportCandidate.prepareFramed(Files.readAllBytes(Path.of(args[0])),request,SurfaceWarpImportCandidate.DEFAULT);
  var prepared=candidate.authoring();var plan=prepared.geometry;
   try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Path.of(args[1]).resolve("packed.bin"),StandardOpenOption.CREATE_NEW)))) {
    out.writeInt(plan.columns());out.writeInt(plan.rows());out.writeInt(plan.meshes().size());
    for(var mesh:plan.meshes()) {
     out.writeInt(mesh.sourcePositions().size());out.writeInt(mesh.triangles().size());
     for(var p:mesh.sourcePositions()){out.writeFloat((float)p.x());out.writeFloat((float)p.y());out.writeFloat((float)p.z());}
     for(var p:mesh.sourceUvs()){out.writeFloat(p.x());out.writeFloat(p.y());}
     for(var p:mesh.warpLocal()){out.writeFloat(p.x());out.writeFloat(p.y());}
     for(var p:mesh.slotIdentities()){out.writeInt(p.surface());out.writeInt(p.sourceSlot());out.writeInt(p.chart());}
     for(int index:mesh.indices())out.writeInt(index);
     for(var t:mesh.triangles()){out.writeInt(t.surface());out.writeInt(t.triangle());}
    }
    out.writeDouble(plan.maximumSourceResidual());for(var p:plan.sourceControls()){out.writeDouble(p.x());out.writeDouble(p.y());out.writeDouble(p.z());}
   }

  try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Path.of(args[1]).resolve("controls-xy-f32.bin"),StandardOpenOption.CREATE_NEW)))) {
   for(int y=0;y<prepared.yawKeys.size();y++)for(int p=0;p<prepared.pitchKeys.size();p++)for(var point:prepared.controls(y,p)){out.writeFloat(point.x());out.writeFloat(point.y());}
  }
  try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Path.of(args[1]).resolve("attributed.bin"),StandardOpenOption.CREATE_NEW)))) {
   var all=candidate.discovery().attribution().stream().map(SurfaceWarpChartPlan.ChartTriangle::source).toList();
   var ids=new HashMap<SurfaceWarpChartPlan.Triangle,Integer>();for(int i=0;i<all.size();i++)ids.put(all.get(i),i);
   out.writeBytes("CPACK001");out.writeInt(all.size());for(var t:candidate.discovery().attribution()){out.writeInt(t.chart());for(var p:t.corners()){out.writeDouble(p.u());out.writeDouble(p.v());}}
   for(var t:candidate.candidateOrder())out.writeInt(ids.get(t));
  }
  System.out.println("PASS GLB-only automatic canonical pipeline; sourceSha256="+candidate.inputSha256()+" charts="+candidate.discovery().charts().size()+" meshSlots="+plan.meshes().stream().map(m->m.sourcePositions().size()).toList()+" controlSamples="+plan.controlSamples()+" idealErrorBoundPixels="+prepared.idealErrorBoundPixels+" camera="+prepared.request.camera()+"; hostExecuted=false; visibilityAdmitted=false");
 }
}
