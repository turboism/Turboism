package dev.turboism.i18n;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

final class PluginCatalogResources {

    private PluginCatalogResources() {
    }

    static List<byte[]> readLocal(
        final ClassLoader pluginClassLoader,
        final String resourcePath
    ) throws IOException {
        if (!(pluginClassLoader instanceof URLClassLoader urlClassLoader)) {
            throw new IOException("Plugin classloader does not expose isolated local URLs.");
        }
        final List<byte[]> resources = new ArrayList<>();
        for (URL url : urlClassLoader.getURLs()) {
            final Path root = filePath(url);
            if (Files.isDirectory(root)) {
                final Path resource = root.resolve(resourcePath).normalize();
                if (!resource.startsWith(root.normalize())) {
                    throw new IOException("Catalog resource escaped the plugin root.");
                }
                if (Files.isRegularFile(resource)) {
                    resources.add(Files.readAllBytes(resource));
                }
            } else if (Files.isRegularFile(root)) {
                try (JarFile jar = new JarFile(root.toFile())) {
                    final JarEntry entry = jar.getJarEntry(resourcePath);
                    if (entry != null && !entry.isDirectory()) {
                        try (var stream = jar.getInputStream(entry)) {
                            resources.add(stream.readAllBytes());
                        }
                    }
                }
            }
        }
        return List.copyOf(resources);
    }

    private static Path filePath(final URL url) throws IOException {
        if (!"file".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Unsupported plugin classloader URL protocol.");
        }
        try {
            final URI uri = url.toURI();
            return Path.of(uri).toAbsolutePath().normalize();
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new IOException("Invalid plugin classloader URL.", exception);
        }
    }
}
