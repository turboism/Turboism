package dev.turboism.validation.modelimport3d.packaging;

import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.core.plugin.PluginJarContract;
import dev.turboism.sdk.plugin.PluginDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/** Offline only: use the bundled runtime's real content contract; never load the plugin or Cubism. */
public final class PluginJarCheck {
    private PluginJarCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("expected one probe JAR");
        try (JarFile jar = new JarFile(args[0])) {
            var metadata = jar.getJarEntry("META-INF/turboism/plugin.json");
            if (metadata == null || metadata.isDirectory()) throw new IllegalArgumentException("missing descriptor");
            PluginDescriptor descriptor;
            try (var input = jar.getInputStream(metadata)) {
                descriptor = new PluginDescriptorParser().parse(input);
            }
            List<String> entries = jar.stream().filter(e -> !e.isDirectory()).map(e -> e.getName()).toList();
            if (entries.stream().distinct().count() != entries.size()) {
                throw new IllegalArgumentException("duplicate JAR entries");
            }
            PluginJarContract.validate(descriptor, entries, args[0]);
            String base = descriptor.i18n().baseName() + ".properties";
            reject(descriptor, entries.stream().filter(e -> !e.equals(base)).toList(),
                    "PLUGIN_I18N_CATALOG_MISSING");
            String entrypoint = descriptor.entrypoints().get(0).replace('.', '/') + ".class";
            reject(descriptor, entries.stream().filter(e -> !e.equals(entrypoint)).toList(),
                    "PLUGIN_ENTRYPOINT_CLASS_MISSING");
            var extraCatalog = new ArrayList<>(entries);
            extraCatalog.add(descriptor.i18n().baseName() + "_undeclared.properties");
            reject(descriptor, extraCatalog, "PLUGIN_I18N_CATALOG_UNDECLARED");
            System.out.println("PACKAGING_CONTRACT PASS: actual JAR + 3 rejection controls; hostExecuted=false");
        } catch (PluginJarContract.PluginJarContractException failure) {
            System.err.println(failure.code() + ": " + failure.path());
            throw failure;
        }
    }

    private static void reject(PluginDescriptor descriptor, List<String> entries, String code) throws Exception {
        try {
            PluginJarContract.validate(descriptor, entries, "synthetic-rejection-control");
        } catch (PluginJarContract.PluginJarContractException failure) {
            if (!code.equals(failure.code())) throw new AssertionError("wrong rejection: " + failure.code(), failure);
            return;
        }
        throw new AssertionError("packaging defect was accepted: " + code);
    }
}
