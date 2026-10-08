package dev.turboism.tests.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.config.CubismJvmSettingsFileService;
import dev.turboism.config.TriangulationEdgeIndexPreference;
import dev.turboism.config.TriangulationEdgeIndexSettingsFileService;
import dev.turboism.internal.core.CubismJvmSettingsService.MemoryProfile;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeInstallerConfigRoundTripTest {
    @TempDir
    Path temporary;

    @Test
    void runtimeSavedProfilesSurviveRealInstallerUpgradeAndRuntimeReopen() throws Exception {
        Path root = Path.of(System.getProperty("projectRoot"));
        Path classes = Files.createDirectory(temporary.resolve("installer-classes"));
        Path probe = temporary.resolve("UpgradeProbe.java");
        // Compile the actual standalone installer implementation, with a
        // package-local entry point. No installer policy is reimplemented here.
        Files.writeString(probe, """
            package dev.turboism.installer;
            import java.nio.file.Path;
            import java.util.Set;
            public final class UpgradeProbe {
                public static void upgrade(Path home) throws Exception {
                    var existing = ConfigMerge.loadExisting(home);
                    ConfigMerge.validateCurrent(existing);
                    var disabled = ConfigMerge.mergeDisabled(existing,
                        Set.of("dev.turboism.plugin.test"), Set.of(), true);
                    ConfigMerge.write(home, ConfigMerge.applyPolicy(existing, disabled));
                }
            }
            """);
        Path sources = root.resolve("packaging/java-installer/listener-src/dev/turboism/installer");
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                        .run(
                                null,
                                null,
                                null,
                                "--release",
                                "17",
                                "-d",
                                classes.toString(),
                                sources.resolve("ConfigMerge.java").toString(),
                                sources.resolve("BoundedJson.java").toString(),
                                probe.toString()));
        ObjectMapper mapper = new ObjectMapper();
        try (URLClassLoader installer =
                new URLClassLoader(new URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            var upgrade =
                    installer.loadClass("dev.turboism.installer.UpgradeProbe").getMethod("upgrade", Path.class);
            for (MemoryProfile profile : MemoryProfile.values()) {
                Path home = Files.createDirectory(temporary.resolve(profile.name()));
                Path config = home.resolve("config.json");
                Files.copy(root.resolve("packaging/windows-installer/config.template.json"), config);
                try (var runtime = new CubismJvmSettingsFileService(home)) {
                    runtime.saveMemoryProfile(profile);
                    runtime.saveZgc(false);
                }
                try (var edgeIndex = new TriangulationEdgeIndexSettingsFileService(home)) {
                    edgeIndex.save(false);
                }
                ObjectNode expected = (ObjectNode) mapper.readTree(config.toFile());
                expected.putArray("disabledPlugins").add("dev.turboism.plugin.test");
                upgrade.invoke(null, home);
                assertEquals(
                        expected,
                        mapper.readTree(config.toFile()),
                        "upgrade must preserve every runtime field: " + profile);
                try (var reopened = new CubismJvmSettingsFileService(home)) {
                    assertEquals(profile, reopened.memoryProfile());
                    assertFalse(reopened.zgc(), "unrelated persisted preference survives");
                }
                try (var edgeIndex = new TriangulationEdgeIndexSettingsFileService(home)) {
                    assertFalse(edgeIndex.read(), "explicit edge-index opt-out survives installer upgrade");
                }
                assertFalse(
                        TriangulationEdgeIndexPreference.read(home),
                        "premain must honor the upgraded runtime-saved opt-out");
            }
        }
    }
}
