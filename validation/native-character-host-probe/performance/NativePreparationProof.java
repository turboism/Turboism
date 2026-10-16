package dev.turboism.validation.modelimport3d.host;
import java.util.*;import java.nio.file.*;import java.security.*;
import dev.turboism.sdk.cubism.id.ParameterId;
public class NativePreparationProof {
 public static void main(String[] args)throws Exception{
  byte[] bytes=Files.readAllBytes(Path.of(args[0]));
  var request=new SurfaceImport.Request("Character",new ParameterId("Yaw"),new ParameterId("Pitch"),new SurfaceProjection.Camera(512,512,400,new SurfaceProjection.Vertex(0,0,0)),180,180,20_000_000,Optional.of(new ParameterId("Roll")));
  long t=System.nanoTime();var p=NativeModelImport.prepare(bytes,request,()->false);long end=System.nanoTime();
  var digest=MessageDigest.getInstance("SHA-256");for(var row:p.geometry().orders())for(int v:row)digest.update(java.nio.ByteBuffer.allocate(4).putInt(v).array());
  System.out.printf(Locale.ROOT,"elapsedSeconds=%.3f triangles=%d partitions=%d controls=%d patches=%d geometrySlots=%d controlSlots=%d maxResidual=%.12f drawOrdersSha256=%s%n",(end-t)/1e9,p.geometry().totalTriangles(),p.geometry().partitions().size(),p.controls().controls().size(),p.controls().patches().size(),p.geometry().totalKeyformSlots(),p.controls().controlSamples(),p.controls().maximumResidualPixels(),HexFormat.of().formatHex(digest.digest()));
 }
}
