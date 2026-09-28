package dev.turboism.tests.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TextureAtlasManifestIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void officialTextureAtlasManifestDeclaresTheAdmittedAuthoringPermissions() throws Exception {
        final Path projectRoot = Path.of(System.getProperty("projectRoot"));
        final Path manifest =
                projectRoot.resolve("plugins/atlas-maxrects-bssf/src/main/resources/META-INF/turboism/plugin.json");
        final JsonNode root = JSON.readTree(Files.readString(manifest));
        final Set<String> permissions = new HashSet<>();
        root.path("permissions")
                .forEach(permission -> permissions.add(permission.path("id").asText()));

        assertEquals(
                Set.of(
                        "turboism.cubism.model.read",
                        "turboism.cubism.model.write",
                        "turboism.config.plugin.read",
                        "turboism.config.plugin.write"),
                permissions);
    }
}
