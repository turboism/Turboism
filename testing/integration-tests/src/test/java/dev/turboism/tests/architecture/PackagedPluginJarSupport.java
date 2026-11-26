package dev.turboism.tests.architecture;

import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.core.event.EventSubscriberDescriptor;
import dev.turboism.core.event.GeneratedSubscriberCatalogLoader;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.TurboismPlugin;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Loads packaged first-party plugin JARs in isolated child classloaders so cross-plugin tests
 * exercise real manifests, generated subscriber catalogs, and classloader boundaries.
 */
final class PackagedPluginJarSupport {

    static final String PARAMETER_LOCK_BUILD_DIR_PROP = "parameterLockBuildDir";
    static final String PARAMETER_SYNC_BUILD_DIR_PROP = "parameterSyncBuildDir";
    static final String SELECTION_BRUSH_BUILD_DIR_PROP = "selectionBrushBuildDir";
    static final String GEOMETRIC_MESH_LAYOUT_BUILD_DIR_PROP = "geometricMeshLayoutBuildDir";

    private static final String LIBS_SUBDIR = "libs";
    private static final String JAR_SUFFIX = ".jar";
    private static final String DESCRIPTOR_RESOURCE = "META-INF/turboism/plugin.json";

    private PackagedPluginJarSupport() {}

    static PluginLocalization passthroughLocalization() {
        return PassthroughLocalization.INSTANCE;
    }

    static LoadedJarPlugin loadFromSystemProperty(final String buildDirProperty) throws Exception {
        final String buildDir = Objects.requireNonNull(
                System.getProperty(buildDirProperty), () -> "Missing system property: " + buildDirProperty);
        return loadJar(resolveBuiltJar(Path.of(buildDir)));
    }

    static LoadedJarPlugin loadJar(final Path jarPath) throws Exception {
        final URLClassLoader loader =
                new URLClassLoader(new URL[] {jarPath.toUri().toURL()}, TurboismPlugin.class.getClassLoader());
        try {
            final PluginDescriptor descriptor = readDescriptor(loader, jarPath);
            final List<TurboismPlugin> entrypoints = instantiateEntrypoints(descriptor, loader);
            final List<EventSubscriberDescriptor> subscribers =
                    new GeneratedSubscriberCatalogLoader().inspect(entrypoints, loader);
            return new LoadedJarPlugin(descriptor, loader, entrypoints, subscribers);
        } catch (Exception | Error failure) {
            loader.close();
            throw failure;
        }
    }

    private static Path resolveBuiltJar(final Path buildDir) throws IOException {
        final Path libs = buildDir.resolve(LIBS_SUBDIR);
        try (var stream = Files.list(libs)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(JAR_SUFFIX))
                    .min(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow(() -> new IOException("No built plugin JAR found under " + libs));
        }
    }

    private static PluginDescriptor readDescriptor(final URLClassLoader loader, final Path jarPath) throws Exception {
        final URL url = loader.findResource(DESCRIPTOR_RESOURCE);
        if (url == null) {
            throw new IOException("Missing " + DESCRIPTOR_RESOURCE + " in " + jarPath);
        }
        try (InputStream stream = url.openStream()) {
            return new PluginDescriptorParser().parse(stream);
        }
    }

    private static List<TurboismPlugin> instantiateEntrypoints(
            final PluginDescriptor descriptor, final ClassLoader loader) throws Exception {
        final List<TurboismPlugin> instances = new ArrayList<>();
        for (String className : descriptor.entrypoints()) {
            final Class<?> clazz = Class.forName(className, true, loader);
            instances.add(
                    TurboismPlugin.class.cast(clazz.getDeclaredConstructor().newInstance()));
        }
        return List.copyOf(instances);
    }

    record LoadedJarPlugin(
            PluginDescriptor descriptor,
            URLClassLoader classLoader,
            List<TurboismPlugin> entrypoints,
            List<EventSubscriberDescriptor> subscribers)
            implements AutoCloseable {

        TurboismPlugin primaryEntrypoint() {
            return entrypoints.get(0);
        }

        @Override
        public void close() throws IOException {
            classLoader.close();
        }
    }

    private enum PassthroughLocalization implements PluginLocalization {
        INSTANCE;

        @Override
        public Locale locale() {
            return Locale.ROOT;
        }

        @Override
        public String text(final String key) {
            return Objects.requireNonNull(key, "key");
        }

        @Override
        public String format(final String key, final Object... arguments) {
            return Objects.requireNonNull(key, "key");
        }

        @Override
        public boolean contains(final String key) {
            return key != null && !key.isBlank();
        }
    }
}
