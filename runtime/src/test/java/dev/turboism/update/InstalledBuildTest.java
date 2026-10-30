package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class InstalledBuildTest {

    @Test
    void firstPartyPluginJarsMatchTheReleasePluginManifest() throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle.kts"))) {
            root = root.getParent();
        }
        assertNotNull(root, "the release plugin manifest is checked against this source tree");
        final Set<String> expected = Files.readAllLines(root.resolve("packaging/release-plugins.txt")).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .map(line -> line.substring(line.lastIndexOf(':') + 1) + ".jar")
                .collect(Collectors.toSet());
        assertEquals(expected, InstalledBuild.FIRST_PARTY_PLUGIN_JARS);
    }
}
