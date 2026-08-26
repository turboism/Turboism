package dev.turboism.tests.distribution;

import dev.turboism.distribution.LocalPluginPackageInspector;
import dev.turboism.distribution.PluginPackageInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class PluginPackageSecurityIntegrationTest {
    @TempDir Path tempDir;

    @Test void rejectsExactSchemaDrivenContaminationInventory() throws Exception {
        String[] forbidden = {
            "dev/turboism/core/Manager.class", "dev/turboism/internal/Secret.class",
            "dev/turboism/sample/internal/Secret.class", "dev/turboism/sdk/Plugin.class",
            "dev/turboism/test/Fake.class", "dev/turboism/testframework/Fake.class",
            "testframework/fixture.txt", "install.sh", "scripts/setup.ps1",
            "com/live2d/Cubism.class", "lib/nested.jar", "native/plugin.so",
            "META-INF/versions/17/Versioned.class"
        };
        for (String path : forbidden) assertContamination(path);
    }

    @Test void rejectsUndeclaredResourcesOutsideTheDenyTable() throws Exception {
        for (String path : new String[]{
            "tests/fixture.txt",
            "scripts/run.txt",
            "docs/cubism-notes.txt"
        }) {
            byte[] jar = PluginPackageFixtures.jar(
                PluginPackageFixtures.descriptor(
                    PluginPackageFixtures.ID,
                    PluginPackageFixtures.VERSION,
                    "0.1.0"
                ),
                PluginPackageFixtures.ENTRYPOINT.replace('.', '/') + ".class",
                "class",
                path,
                "undeclared"
            );
            assertRejected(
                PluginPackageFixtures.packageWith(
                    jar,
                    PluginPackageFixtures.ID,
                    PluginPackageFixtures.VERSION
                ),
                "PLUGIN_RESOURCE_UNDECLARED"
            );
        }
    }

    @Test void allowsOrdinaryPluginClassesOutsideTheDenyTable() throws Exception {
        byte[] jar = PluginPackageFixtures.jar(
            PluginPackageFixtures.descriptor(
                PluginPackageFixtures.ID,
                PluginPackageFixtures.VERSION,
                "0.1.0"
            ),
            PluginPackageFixtures.ENTRYPOINT.replace('.', '/') + ".class",
            "class",
            "example/WidgetTest.class",
            "class"
        );
        Path input = tempDir.resolve("ordinary-class.tplugin");
        Files.write(input, PluginPackageFixtures.packageWith(
            jar,
            PluginPackageFixtures.ID,
            PluginPackageFixtures.VERSION
        ));
        assertInstanceOf(
            PluginPackageInspector.Accepted.class,
            new LocalPluginPackageInspector().inspect(input)
        );
    }

    @Test void rejectsOtherPluginMetadata() throws Exception {
        assertContamination("other/META-INF/turboism/plugin.json");
    }

    @Test void rejectsNestedJarWithMalformedTerminalStructure() throws Exception {
        byte[] valid = PluginPackageFixtures.jar(
            PluginPackageFixtures.descriptor(PluginPackageFixtures.ID,
                PluginPackageFixtures.VERSION, "0.1.0"),
            PluginPackageFixtures.ENTRYPOINT.replace('.', '/') + ".class", "class");
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
        assertRejected(PluginPackageFixtures.packageWith(trailing, PluginPackageFixtures.ID,
            PluginPackageFixtures.VERSION), "ARTIFACT_JAR_INVALID");
    }

    private void assertContamination(String path) throws Exception {
        byte[] jar = PluginPackageFixtures.jar(
            PluginPackageFixtures.descriptor(PluginPackageFixtures.ID,
                PluginPackageFixtures.VERSION, "0.1.0"),
            PluginPackageFixtures.ENTRYPOINT.replace('.', '/') + ".class", "class",
            path, "forbidden");
        assertRejected(PluginPackageFixtures.packageWith(jar, PluginPackageFixtures.ID,
            PluginPackageFixtures.VERSION), "PLUGIN_CONTENT_CONTAMINATION");
    }

    private void assertRejected(byte[] archive, String code) throws Exception {
        Path input = tempDir.resolve(Integer.toHexString(java.util.Arrays.hashCode(archive)) + ".tplugin");
        Files.write(input, archive);
        var rejected = assertInstanceOf(PluginPackageInspector.Rejected.class,
            new LocalPluginPackageInspector().inspect(input));
        assertEquals(code, rejected.problems().get(0).code());
    }
}
