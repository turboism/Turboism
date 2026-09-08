package dev.turboism.preview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreviewPluginDiscoveryTest {

    private static final String PLUGIN_ID = "dev.example.duplicate";

    @TempDir
    Path temporary;

    @Test
    void selectsHighestSemanticVersionAndReportsEveryLosingArtifact() throws Exception {
        final Path home = temporary.resolve("home");
        final Path plugins = home.resolve("plugins");
        writePlugin(plugins.resolve("a-lower.jar"), "2.9.0");
        writePlugin(plugins.resolve("m-highest.jar"), "10.0.0");
        writePlugin(plugins.resolve("z-lowest.jar"), "1.0.0");
        final List<LocalPluginRuntime.PluginFailure> failures = new ArrayList<>();

        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final PreviewPluginCandidate winner = new PreviewPluginDiscovery(plugins, log)
                .discover(failures)
                .get(PLUGIN_ID);

            assertEquals("10.0.0", winner.descriptor().version());
            assertEquals("m-highest.jar", winner.jar().getFileName().toString());
            assertEquals(List.of("a-lower.jar", "z-lowest.jar"), failureFilenames(failures));
            assertEquals(List.of("DUPLICATE_PLUGIN_ID", "DUPLICATE_PLUGIN_ID"),
                failures.stream().map(LocalPluginRuntime.PluginFailure::code).toList());
        }
    }

    @Test
    void usesLexicographicallyFirstFilenameToBreakEqualVersionTie() throws Exception {
        final Path home = temporary.resolve("tie-home");
        final Path plugins = home.resolve("plugins");
        writePlugin(plugins.resolve("z-plugin.jar"), "3.4.5");
        writePlugin(plugins.resolve("a-plugin.jar"), "3.4.5");
        final List<LocalPluginRuntime.PluginFailure> failures = new ArrayList<>();

        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final PreviewPluginCandidate winner = new PreviewPluginDiscovery(plugins, log)
                .discover(failures)
                .get(PLUGIN_ID);

            assertEquals("a-plugin.jar", winner.jar().getFileName().toString());
            assertEquals(List.of("z-plugin.jar"), failureFilenames(failures));
            assertEquals("DUPLICATE_PLUGIN_ID", failures.get(0).code());
        }
    }

    private static List<String> failureFilenames(
        final List<LocalPluginRuntime.PluginFailure> failures
    ) {
        return failures.stream()
            .map(LocalPluginRuntime.PluginFailure::jar)
            .map(path -> path.getFileName().toString())
            .toList();
    }

    private static void writePlugin(final Path jar, final String version) throws Exception {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            add(output, "dev/example/Fixture.class", new byte[] {0});
            add(output, "META-INF/turboism/plugin.json",
                descriptor(version).getBytes(StandardCharsets.UTF_8));
            add(output, "META-INF/turboism/i18n/messages.properties", new byte[0]);
        }
    }

    private static String descriptor(final String version) {
        return """
            {"format":"turboism.plugin.meta","schemaVersion":2,"id":"%s","name":"Fixture","version":"%s",
            "description":"test","entrypoints":["dev.example.Fixture"],"turboismApi":"[0.1.0,0.2.0)",
            "authors":[{"name":"Tests"}],"license":"Test","website":"https://turboism.dev","resources":[],
            "i18n":{"baseName":"META-INF/turboism/i18n/messages","locales":[]},"dependencies":[],"permissions":[],
            "capabilities":[],"environment":{"requiresCubism":false,"ui":"none"}}
            """.formatted(PLUGIN_ID, version);
    }

    private static void add(
        final JarOutputStream output,
        final String name,
        final byte[] bytes
    ) throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }
}
