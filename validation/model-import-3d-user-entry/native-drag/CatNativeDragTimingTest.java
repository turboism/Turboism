package dev.turboism.validation.modelimport3d.nativeprobe;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
import javax.swing.SwingUtilities;
public final class CatNativeDragTimingTest {
 public static void main(String[] args)throws Exception {
  var threads=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();threads.setThreadAllocatedMemoryEnabled(true);
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  SwingUtilities.invokeLater(()->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new AssertionError(e);}});entered.await();
  Thread unblock=new Thread(()->{try{Thread.sleep(80);release.countDown();}catch(InterruptedException e){throw new AssertionError(e);}});unblock.start();
  long[] measured=CatNativeDragAgent.measureOnEdt(()->null,threads);unblock.join();
  if(measured[0]<0 || measured[1]<=0 || measured[2]<40_000_000 || measured[3]<measured[1]+measured[2])throw new AssertionError(java.util.Arrays.toString(measured));
  var output=new java.util.Properties();CatNativeDragAgent.timing(output,"step.1",measured,true);
  if(!output.getProperty("step.1.queueWaitNs").equals(Long.toString(measured[2])) || !output.getProperty("step.1.callerNs").equals(Long.toString(measured[3])))throw new AssertionError("trace metric association");
  var legacy=new java.util.Properties();CatNativeDragAgent.timing(legacy,"step.1",measured,false);
  if(legacy.size()!=2 || legacy.getProperty("step.1.queueWaitNs")!=null)throw new AssertionError("legacy metric schema changed");
  System.out.println("PASS real blocked EDT wait separated from action and complete caller time; trace/legacy fields isolated");
 }
}
