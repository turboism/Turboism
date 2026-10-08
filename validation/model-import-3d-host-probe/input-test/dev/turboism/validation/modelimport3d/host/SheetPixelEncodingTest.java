package dev.turboism.validation.modelimport3d.host;
import dev.turboism.sdk.cubism.model.CubismRenderedPixels;
import java.io.*;
import javax.imageio.ImageIO;
public final class SheetPixelEncodingTest {
 public static void main(String[] args)throws Exception {
  int[] pixels=new int[1024*1024];
  for(int y=0;y<1024;y++)for(int x=0;x<1024;x++)pixels[y*1024+x]=((x%3==0?0:x%3==1?127:255)<<24)|((x&255)<<16)|((y&255)<<8)|((x+y)&255);
  byte[] encoded=SheetPixelCapture.encode(new CubismRenderedPixels(1024,1024,pixels));
  var decoded=ImageIO.read(new ByteArrayInputStream(encoded));
  if(decoded.getWidth()!=1024 || decoded.getHeight()!=1024)throw new AssertionError();
  for(int y=0;y<1024;y++)for(int x=0;x<1024;x++)if(decoded.getRGB(x,y)!=pixels[y*1024+x])throw new AssertionError("pixel orientation/channel/alpha");
  try{SheetPixelCapture.encode(new CubismRenderedPixels(1,1,new int[1]));throw new AssertionError("dimensions");}
  catch(IllegalStateException expected){}
  int index=0;for(int yaw:new int[]{-30,0,30})for(int pitch:new int[]{-30,0,30}) {
   var pose=SheetPixelCapture.POSES.get(index++);
   if(pose.yaw()!=yaw || pose.pitch()!=pitch)throw new AssertionError("pose inventory");
  }
  System.out.println("PASS 1048576 exact ARGB pixels, dimension rejection and nine pose inventory; nativeRenderExecuted=false");
 }
}
