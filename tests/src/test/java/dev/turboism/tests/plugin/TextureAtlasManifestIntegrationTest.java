package dev.turboism.tests.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextureAtlasManifestIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void officialTextureAtlasManifestDeclaresTheAdmittedAuthoringPermissions() throws Exception {
        final Path projectRoot = Path.of(System.getProperty("projectRoot"));
        final Path manifest = projectRoot.resolve(
            "plugins/texture-atlas/src/main/resources/META-INF/turboism/plugin.json"
        );
        final JsonNode root = JSON.readTree(Files.readString(manifest));
        final Set<String> permissions = new HashSet<>();
        root.path("permissions").forEach(permission -> permissions.add(permission.path("id").asText()));

        assertEquals(
            Set.of("turboism.cubism.model.read", "turboism.cubism.model.write",
                "turboism.config.plugin.read", "turboism.config.plugin.write"),
            permissions
        );
    }
}
