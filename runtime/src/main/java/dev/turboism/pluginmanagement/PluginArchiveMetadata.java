package dev.turboism.pluginmanagement;

import dev.turboism.core.descriptor.PluginDescriptorParser;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.JarFile;

record PluginArchiveMetadata(String id, String name, String version, String description) {
    private static final String DESCRIPTOR = "META-INF/turboism/plugin.json";

    static Optional<PluginArchiveMetadata> read(final Path path) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return Optional.empty();
            try (JarFile jar = new JarFile(path.toFile())) {
                final var entry = jar.getJarEntry(DESCRIPTOR);
                if (entry == null || entry.isDirectory()) return Optional.empty();
                try (InputStream input = jar.getInputStream(entry)) {
                    final var descriptor = new PluginDescriptorParser().parse(input);
                    return Optional.of(new PluginArchiveMetadata(
                        descriptor.id(), descriptor.name(), descriptor.version(), descriptor.description()
                    ));
                }
            }
        } catch (Exception failure) {
            return Optional.empty();
        }
    }
}
