package dev.turboism.diagnostic;
import com.live2d.cubism.doc.model.*;
import com.live2d.cubism.doc.model.drawable.artMesh.*;
import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.*;
import com.live2d.graphics3d.type.GVector2;
import com.live2d.serialize.UUSerialize;
import java.io.*;import java.util.*;import java.lang.management.*;

/** Diagnostic only: invokes the same official interpolation calculator and blend
 * operations, bypassing temporary per-form point references. No installed files
 * or user models are modified. Not connected to any Editor hook. */
public final class FastMeshKernelProof {
 static boolean fast(List<? extends ACForm> target,List<? extends ACForm> source,List<CExtendedInterpolationExtension.b> keys,ExtendedInterpolationType type,float scale) {
  if(target==null || source==null || keys==null || type==null || target.isEmpty() || source.isEmpty() || keys.size()!=target.size())return false;
  // Refuse unsupported structures before any blend mutates a target.
  if(keys.get(0)==null || keys.get(keys.size()-1)==null || keys.get(0).b() || keys.get(keys.size()-1).b())return false;
  for(var key:keys)if(key==null || (key.b() && (key.c()<0 || key.c()>=source.size()-1 || !Float.isFinite(key.d()))))return false;
  int size=-1;var owner=target.get(0) instanceof CArtMeshForm f?f.getSource():null;
  if(owner==null)return false;
  var dst=new float[target.size()][];var src=new float[source.size()][];
  for(int i=0;i<target.size();i++){
   if(!(target.get(i) instanceof CArtMeshForm f) || f.getSource()!=owner)return false;
   dst[i]=f.getPositions();if(size<0)size=dst[i].length;if(dst[i].length!=size || size%2!=0)return false;
  }
  for(int i=0;i<source.size();i++){
   if(!(source.get(i) instanceof CArtMeshForm f) || f.getSource()!=owner || f.getPositions().length!=size)return false;
   src[i]=f.getPositions();for(int j=0;j<target.size();j++)if(keys.get(j).b() && src[i]==dst[j])return false;
  }
  var knots=new ArrayList<Float>();for(var key:keys)if(!key.b())knots.add(key.a()*scale);
  if(knots.size()!=source.size())return false;
  var vectors=new ArrayList<GVector2>();for(int i=0;i<src.length;i++)vectors.add(new GVector2());
  ACForm left=target.get(0);
  for(int i=1;i<target.size();i++) {
   var key=keys.get(i);
   if(!key.b()){left=target.get(i);continue;}
   int right=i+1;while(right<keys.size() && keys.get(right).b())right++;
   if(right==keys.size())throw new IllegalStateException("missing right source key");
   var form=target.get(i);float weight=key.d();form.zero();form.blend(left,1f-weight);form.blend(target.get(right),weight);form.postBlend();
  }
  var output=new GVector2();
  for(int offset=0;offset<size;offset+=2) {
   for(int i=0;i<src.length;i++)vectors.get(i).set(src[i][offset],src[i][offset+1]);
   var calculator=type.b();calculator.a(vectors,true,null,knots);
   for(int i=0;i<keys.size();i++)if(keys.get(i).b()) {
    var key=keys.get(i);var result=calculator.a(key.c(),key.d(),output);
    if(result!=null) {
     // Match moveToOnLocal(value, 1f), including signed-zero arithmetic
     // and one EditVersion increment for every written point.
     dst[i][offset]=dst[i][offset]*0f+result.getX()*1f;
     dst[i][offset+1]=dst[i][offset+1]*0f+result.getY()*1f;
     target.get(i).setUpdated();
    }
   }
  }
  return true;
 }
 record Line(List<CArtMeshForm> all,List<CArtMeshForm> anchors,List<CExtendedInterpolationExtension.b> keys) { }
 static Line line(CArtMeshSource mesh) {
  int floats=mesh.getDefaultKeyForm().getPositions().length;var all=new ArrayList<CArtMeshForm>();var anchors=new ArrayList<CArtMeshForm>();var keys=new ArrayList<CExtendedInterpolationExtension.b>();
  var guid=new com.live2d.type.CParameterGuid(new UUID(0,1));
  for(int i=0;i<33;i++){
   boolean inserted=i%4!=0;int left=i/4;float fraction=(i%4)/4f;float[] xy=new float[floats];
   for(int p=0;p<floats;p+=2){double angle=Math.toRadians(-180+i*11.25);xy[p]=(float)(40*Math.cos(angle)+p*.123);xy[p+1]=(float)(25*Math.sin(angle)-p*.234);}
   var form=new CArtMeshForm(mesh,500+i,1-i*.005f,xy,new com.live2d.doc.CoordType("DeformerLocal"));all.add(form);if(!inserted)anchors.add(form);
   keys.add(new CExtendedInterpolationExtension.b(guid,-180+i*11.25f,inserted,left,fraction));
  }
  return new Line(all,anchors,keys);
 }
 static long allocated(){return ((com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().getId());}
 public static void main(String[] args){try{run(args);System.exit(0);}catch(Throwable t){t.printStackTrace();System.exit(1);}}
 static void run(String[] args) {
  var model=(CModelSource)UUSerialize.INSTANCE.deserializeFromFile(new File(args[0]),new HashMap<>(),-1,false);model.setup();var mesh=model.getAllArtMeshes().get(0);var extension=(CExtendedInterpolationExtension)mesh.getExtensions().stream().filter(x->x instanceof CExtendedInterpolationExtension).findFirst().orElseThrow();
  ((com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean()).setThreadAllocatedMemoryEnabled(true);
  for(var type:ExtendedInterpolationType.values()) {
   var original=line(mesh);var optimized=line(mesh);
   for(int round=0;round<20;round++) {
    // A user drag changes one ordinary keyform, while all other poses stay intact.
    for(var line:List.of(original,optimized)){var form=line.anchors().get(round%9);var xy=form.getPositions();for(int i=0;i<xy.length;i+=2){xy[i]+=.125f;xy[i+1]-=.0625f;}form.setUpdated();}
    extension.updateInterpolatedForms_common(original.all(),original.anchors(),original.keys(),type,1f);
    if(!fast(optimized.all(),optimized.anchors(),optimized.keys(),type,1f))throw new IllegalStateException("fast path refused");
    for(int i=0;i<33;i++) {
     var a=original.all().get(i);var b=optimized.all().get(i);
     if(a.getDrawOrder()!=b.getDrawOrder() || Float.floatToRawIntBits(a.getOpacity())!=Float.floatToRawIntBits(b.getOpacity()) || a.getEditVersion()!=b.getEditVersion())throw new IllegalStateException("metadata mismatch "+type+" form="+i);
     for(int p=0;p<a.getPositions().length;p++)if(Float.floatToRawIntBits(a.getPositions()[p])!=Float.floatToRawIntBits(b.getPositions()[p]))throw new IllegalStateException("position bits differ "+type+" round="+round+" form="+i+" scalar="+p+" official="+a.getPositions()[p]+" fast="+b.getPositions()[p]);
    }
   }
   int repeats=1000;
   long allocation=allocated(),time=System.nanoTime();for(int repeat=0;repeat<repeats;repeat++)extension.updateInterpolatedForms_common(original.all(),original.anchors(),original.keys(),type,1f);
   long originalNs=System.nanoTime()-time,originalBytes=allocated()-allocation;
   allocation=allocated();time=System.nanoTime();for(int repeat=0;repeat<repeats;repeat++)fast(optimized.all(),optimized.anchors(),optimized.keys(),type,1f);
   long fastNs=System.nanoTime()-time,fastBytes=allocated()-allocation;
   System.out.println("[CAT_PERF_KERNEL] type="+type.name()+" exactPositionBitsAndMetadata=true rounds=20 points="+mesh.getDefaultKeyForm().getPositions().length/2+" repeats="+repeats+" originalNs="+originalNs+" fastNs="+fastNs+" originalBytes="+originalBytes+" fastBytes="+fastBytes);
  }
  System.out.println("[CAT_PERF_KERNEL] PASS_DIAGNOSTIC_NOT_EDITOR_FIX");
 }
}
