package dev.turboism.pluginmanagement;
// LAB ONLY. Real manual same-version reinstall intent, not catalog/release/CI acceptance.
import java.nio.file.*;
import java.util.*;
public class HostRecoveryFixture {
 public static void main(String[] args) throws Exception {
  Path home=Path.of("D:/turboism-test/issue50-recovery-f173f64");
  if(args[0].equals("seed")) {
   var pending=new PendingPluginOperations(home);
   if(!pending.operations().isEmpty())throw new IllegalStateException("Preexisting intent");
   for(String name:List.of("clipmask-viewer.jar","command-palette.jar"))
    if(!pending.stage(home.resolve("plugins").resolve(name)).accepted())throw new AssertionError("stage failed");
   var last=pending.operations().get(pending.operations().size()-1);
   Path staged=Path.of(last.stagedJar());byte[] data=Files.readAllBytes(staged);data[data.length-1]^=1;Files.write(staged,data);
   System.out.println("MANUAL_BATCH_CORRUPTED_LAST: "+last.pluginId());
  } else {
   try(var service=new RuntimePluginManagementService(home,(java.util.function.Supplier<Optional<Path>>)Optional::empty,List::of)) {
    var review=service.pendingChanges();
    System.out.println("NATIVE_SERVICE_REVIEW: "+review.code()+" entries="+review.changes().size());
    if(args[0].equals("blocked")) {
     if(!review.code().equals("PLUGIN_FRAMEWORK_RECOVERY_PENDING"))throw new AssertionError(review);
     var result=service.discardPendingChanges(review.snapshot());
     if(result.accepted()||!result.code().equals("PLUGIN_FRAMEWORK_RECOVERY_PENDING"))throw new AssertionError(result);
     System.out.println("FRAMEWORK_RECOVERY_NOT_CANCELLED");
    } else if(!review.code().equals("PLUGIN_PENDING_FAILED"))throw new AssertionError(review);
   }
  }
 }
}
