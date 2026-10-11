package dev.turboism.validation.modelimport3d.host;
import java.nio.file.*;
import java.util.*;
public final class AtlasRenderPhaseTest {
 public static void main(String[] args)throws Exception {
  var prepared=CubePersistenceInput.prepare(Files.readAllBytes(Path.of(args[0])));
  int reached=0;
  for(String phase:List.of("atlas-before-save","atlas-after-save","atlas-reopen")) {
   int[] reads={0};
   try {CubePersistenceRender.sample(phase,prepared,()->{reads[0]++;throw new IllegalStateException("MODEL_SENTINEL");},
    ()->{throw new AssertionError("history must not be reached");},value->{throw new AssertionError("restore must not be reached");},
    Path.of(args[1]),new LinkedHashMap<>());throw new AssertionError("unexpected capture success");}
   catch(Exception expected) {
    Throwable cause=expected;while(cause.getCause()!=null)cause=cause.getCause();
    System.out.println(phase+" reads="+reads[0]+" terminal="+cause.getMessage());
    if(reads[0]==1 && "MODEL_SENTINEL".equals(cause.getMessage()))reached++;
   }
  }
  if(reached!=3)throw new AssertionError("valid atlas persistence phases rejected before model read: "+reached+"/3");
  System.out.println("Atlas render phase dispatch PASS; no model or host writes");
 }
}
