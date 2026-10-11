package dev.turboism.validation.modelimport3d.nativeprobe;
public final class CatNativeDragDomainTest {
 public static void main(String[] args) {
  System.clearProperty("turboism.validation.mi3d.editableDrag");
  CatNativeDragAgent.requireSourceDomain(512);reject(615);
  System.setProperty("turboism.validation.mi3d.editableDrag","true");
  System.setProperty("turboism.validation.mi3d.mode","cat-cost-drag");
  CatNativeDragAgent.requireSourceDomain(615);reject(512);
  System.setProperty("turboism.validation.mi3d.mode","cat-optimized-drag");reject(615);
  System.out.println("PASS legacy512/new615 inventory isolation and mixed-profile refusal");
 }
 static void reject(int count) {try {CatNativeDragAgent.requireSourceDomain(count);} catch(IllegalStateException expected){return;}throw new AssertionError("invalid source domain admitted: "+count);}
}
