package dev.turboism.tests.distribution;

import dev.turboism.distribution.LocalPluginPackageInspector;
import dev.turboism.distribution.PluginPackageInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginPlanEvidenceIntegrationTest {
    @TempDir Path tempDir;

    @Test void exposesDeepImmutableDistributionOwnedEvidence() throws Exception {
        String descriptor = PluginPackageFixtures.descriptor(PluginPackageFixtures.ID,
            PluginPackageFixtures.VERSION, "0.1.0",
            ",\"authors\":[{\"name\":\"Author\",\"email\":\"author@example.test\"}]"
                + ",\"dependencies\":[{\"id\":\"dev.turboism.plugin.parent\",\"type\":\"required\""
                + ",\"version\":\"0.1.0\",\"ordering\":\"after\",\"reason\":\"needed\"}]"
                + ",\"permissions\":[{\"id\":\"turboism.ui.menu.contribute\",\"scope\":\"application\""
                + ",\"reason\":\"menu\"}],\"capabilities\":[\"ui.menu.contribute\"]"
                + ",\"environment\":{\"requiresCubism\":false,\"ui\":\"none\"}");
        byte[] jar = PluginPackageFixtures.jar(descriptor,
            PluginPackageFixtures.ENTRYPOINT.replace('.', '/') + ".class", "class");
        Path input = tempDir.resolve("evidence.tplugin");
        Files.write(input, PluginPackageFixtures.packageWith(jar,
            PluginPackageFixtures.ID, PluginPackageFixtures.VERSION));

        var result = new LocalPluginPackageInspector().inspect(input);
        if (result instanceof PluginPackageInspector.Rejected rejected) {
            throw new AssertionError(rejected.problems().toString());
        }
        var accepted = assertInstanceOf(PluginPackageInspector.Accepted.class, result);
        var plan = accepted.plan();
        var evidence = plan.descriptor();

        assertEquals(PluginPackageFixtures.sha256(descriptor.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            plan.descriptorSha256());
        assertEquals("Author", evidence.authors().get(0).name());
        assertEquals("dev.turboism.plugin.parent", evidence.dependencies().get(0).id());
        assertEquals("turboism.ui.menu.contribute", evidence.permissions().get(0).id());
        assertEquals(List.of("ui.menu.contribute"), evidence.capabilities());
        assertEquals("none", evidence.environment().ui());
        assertThrows(UnsupportedOperationException.class,
            () -> evidence.entrypoints().put("other", "example.Other"));
        assertThrows(UnsupportedOperationException.class,
            () -> evidence.capabilities().add("other"));
        assertThrows(UnsupportedOperationException.class,
            () -> plan.files().add(plan.files().get(0)));
        assertNotSame(evidence.authors(), evidence.dependencies());
        assertTrue(java.util.Arrays.stream(evidence.getClass().getDeclaredFields())
            .noneMatch(field -> field.getType().getName().startsWith("dev.turboism.sdk")));
        assertTrue(java.util.Arrays.stream(plan.getClass().getDeclaredFields())
            .noneMatch(field -> field.getType().getName().equals("com.fasterxml.jackson.databind.JsonNode")
                || field.getType().equals(Path.class) || field.getType().equals(byte[].class)));
    }
}
