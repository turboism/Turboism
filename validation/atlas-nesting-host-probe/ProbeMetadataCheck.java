import dev.turboism.core.descriptor.PluginDescriptorParser;
import java.util.jar.JarFile;

/** Use the actual host parser before spending a managed host slot. */
class ProbeMetadataCheck {
    public static void main(String[] args) throws Exception {
        try (var jar = new JarFile(args[0]);
                var input = jar.getInputStream(jar.getJarEntry("META-INF/turboism/plugin.json"))) {
            var descriptor = new PluginDescriptorParser().parse(input);
            dev.turboism.core.plugin.PluginJarContract.validate(descriptor,
                    jar.stream().filter(e -> !e.isDirectory()).map(java.util.zip.ZipEntry::getName).toList(), args[0]);
            System.out.println("PASS host metadata and JAR contracts: " + descriptor.id());
        }
    }
}
