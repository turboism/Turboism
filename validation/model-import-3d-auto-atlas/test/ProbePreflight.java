import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.core.plugin.PluginJarContract;
import java.nio.file.Path;
import java.util.jar.JarFile;

/** Checks the same descriptor and JAR contract used by local runtime discovery. */
public final class ProbePreflight {
    private ProbePreflight() {}
    public static void main(String[] args) throws Exception {
        try (var jar = new JarFile(Path.of(args[0]).toFile())) {
            var descriptor = new PluginDescriptorParser().parse(
                    jar.getInputStream(jar.getJarEntry("META-INF/turboism/plugin.json")));
            PluginJarContract.validate(descriptor,
                    jar.stream().filter(entry -> !entry.isDirectory()).map(entry -> entry.getName()).toList(), args[0]);
        }
        System.out.println("PASS: runtime descriptor and JAR content contract");
    }
}
