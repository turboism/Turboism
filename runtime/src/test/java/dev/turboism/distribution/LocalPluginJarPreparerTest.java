package dev.turboism.distribution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPluginJarPreparerTest {
    @TempDir Path root;

    @Test
    void stagesAValidDirectPluginJar() throws Exception {
        final Path source = root.resolve("sample.jar");
        Files.write(source, pluginJar("example.plugin", "1.0.0"));

        final LocalPluginJarPreparer.Prepared result = assertInstanceOf(
            LocalPluginJarPreparer.Prepared.class,
            new LocalPluginJarPreparer().prepare(source, root.resolve("staging"))
        );

        assertEquals("example.plugin", result.value().descriptor().id());
        assertEquals("1.0.0", result.value().descriptor().version());
        assertTrue(Files.isRegularFile(result.value().stagedJar()));
        assertEquals(Files.size(source), result.value().jarSize());
        assertEquals(64, result.value().jarSha256().length());
    }

    @Test
    void rejectsSourceMutationAfterSnapshot() throws Exception {
        final Path source = root.resolve("sample.jar");
        Files.write(source, pluginJar("example.plugin", "1.0.0"));
        final PackageAccess mutating = new PackageAccess() {
            @Override public void afterInitialHash(final Path path) throws java.io.IOException {
                Files.writeString(path, "changed");
            }
        };

        final LocalPluginJarPreparer.Preparation result =
            new LocalPluginJarPreparer(mutating).prepare(source, root.resolve("staging"));

        final LocalPluginJarPreparer.PreparationRejected rejected = assertInstanceOf(
            LocalPluginJarPreparer.PreparationRejected.class, result);
        assertEquals(DistributionErrors.PACKAGE_CHANGED, rejected.code());
        assertFalse(hasJar(root.resolve("staging")));
    }

    @Test
    void rejectsSymbolicLinkSource() throws Exception {
        final Path target = root.resolve("target.jar");
        final Path source = root.resolve("linked.jar");
        Files.write(target, pluginJar("example.plugin", "1.0.0"));
        try {
            Files.createSymbolicLink(source, target);
        } catch (UnsupportedOperationException failure) {
            return;
        }

        final LocalPluginJarPreparer.Preparation result =
            new LocalPluginJarPreparer().prepare(source, root.resolve("staging"));

        assertInstanceOf(LocalPluginJarPreparer.PreparationRejected.class, result);
        assertFalse(hasJar(root.resolve("staging")));
    }

    private static boolean hasJar(final Path directory) throws Exception {
        if (!Files.isDirectory(directory)) return false;
        try (var paths = Files.list(directory)) {
            return paths.anyMatch(path -> path.getFileName().toString().endsWith(".jar"));
        }
    }

    private static byte[] pluginJar(final String id, final String version) throws Exception {
        final String descriptor = "{\"format\":\"turboism.plugin.meta\",\"schemaVersion\":2,"
            + "\"id\":\"" + id + "\",\"name\":\"Example\",\"version\":\"" + version + "\","
            + "\"description\":\"Example\",\"entrypoints\":[\"example.Plugin\"],"
            + "\"turboismApi\":\"[0.1.0,0.2.0)\",\"authors\":[{\"name\":\"Test\"}],"
            + "\"license\":\"Test\",\"website\":\"https://example.test\",\"resources\":[],"
            + "\"i18n\":{\"baseName\":\"META-INF/turboism/i18n/messages\",\"locales\":[]},"
            + "\"dependencies\":[],\"permissions\":[],\"capabilities\":[],"
            + "\"environment\":{\"requiresCubism\":false,\"ui\":\"none\"}}";
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            add(jar, "META-INF/turboism/plugin.json", descriptor.getBytes(StandardCharsets.UTF_8));
            add(jar, "META-INF/turboism/i18n/messages.properties",
                "plugin.name=Example\nplugin.description=Example\n".getBytes(StandardCharsets.UTF_8));
            add(jar, "example/Plugin.class", new byte[]{0});
        }
        return output.toByteArray();
    }

    private static void add(final JarOutputStream jar, final String name, final byte[] bytes) throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(bytes);
        jar.closeEntry();
    }
}
