import dev.turboism.validation.mcp.CoreOwnedMocProbe;
import dev.turboism.sdk.plugin.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.Properties;
/** Startup failure publishes explicit evidence without invoking any native Core method. */
public final class CoreOwnedMocStartupFailureSelfTest {
 /** Runs the startup-failure regression against the built task probe. */
 public static void main(String[] args) throws Exception {
  PluginLogger logger=(PluginLogger)Proxy.newProxyInstance(PluginLogger.class.getClassLoader(),new Class<?>[]{PluginLogger.class},(p,m,a)->null);
  PluginContext context=(PluginContext)Proxy.newProxyInstance(PluginContext.class.getClassLoader(),new Class<?>[]{PluginContext.class},(p,m,a)-> {
   if(m.getName().equals("logger"))return logger;
   throw new AssertionError("Startup failure must not call production Core or other SDK services: "+m.getName());
  });
  Path root=Files.createTempDirectory("mundane-moc-notstarted-");
  System.setProperty("turboism.validation.runId","startup-failure-test");
  System.setProperty("turboism.validation.hostVersion","5304");
  CoreOwnedMocProbe.notStarted(context,root,"fixture readiness timeout");
  Properties result=new Properties();try(var in=Files.newInputStream(root.resolve("core-owned-moc-result.properties"))){result.load(in);}
  if(!"FAIL".equals(result.getProperty("status")) || !"NOT_STARTED".equals(result.getProperty("nativeAssertions")) || !"startup-failure-test".equals(result.getProperty("runId")))throw new AssertionError(result);
  Files.delete(root.resolve("core-owned-moc-result.properties"));Files.delete(root);
  System.out.println("PASS startup failure publishes NOT_STARTED with no Core invocation");
 }
}
