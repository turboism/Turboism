package dev.turboism.tests.distribution;

import dev.turboism.distribution.LocalPluginJarPreparer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DirectPluginJarInstallIntegrationTest {
    @TempDir Path tempDir;

    @Test
    void preparesProjectPluginJarWithoutOuterDistributionPackage() throws Exception {
        final byte[] jar = PluginPackageFixtures.jar(
            PluginPackageFixtures.descriptor(
                PluginPackageFixtures.ID, PluginPackageFixtures.VERSION, "[0.1.0,0.2.0)"),
            PluginPackageFixtures.ENTRYPOINT.replace('.', '/') + ".class", "class"
        );
        final Path selected = tempDir.resolve("selected-plugin.jar");
        Files.write(selected, jar);

        final LocalPluginJarPreparer.Prepared prepared = assertInstanceOf(
            LocalPluginJarPreparer.Prepared.class,
            new LocalPluginJarPreparer().prepare(selected, tempDir.resolve("staging"))
        );

        assertEquals(PluginPackageFixtures.ID, prepared.value().descriptor().id());
        assertEquals(PluginPackageFixtures.VERSION, prepared.value().descriptor().version());
        assertArrayEquals(jar, Files.readAllBytes(prepared.value().stagedJar()));
    }
}
