package dev.turboism.validation.modelimport3d.host;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/** Full-candidate preparation checks, no Cubism or native image registration. */
final class SourceWarpAppearanceTest {
 public static void main(String[] args)throws Exception {
  if(args.length!=3)throw new IllegalArgumentException("source GLB, Warp fixture, output directory required");
  var surfaces=GlbSurfaceReader.read(Files.readAllBytes(Path.of(args[0])));var path=Path.of(args[1]);SourceWarpFixture source;
  try(var input=Files.newInputStream(path)){source=SourceWarpFixture.read(input,Files.size(path));}
  var plan=SourceWarpAppearance.prepare(surfaces,source);var image=plan.imageGrant();
  if(image.width()!=1024 || image.height()!=256 || plan.inputs.size()!=2)throw new AssertionError("shared atlas shape");
  var decoded=ImageIO.read(new java.io.ByteArrayInputStream(image.copyBytes()));int checked=0;
  for(int c=0;c<2;c++)for(int y=0;y<256;y++)for(int x=0;x<512;x++) {
   int expected=surfaces.get(c).texture().pixels().get(Math.max(0,Math.min(127,y-1))*256+Math.max(0,Math.min(255,x-1)));
   if(decoded.getRGB(c*512+x,y)!=expected)throw new AssertionError("source texel changed");checked++;
  }
  var output=Path.of(args[2]);Files.createDirectories(output);Files.write(output.resolve("java-atlas.png"),image.copyBytes());
  for(int m=0;m<2;m++) {
   var input=plan.inputs.get(m);var mesh=source.meshes.get(m);var geometry=mesh.geometry();
   if(!input.defaultLocal().equals(geometry.positions()) || !input.sourceGeometry().triangleIndices().equals(geometry.triangleIndices()))throw new AssertionError("default chart or original indices changed");
   try(var dump=new java.io.DataOutputStream(Files.newOutputStream(output.resolve("mesh-"+m+"-image-input.bin")))) {
    for(int i=0;i<mesh.sourceVertex.length;i++) {
     var uv=geometry.uvs().get(i);int component=mesh.slotIdentity[i*3];
     var pixel=input.sourceGeometry().positions().get(i);var shader=input.sourceGeometry().uvs().get(i);
     float x=(float)(component*512+1+uv.x()*256d),y=(float)(1+uv.y()*128d);
     if(pixel.x()!=x || pixel.y()!=y || shader.x()!=x/1024 || shader.y()!=y/256)throw new AssertionError("derived shader UV correspondence");
     dump.writeFloat(pixel.x());dump.writeFloat(pixel.y());dump.writeFloat(shader.x());dump.writeFloat(shader.y());
    }
   }
  }
  var uv=source.meshes.get(0).sourceUv;float old=uv[0];uv[0]=old+.125f;
  try {SourceWarpAppearance.prepare(surfaces,source);throw new AssertionError("source UV corruption accepted");}
  catch(IllegalArgumentException expected) { }finally{uv[0]=old;}
  try {SourceWarpAppearance.prepare(List.of(surfaces.get(0)),source);throw new AssertionError("missing source material accepted");}
  catch(IllegalArgumentException expected) { }
  System.out.println("PASS "+checked+" source atlas pixels, 101090 separated image/default inputs, source UV/material rejection; hostExecuted=false");
 }
}
