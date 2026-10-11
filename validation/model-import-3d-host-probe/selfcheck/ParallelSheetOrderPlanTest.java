package dev.turboism.validation.modelimport3d.host;
import java.nio.file.*;
import java.util.*;
public final class ParallelSheetOrderPlanTest {
 static int checks;
 static void check(boolean v){checks++;if(!v)throw new AssertionError();}
 static void reject(Runnable r){try{r.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError("invalid proof admitted");}
 static GlbSurfaceReader.Surface copy(GlbSurfaceReader.Surface s,List<SurfaceProjection.Vertex> p,List<Integer> indices,boolean twoSided){
  return new GlbSurfaceReader.Surface(s.node(),s.mesh(),s.primitive(),s.material(),p,s.sourceNormals(),s.textureUvs(),indices,s.baseColor(),twoSided,s.reflected(),s.texture());
 }
 public static void main(String[] args)throws Exception {
  var surfaces=GlbSurfaceReader.read(Files.readAllBytes(Path.of(args[0])));var camera=CubeGeometryProbe.CAMERA;
  var plan=ParallelSheetOrderPlan.compile(surfaces,camera,30,30,1.5);
  check(plan.leftSurface()==0 && plan.separationLowerBound().signum()>0);
  check(plan.drawOrder(0,-30)==501 && plan.drawOrder(1,-30)==499);
  check(plan.drawOrder(0,30)==499 && plan.drawOrder(1,30)==501);
  check(plan.drawOrder(0,0)==500 && plan.drawOrder(1,0)==500);
  var reverse=ParallelSheetOrderPlan.compile(List.of(surfaces.get(1),surfaces.get(0)),camera,30,30,1.5);
  check(reverse.leftSurface()==1 && reverse.drawOrder(1,-30)==501);
  reject(()->plan.drawOrder(2,0));reject(()->plan.drawOrder(0,31));reject(()->plan.drawOrder(0,Double.NaN));
  reject(()->ParallelSheetOrderPlan.compile(surfaces,camera,30,30,15));
  reject(()->ParallelSheetOrderPlan.compile(surfaces,camera,30,31,1.5));
  reject(()->ParallelSheetOrderPlan.compile(surfaces,camera,30,30,0));
  reject(()->ParallelSheetOrderPlan.compile(List.of(surfaces.get(0),surfaces.get(0)),camera,30,30,1.5));
  var s=surfaces.get(0);
  reject(()->ParallelSheetOrderPlan.compile(List.of(copy(s,s.positions(),s.indices(),false),surfaces.get(1)),camera,30,30,1.5));
  reject(()->ParallelSheetOrderPlan.compile(List.of(copy(s,s.positions(),List.of(0,1,2,0,1,2),true),surfaces.get(1)),camera,30,30,1.5));
  var points=new ArrayList<>(s.positions());points.set(0,new SurfaceProjection.Vertex(-.3,-.75,-1));
  reject(()->ParallelSheetOrderPlan.compile(List.of(copy(s,points,s.indices(),true),surfaces.get(1)),camera,30,30,1.5));
  var zeros=List.of(new SurfaceProjection.Vertex(-.25,-0d,-1),new SurfaceProjection.Vertex(-.25,0d,-1),
      new SurfaceProjection.Vertex(-.25,1,1),new SurfaceProjection.Vertex(-.25,1,-1));
  reject(()->ParallelSheetOrderPlan.compile(List.of(copy(s,zeros,s.indices(),true),surfaces.get(1)),camera,30,30,1.5));
  System.out.println("Parallel sheet order proof: "+checks+" checks PASS; separationLowerBound="+plan.separationLowerBound()+"; not yet importer/native support");
 }
}
