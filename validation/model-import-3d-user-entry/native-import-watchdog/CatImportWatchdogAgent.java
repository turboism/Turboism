package dev.turboism.validation.modelimport3d.nativeprobe;
import java.lang.management.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
/** Dedicated read-only thread observer. Never calls Editor APIs or sends input. */
public final class CatImportWatchdogAgent {
 public static void premain(String ignored)throws Exception {
  if(!java.util.List.of("cat-adaptive-save","cat-cost-save").contains(System.getProperty("turboism.validation.mi3d.mode","")))throw new IllegalStateException("dedicated adaptive import profile only");
  Path jar=Path.of(CatImportWatchdogAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
  if(!jar.getParent().getFileName().toString().equals("agents"))throw new IllegalStateException("task-owned agent directory only");
  Path state=jar.getParent().getParent().resolve("turboism-home/state/dev.turboism.validation.model-import-3d-user-entry");
  Thread observer=new Thread(()->observe(state),"mi3d-import-watchdog");observer.setDaemon(true);observer.start();
  System.err.println("CAT_IMPORT_WATCHDOG_READY");
 }
 static void observe(Path state) {
  try {
   long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(30),lastDump=0;int sequence=0;String previous="";
   while(System.nanoTime()<deadline) {
    if(Files.exists(state.resolve("result.properties")))return;
    Path phase=state.resolve("mesh-editing-stage.txt");String current=Files.isRegularFile(phase)?Files.readString(phase).strip():"BEFORE_DRIVER_STAGE";
    long now=System.nanoTime();
    if(!current.equals(previous) || now-lastDump>TimeUnit.SECONDS.toNanos(30)) {
     if(Files.isDirectory(state)) {
      Path out=state.resolve("import-watchdog");Files.createDirectories(out);
      var text=new StringBuilder("SCHEMA=mi3d-import-watchdog-v1\nPHASE=").append(current).append("\nTIME=").append(java.time.Instant.now()).append('\n');
      for(var thread:ManagementFactory.getThreadMXBean().dumpAllThreads(true,true)) {
       text.append("THREAD ").append(thread.getThreadName()).append(" id=").append(thread.getThreadId()).append(" state=").append(thread.getThreadState())
           .append(" lock=").append(thread.getLockInfo()).append(" owner=").append(thread.getLockOwnerName()).append('\n');
       for(var frame:thread.getStackTrace())text.append("  at ").append(frame).append('\n');
       for(var lock:thread.getLockedMonitors())text.append("  owns monitor ").append(lock).append('\n');
       for(var lock:thread.getLockedSynchronizers())text.append("  owns synchronizer ").append(lock).append('\n');
      }
      Files.writeString(out.resolve(String.format("threads-%04d.txt",++sequence)),text.toString(),StandardOpenOption.CREATE_NEW);
      lastDump=now;
     }
     previous=current;
    }
    Thread.sleep(1000);
   }
  } catch(Exception failure) { failure.printStackTrace(); }
 }
}
